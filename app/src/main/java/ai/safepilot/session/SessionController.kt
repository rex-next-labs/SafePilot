package ai.safepilot.session

import ai.safepilot.engine.Confirm
import ai.safepilot.engine.DriverIntent
import ai.safepilot.engine.Guidance
import ai.safepilot.engine.IntentClassifier
import ai.safepilot.engine.Outcome
import ai.safepilot.engine.Situation
import ai.safepilot.engine.SituationDetector
import ai.safepilot.vehicle.Gear
import ai.safepilot.vehicle.Known
import ai.safepilot.vehicle.VehicleSnapshot
import ai.safepilot.voice.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class Waiting { NONE, VEHICLE, ANSWER }

sealed interface SessionState {
    data object Idle : SessionState
    data class Guiding(
        val guidance: Guidance,
        val stepIndex: Int,
        val waiting: Waiting,
        /** 마지막으로 말한 문장 */
        val caption: String,
    ) : SessionState
    data class Done(val situation: Situation, val outcome: Outcome) : SessionState
}

/**
 * 대화 상태 머신 (설계서 5.1). 차량 상태를 보고 안내를 시작하고,
 * 단계마다 말한 뒤 차량 데이터나 운전자 답변으로 결과를 확인한다.
 */
class SessionController(
    private val scope: CoroutineScope,
    private val snapshots: StateFlow<VehicleSnapshot>,
    private val speaker: Speaker,
    private val doneHoldMs: Long = 10_000,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val answers = Channel<DriverIntent>(Channel.CONFLATED)

    /**
     * 앱이 질문을 마쳐 답을 기다리기 시작할 때마다 나온다. 화면은 이 신호를 받으면
     * 몇 초간만 마이크를 켠다. 상시 감지가 아니라 방금 던진 질문의 답만 듣는다.
     */
    private val _listenRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val listenRequests: SharedFlow<Unit> = _listenRequests
    private var run: Job? = null

    fun start() {
        scope.launch {
            var prev = snapshots.value
            snapshots.collect { curr ->
                val situation = SituationDetector.detect(prev, curr)
                prev = curr
                if (situation != null && _state.value !is SessionState.Guiding) begin(situation)
                if (shouldCancel(curr)) cancel(silent = true)
            }
        }
    }

    /** 운전자가 마이크로 말한 최종 문장 */
    fun onDriverText(text: String) = onDriverIntent(IntentClassifier.classify(text))

    /** 화면 버튼이나 음성에서 나온 의도 */
    fun onDriverIntent(intent: DriverIntent) {
        val s = _state.value
        when {
            intent == DriverIntent.CANCEL && s is SessionState.Guiding -> cancel(silent = false)
            s is SessionState.Guiding && s.waiting == Waiting.ANSWER &&
                (intent == DriverIntent.YES || intent == DriverIntent.NO) -> answers.trySend(intent)
            s !is SessionState.Guiding && intent == DriverIntent.SLOPE_PARKING -> begin(Situation.SLOPE_PARKING)
            s !is SessionState.Guiding && intent == DriverIntent.TIRE_ISSUE -> begin(Situation.TIRE_ISSUE)
            s is SessionState.Guiding && intent == DriverIntent.UNKNOWN -> scope.launch {
                speaker.say("다시 말씀해 주세요.")
                if (s.waiting == Waiting.ANSWER) _listenRequests.tryEmit(Unit)
            }
            else -> Unit
        }
    }

    fun dismiss() {
        if (_state.value is SessionState.Done) _state.value = SessionState.Idle
    }

    private fun shouldCancel(s: VehicleSnapshot): Boolean {
        val g = _state.value as? SessionState.Guiding ?: return false
        // 주차 안내 중 다시 출발하면 안내를 멈춘다
        return g.guidance.situation == Situation.SLOPE_PARKING &&
            s.gear.let { it is Known.Value && it.value != Gear.PARK }
    }

    private fun begin(situation: Situation) {
        run?.cancel()
        while (answers.tryReceive().isSuccess) Unit
        val guidance = SituationDetector.guidanceFor(situation, snapshots.value)
        run = scope.launch { execute(guidance) }
    }

    private fun cancel(silent: Boolean) {
        run?.cancel()
        speaker.stop()
        _state.value = SessionState.Idle
        if (!silent) scope.launch { speaker.say("안내를 멈출게요.") }
    }

    private suspend fun execute(g: Guidance) {
        for ((i, step) in g.steps.withIndex()) {
            _state.value = SessionState.Guiding(g, i, Waiting.NONE, step.speech)
            speaker.say(step.speech)
            when (val c = step.confirm) {
                Confirm.None -> Unit
                is Confirm.VehicleCondition -> {
                    if (!c.check(snapshots.value)) {
                        setWaiting(Waiting.VEHICLE)
                        val met = withTimeoutOrNull(c.timeoutSec * 1000L) { snapshots.first { c.check(it) } }
                        if (met == null) {
                            say(c.reminder)
                            snapshots.first { c.check(it) }
                        }
                    }
                }
                is Confirm.DriverAnswer -> {
                    setWaiting(Waiting.ANSWER)
                    _listenRequests.tryEmit(Unit)
                    // 답이 없으면 한 번만 다시 묻고, 그 뒤에는 조용히 기다린다
                    val answer = withTimeoutOrNull(ANSWER_REMIND_MS) { answers.receive() } ?: run {
                        say(ANSWER_REMINDER)
                        _listenRequests.tryEmit(Unit)
                        answers.receive()
                    }
                    if (answer == DriverIntent.NO) {
                        finish(g, c.onNo)
                        return
                    }
                }
            }
        }
        finish(g, g.done)
    }

    private suspend fun say(text: String) {
        _state.update { if (it is SessionState.Guiding) it.copy(caption = text) else it }
        speaker.say(text)
    }

    private fun setWaiting(w: Waiting) {
        _state.update { if (it is SessionState.Guiding) it.copy(waiting = w) else it }
    }

    private suspend fun finish(g: Guidance, outcome: Outcome) {
        _state.value = SessionState.Done(g.situation, outcome)
        speaker.say(outcome.speech)
        delay(doneHoldMs)
        if (_state.value is SessionState.Done) _state.value = SessionState.Idle
    }

    private companion object {
        const val ANSWER_REMIND_MS = 20_000L
        const val ANSWER_REMINDER = "'예' 또는 '아니요'라고 말씀해 주세요."
    }
}

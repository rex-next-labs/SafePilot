package ai.safepilot.ui

import ai.safepilot.engine.DriverIntent
import ai.safepilot.session.SessionController
import ai.safepilot.session.SessionState
import ai.safepilot.vehicle.VehicleRepository
import ai.safepilot.voice.Heard
import ai.safepilot.voice.PleosListener
import ai.safepilot.voice.PleosSpeaker
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** [auto]: 앱이 질문한 직후 잠깐 켠 마이크인지(버튼으로 켠 것과 구분해 표시한다) */
data class MicState(val listening: Boolean = false, val auto: Boolean = false, val heard: String = "", val error: String? = null)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val vehicle = VehicleRepository(app)
    private val speaker = PleosSpeaker(app)
    private val listener = PleosListener(app)
    private val session = SessionController(viewModelScope, vehicle.snapshot, speaker)

    val snapshot = vehicle.snapshot
    val state = session.state
    private val _mic = MutableStateFlow(MicState())
    val mic: StateFlow<MicState> = _mic.asStateFlow()
    private var autoWindow: Job? = null

    init {
        speaker.start()
        listener.init()
        vehicle.start()
        session.start()
        viewModelScope.launch {
            listener.results.collect { h ->
                when (h) {
                    is Heard.Partial -> _mic.value = _mic.value.copy(heard = h.text, error = null)
                    is Heard.Final -> {
                        autoWindow?.cancel()
                        _mic.value = MicState(listening = false, heard = h.text)
                        session.onDriverText(h.text)
                    }
                    is Heard.Failed -> {
                        autoWindow?.cancel()
                        _mic.value = MicState(
                            listening = false,
                            error = "음성 인식을 쓸 수 없어요 (${h.reason}). 화면 버튼을 눌러 주세요.",
                        )
                    }
                }
            }
        }
        viewModelScope.launch { session.listenRequests.collect { listenForAnswer() } }
    }

    /** 질문 직후 잠깐만 듣는다. 말이 없으면 끄고, 말하는 중이면 조금 더 기다린다 */
    private fun listenForAnswer() {
        if (_mic.value.listening) return
        _mic.value = MicState(listening = true, auto = true)
        listener.start()
        autoWindow = viewModelScope.launch {
            delay(ANSWER_WINDOW_MS)
            if (_mic.value.listening && _mic.value.heard.isNotBlank()) delay(ANSWER_GRACE_MS)
            if (_mic.value.listening) {
                listener.stop()
                _mic.value = MicState()
            }
        }
    }

    /** 마이크 버튼. 운전자가 먼저 말을 걸 때, 또는 자동 듣기를 놓쳤을 때 쓴다 */
    fun toggleMic() {
        autoWindow?.cancel()
        if (_mic.value.listening) {
            listener.stop()
            _mic.value = _mic.value.copy(listening = false, auto = false)
        } else {
            speaker.stop()
            _mic.value = MicState(listening = true)
            listener.start()
            // 말이 끝났는데 인식기가 끝을 알리지 않는 경우를 대비해 최대 시간을 둔다
            autoWindow = viewModelScope.launch {
                delay(MANUAL_WINDOW_MS)
                if (_mic.value.listening) {
                    listener.stop()
                    _mic.value = MicState(error = if (_mic.value.heard.isBlank()) "말소리를 듣지 못했어요. 다시 눌러 주세요." else null)
                }
            }
        }
    }

    fun answer(intent: DriverIntent) {
        // 버튼으로 답하면 듣던 마이크는 끈다
        if (_mic.value.listening) {
            autoWindow?.cancel()
            listener.stop()
            _mic.value = MicState()
        }
        session.onDriverIntent(intent)
    }

    fun dismiss() = session.dismiss()

    /** Gleo AI 음성 호출. 이미 안내 중이면 새로 시작하지 않는다 */
    fun startFromGleo(intent: DriverIntent): Boolean {
        if (session.state.value is SessionState.Guiding) return false
        session.onDriverIntent(intent)
        return true
    }

    override fun onCleared() {
        vehicle.stop()
        speaker.release()
        listener.release()
    }

    private companion object {
        const val ANSWER_WINDOW_MS = 6_000L
        const val ANSWER_GRACE_MS = 3_000L
        const val MANUAL_WINDOW_MS = 10_000L
    }
}

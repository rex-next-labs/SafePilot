package ai.safepilot.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.tts.constant.Mode as TtsMode
import android.speech.tts.TextToSpeech as AndroidTts

private const val TAG = "SafePilot.Voice"

/** 문장 길이에 맞춘 최대 대기 시간. TTS가 끝을 알리지 않아도 대화가 멈추지 않게 한다 */
private fun maxSpeakMs(text: String) = 3_000L + text.length * 250L
private const val READY_WAIT_MS = 3_000L

/**
 * Pleos TTS(기기 내 처리, ko-KR). 초기화에 실패하면 Android 기본 TTS로, 그것도 안 되면
 * 화면 자막만 남긴다. 서버 모드는 Playground 키를 등록한 뒤에 쓴다.
 */
class PleosSpeaker(private val context: Context) : Speaker {
    private var pleos: TextToSpeech? = null
    private var android: AndroidTts? = null
    @Volatile private var pending: CompletableDeferred<Unit>? = null
    /** 초기화가 끝나면(성공이든 대체든) 완료. 앱이 막 켜졌을 때 첫 문장이 묻히지 않게 한다 */
    private val ready = CompletableDeferred<Unit>()

    fun start() {
        val tts = TextToSpeech(context, TtsMode.ON_DEVICE)
        pleos = tts
        // initialize가 끝나기 전에 다른 함수를 부르면 SDK 내부가 준비되지 않아 실패한다
        runCatching {
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    runCatching {
                        tts.addEventListener(object : EventListener {
                            override fun onStart() {}
                            override fun onReady() {}
                            override fun onDone() { pending?.complete(Unit) }
                            override fun onError(p0: ErrorType) { Log.w(TAG, "tts error $p0"); pending?.complete(Unit) }
                            override fun onStop() { pending?.complete(Unit) }
                            override fun onUpdatedRms(p0: Double) {}
                        })
                        tts.setLocale(Locale.KOREA)
                    }.onFailure { Log.w(TAG, "Pleos TTS setup", it) }
                    Log.i(TAG, "Pleos TTS ready")
                    ready.complete(Unit)
                }
                override fun onFailure(p0: ErrorType) { Log.w(TAG, "Pleos TTS init failed: $p0"); useAndroidTts() }
            })
        }.onFailure { Log.w(TAG, "Pleos TTS unavailable", it); useAndroidTts() }
    }

    private fun useAndroidTts() {
        pleos = null
        android = AndroidTts(context) { status ->
            if (status == AndroidTts.SUCCESS) android?.language = Locale.KOREA else android = null
            ready.complete(Unit)
        }.apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { pending?.complete(Unit) }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { pending?.complete(Unit) }
            })
        }
    }

    override suspend fun say(text: String) {
        withTimeoutOrNull(READY_WAIT_MS) { ready.await() }
        Log.i(TAG, "say: $text")
        val done = CompletableDeferred<Unit>()
        pending = done
        val started = runCatching {
            when {
                pleos != null -> pleos!!.speak(text, true)
                android != null -> android!!.speak(text, AndroidTts.QUEUE_FLUSH, null, "sp")
                else -> return
            }
        }.isSuccess
        if (started) withTimeoutOrNull(maxSpeakMs(text)) { done.await() }
    }

    override fun stop() {
        runCatching { pleos?.stop() }
        runCatching { android?.stop() }
        pending?.complete(Unit)
    }

    fun release() {
        runCatching { pleos?.release() }
        android?.shutdown()
    }
}

/** Pleos STT(기기 내 처리, ko-KR). 마이크 버튼을 누를 때만 request()로 듣는다. */
class PleosListener(private val context: Context) : Listener {
    private val _results = MutableSharedFlow<Heard>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val results: SharedFlow<Heard> = _results
    private var stt: SpeechToText? = null
    private var ready = false

    fun init() {
        runCatching {
            val s = SpeechToText(context, SttMode.ON_DEVICE)
            stt = s
            s.addListener(object : ResultListener {
                override fun onUpdated(p0: String, p1: Boolean) {
                    val text = p0
                    _results.tryEmit(if (p1) Heard.Final(text) else Heard.Partial(text))
                }
                override fun onUpdatedEpdData(p0: Long, p1: Long) {}
                override fun onStartedRecognition() {}
                override fun onEndedRecognition() {}
                override fun onError(p0: SttErrorType) { _results.tryEmit(Heard.Failed(p0.name)) }
                override fun onReady() {}
                override fun onUpdatedRms(p0: Double) {}
            })
            s.initialize(object : SttInitListener {
                override fun onSuccess() {
                    runCatching { s.setLocale(Locale.KOREA) }
                    ready = true
                    Log.i(TAG, "Pleos STT ready")
                }
                override fun onFailure(p0: SttErrorType) { Log.w(TAG, "Pleos STT init failed: $p0") }
            })
        }.onFailure { Log.w(TAG, "Pleos STT unavailable", it) }
    }

    override fun start() {
        val s = stt
        if (s == null || !ready) {
            _results.tryEmit(Heard.Failed("STT_NOT_READY"))
            return
        }
        runCatching { s.request() }.onFailure { _results.tryEmit(Heard.Failed(it.message ?: "request failed")) }
    }

    override fun stop() { runCatching { stt?.stop() } }

    fun release() { runCatching { stt?.release() } }
}

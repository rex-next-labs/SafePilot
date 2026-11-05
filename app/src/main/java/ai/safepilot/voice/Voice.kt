package ai.safepilot.voice

import kotlinx.coroutines.flow.Flow

/** 음성 출력. 말하기가 끝날 때까지 기다린다. */
interface Speaker {
    suspend fun say(text: String)
    fun stop()
}

sealed interface Heard {
    data class Partial(val text: String) : Heard
    data class Final(val text: String) : Heard
    data class Failed(val reason: String) : Heard
}

/** 음성 입력. 마이크 버튼을 눌렀을 때만 듣는다(상시 감지 없음). */
interface Listener {
    val results: Flow<Heard>
    fun start()
    fun stop()
}

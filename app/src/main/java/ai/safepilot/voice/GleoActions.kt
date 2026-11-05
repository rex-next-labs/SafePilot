package ai.safepilot.voice

import ai.safepilot.engine.DriverIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

/**
 * Pleos Gleo AI 연동 (Gleo Interface). 운전자가 "세이프파일럿, 주차 확인해 줘"라고 부르면
 * Gleo가 assets/schema.json의 기능 이름을 Intent action으로 앱을 연다.
 */
object GleoActions {
    private const val TAG = "SafePilot.Gleo"
    private const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"

    private val actions = mapOf(
        "CHECK_PARKING_SAFETY" to DriverIntent.SLOPE_PARKING,
        "EXPLAIN_TIRE_WARNING" to DriverIntent.TIRE_ISSUE,
    )

    /** Gleo가 보낸 Intent면 시작할 안내를, 아니면 null */
    fun intentOf(intent: Intent?): DriverIntent? = intent?.action?.let { actions[functionName(it)] }

    /** Gleo는 "ai.safepilot.CHECK_PARKING_SAFETY"처럼 패키지 이름을 붙여 보낸다. 기능 이름만 남긴다 */
    private fun functionName(action: String) = action.substringAfterLast('.')

    /**
     * Gleo에 결과를 알린다(7초 안에 보내야 한다). 안내는 SafePilot이 직접 말하므로
     * Gleo의 응답 음성은 끈다(mode = silent).
     */
    fun respond(context: Context, intent: Intent, started: Boolean) {
        val function = intent.action?.let(::functionName) ?: return
        runCatching {
            val response = JSONObject().apply {
                put("functionName", function)
                put("status", if (started) "success" else "fail")
                put("message", if (started) "SafePilot started the voice guidance." else "SafePilot is already guiding the driver.")
                put("mode", "silent")
            }
            context.sendBroadcast(Intent(RESPONSE_ACTION).apply {
                putExtra("packageName", context.packageName)
                putExtra("response", response.toString())
            })
            Log.i(TAG, "responded $response")
        }.onFailure { Log.w(TAG, "respond failed", it) }
    }
}

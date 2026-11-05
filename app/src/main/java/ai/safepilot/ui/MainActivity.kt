package ai.safepilot.ui

import ai.safepilot.voice.GleoActions
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val needed = listOf(Manifest.permission.RECORD_AUDIO, "android.car.permission.CAR_SPEED")
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 1)
        setContent { SafePilotApp(vm) }
        if (savedInstanceState == null) handleGleo(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleGleo(intent)
    }

    /** Gleo AI가 음성 호출로 연 경우 해당 안내를 시작하고 결과를 Gleo에 알린다 */
    private fun handleGleo(intent: Intent?) {
        val request = GleoActions.intentOf(intent) ?: return
        val started = vm.startFromGleo(request)
        GleoActions.respond(this, intent!!, started)
    }
}

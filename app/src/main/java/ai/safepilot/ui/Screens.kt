package ai.safepilot.ui

import ai.safepilot.R
import ai.safepilot.engine.DriverIntent
import ai.safepilot.session.SessionState
import ai.safepilot.session.Waiting
import ai.safepilot.vehicle.Gear
import ai.safepilot.vehicle.Known
import ai.safepilot.vehicle.VehicleSnapshot
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// 숏폼 영상의 SafePilot UI 기준: 어두운 배경, 파란 강조, 빨강·초록 상태
private object C {
    val bg = Color(0xFF0E1116)
    val panel = Color(0xFF181C23)
    val line = Color(0xFF2A303A)
    val text = Color(0xFFF2F4F7)
    val sub = Color(0xFF9AA3AF)
    val blue = Color(0xFF3D8BFF)
    val green = Color(0xFF34C77B)
    val red = Color(0xFFFF5A5A)
    val amber = Color(0xFFFFB547)
}

@Composable
fun SafePilotApp(vm: AppViewModel) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val mic by vm.mic.collectAsStateWithLifecycle()

    Row(Modifier.fillMaxSize().background(C.bg).padding(32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        StatusPanel(snapshot, Modifier.width(360.dp))
        Column(Modifier.weight(1f).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val s = state) {
                    SessionState.Idle -> IdleCard(onStart = vm::answer)
                    is SessionState.Guiding -> GuidingCard(s, onAnswer = vm::answer)
                    is SessionState.Done -> DoneCard(s, onClose = vm::dismiss)
                }
            }
            Spacer(Modifier.height(24.dp))
            MicBar(mic, onMic = vm::toggleMic)
        }
    }
}

@Composable
private fun StatusPanel(s: VehicleSnapshot, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(C.panel).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("SafePilot", color = C.blue, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("차량 상태", color = C.sub, fontSize = 20.sp)
        StatusRow("기어", s.gear.label { when (it) { Gear.PARK -> "P"; Gear.REVERSE -> "R"; Gear.NEUTRAL -> "N"; Gear.DRIVE -> "D"; Gear.OTHER -> "-" } })
        StatusRow("속도", s.speedKmh.label { "${it.toInt()} km/h" })
        StatusRow("주차 브레이크", s.parkingBrakeOn.label { if (it) "작동" else "꺼짐" }, s.parkingBrakeOn.tone(okWhen = true))
        StatusRow("타이어 공기압", s.tpmsWarning.label { if (it) "경고" else "정상" }, s.tpmsWarning.tone(okWhen = false))
        StatusRow("에어백", s.airbagDeployed.label { if (it) "전개됨" else "정상" }, s.airbagDeployed.tone(okWhen = false))
    }
}

private fun <T> Known<T>.label(f: (T) -> String): String = when (this) {
    is Known.Value -> f(value)
    Known.Unsupported -> "확인 불가"
    Known.Pending -> "…"
}

private fun Known<Boolean>.tone(okWhen: Boolean): Color = when (this) {
    is Known.Value -> if (value == okWhen) C.green else C.red
    else -> C.sub
}

@Composable
private fun StatusRow(name: String, value: String, tone: Color = C.text) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = C.sub, fontSize = 22.sp)
        Text(value, color = tone, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(C.panel).padding(40.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) { content() }
}

@Composable
private fun IdleCard(onStart: (DriverIntent) -> Unit) = Card {
    Text("차량 상태를 지켜보고 있어요", color = C.text, fontSize = 36.sp, fontWeight = FontWeight.Bold)
    Text("안내가 필요한 순간에 먼저 알려 드릴게요. 필요할 땐 \"세이프파일럿, 주차 확인해 줘\"처럼 불러 주세요.", color = C.sub, fontSize = 24.sp)
    Spacer(Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ChoiceButton("주차 확인", C.line) { onStart(DriverIntent.SLOPE_PARKING) }
        ChoiceButton("타이어 점검", C.line) { onStart(DriverIntent.TIRE_ISSUE) }
    }
}

@Composable
private fun GuidingCard(s: SessionState.Guiding, onAnswer: (DriverIntent) -> Unit) = Card {
    Text(s.guidance.situation.title, color = C.blue, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
    Text(s.caption, color = C.text, fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 52.sp)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        s.guidance.steps.forEachIndexed { i, step ->
            val (mark, color) = when {
                i < s.stepIndex -> "✓" to C.green
                i == s.stepIndex -> "●" to C.blue
                else -> "○" to C.sub
            }
            Text("$mark  ${step.card}", color = color, fontSize = 24.sp)
        }
    }
    s.guidance.steps.getOrNull(s.stepIndex)?.source?.let { Text("근거: $it", color = C.sub, fontSize = 18.sp) }
    Spacer(Modifier.weight(1f))
    when (s.waiting) {
        Waiting.ANSWER -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ChoiceButton("예", C.blue) { onAnswer(DriverIntent.YES) }
            ChoiceButton("아니요", C.line) { onAnswer(DriverIntent.NO) }
        }
        Waiting.VEHICLE -> Text("차량 상태를 확인하고 있어요…", color = C.amber, fontSize = 24.sp)
        Waiting.NONE -> Unit
    }
}

@Composable
private fun DoneCard(s: SessionState.Done, onClose: () -> Unit) = Card {
    Text(s.situation.title, color = C.blue, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
    Text(s.outcome.speech, color = C.text, fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 52.sp)
    s.outcome.summary.forEach { line ->
        Text(line, color = if (line.endsWith("✓")) C.green else C.amber, fontSize = 28.sp)
    }
    Spacer(Modifier.weight(1f))
    ChoiceButton("닫기", C.line, onClose)
}

@Composable
private fun ChoiceButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.height(88.dp).width(220.dp).clip(RoundedCornerShape(20.dp)).background(color).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = C.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun MicBar(mic: MicState, onMic: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.panel).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(if (mic.listening) C.red else C.blue)
                .border(3.dp, C.text.copy(alpha = 0.2f), CircleShape).clickable(onClick = onMic),
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(R.drawable.ic_mic), contentDescription = "마이크", tint = C.text, modifier = Modifier.size(48.dp)) }
        Text(
            when {
                mic.error != null -> mic.error
                mic.listening -> when {
                    mic.heard.isNotBlank() -> mic.heard
                    mic.auto -> "답을 듣고 있어요… 말씀하시거나 버튼을 눌러 주세요"
                    else -> "듣고 있어요…"
                }
                mic.heard.isNotBlank() -> "\"${mic.heard}\""
                else -> "\"세이프파일럿\"을 부르거나 마이크 버튼을 눌러 주세요"
            },
            color = if (mic.error != null) C.amber else C.text, fontSize = 26.sp,
        )
    }
}

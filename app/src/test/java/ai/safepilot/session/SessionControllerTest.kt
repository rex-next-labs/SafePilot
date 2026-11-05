package ai.safepilot.session

import ai.safepilot.engine.DriverIntent
import ai.safepilot.engine.Situation
import ai.safepilot.vehicle.Gear
import ai.safepilot.vehicle.Known
import ai.safepilot.vehicle.VehicleSnapshot
import ai.safepilot.voice.Speaker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSpeaker : Speaker {
    val said = mutableListOf<String>()
    override suspend fun say(text: String) { said += text }
    override fun stop() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionControllerTest {
    private val driving = VehicleSnapshot(
        gear = Known.Value(Gear.DRIVE), speedKmh = Known.Value(0f),
        parkingBrakeOn = Known.Value(false), tpmsWarning = Known.Value(false),
    )

    private fun TestScope.setup(): Triple<MutableStateFlow<VehicleSnapshot>, FakeSpeaker, SessionController> {
        val flow = MutableStateFlow(driving)
        val speaker = FakeSpeaker()
        val c = SessionController(backgroundScope, flow, speaker)
        c.start()
        runCurrent()
        return Triple(flow, speaker, c)
    }

    @Test fun slopeParking_fullFlow() = runTest {
        val (flow, speaker, c) = setup()
        flow.value = driving.copy(gear = Known.Value(Gear.PARK))
        runCurrent()
        assertEquals(listOf("주차 브레이크가 꺼져 있어요."), speaker.said)
        assertEquals(Waiting.VEHICLE, (c.state.value as SessionState.Guiding).waiting)

        flow.value = flow.value.copy(parkingBrakeOn = Known.Value(true))
        runCurrent()
        assertEquals("확인됐어요. 혹시 경사진 곳인가요?", speaker.said.last())

        c.onDriverText("응")
        runCurrent()
        assertTrue(speaker.said.last().startsWith("경사진 곳에선 핸들을"))

        c.onDriverText("네 돌렸어요")
        runCurrent()
        val done = c.state.value as SessionState.Done
        assertEquals(Situation.SLOPE_PARKING, done.situation)
        assertEquals(listOf("주차 브레이크 ✓", "핸들 방향 ✓"), done.outcome.summary)
    }

    @Test fun slopeParking_flatGround_endsEarly() = runTest {
        val (flow, speaker, c) = setup()
        flow.value = driving.copy(gear = Known.Value(Gear.PARK))
        runCurrent()
        flow.value = flow.value.copy(parkingBrakeOn = Known.Value(true))
        runCurrent()
        c.onDriverIntent(DriverIntent.NO)
        runCurrent()
        assertEquals("알겠어요. 주차를 마쳤어요.", speaker.said.last())
    }

    @Test fun slopeParking_remindsOnceWhenBrakeNotApplied() = runTest {
        val (flow, speaker, _) = setup()
        flow.value = driving.copy(gear = Known.Value(Gear.PARK))
        runCurrent()
        advanceTimeBy(20_001)
        assertEquals("주차 브레이크를 걸어 주세요.", speaker.said.last())
        advanceTimeBy(60_000)
        assertEquals(2, speaker.said.size)
    }

    @Test fun slopeParking_cancelsWhenDrivingOff() = runTest {
        val (flow, _, c) = setup()
        flow.value = driving.copy(gear = Known.Value(Gear.PARK))
        runCurrent()
        flow.value = flow.value.copy(gear = Known.Value(Gear.DRIVE))
        runCurrent()
        assertEquals(SessionState.Idle, c.state.value)
    }

    @Test fun tire_whileMoving_waitsForStop() = runTest {
        val (flow, speaker, c) = setup()
        flow.value = driving.copy(speedKmh = Known.Value(80f), tpmsWarning = Known.Value(true))
        runCurrent()
        assertEquals("속도를 줄이고 안전한 곳에 차를 세워 주세요.", speaker.said.last())
        assertEquals(Waiting.VEHICLE, (c.state.value as SessionState.Guiding).waiting)
        flow.value = flow.value.copy(speedKmh = Known.Value(0f))
        runCurrent()
        assertTrue(c.state.value is SessionState.Done)
    }

    @Test fun voiceStart_whenIdle() = runTest {
        val (flow, speaker, c) = setup()
        flow.value = driving.copy(gear = Known.Value(Gear.PARK), parkingBrakeOn = Known.Value(true))
        runCurrent()
        assertEquals(SessionState.Idle, c.state.value)
        c.onDriverText("경사로 주차 확인해 줘")
        runCurrent()
        assertEquals("혹시 경사진 곳인가요?", speaker.said.last())
    }

    @Test fun asksForAnswer_afterEachQuestion() = runTest {
        val (flow, speaker, c) = setup()
        var requests = 0
        backgroundScope.launch { c.listenRequests.collect { requests++ } }
        runCurrent()
        flow.value = driving.copy(gear = Known.Value(Gear.PARK))
        runCurrent()
        // 주차 브레이크를 기다리는 동안에는 듣지 않는다(차량 데이터로 확인)
        assertEquals(0, requests)
        flow.value = flow.value.copy(parkingBrakeOn = Known.Value(true))
        runCurrent()
        assertEquals(1, requests)
        // 못 알아들으면 다시 묻고 한 번 더 듣는다
        c.onDriverText("음악 틀어 줘")
        runCurrent()
        assertEquals("다시 말씀해 주세요.", speaker.said.last())
        assertEquals(2, requests)
        c.onDriverText("응")
        runCurrent()
        assertEquals(3, requests)
    }

    @Test fun noAnswer_remindsOnceAndListensAgain() = runTest {
        val (flow, speaker, c) = setup()
        var requests = 0
        backgroundScope.launch { c.listenRequests.collect { requests++ } }
        flow.value = driving.copy(gear = Known.Value(Gear.PARK), parkingBrakeOn = Known.Value(true))
        runCurrent()
        c.onDriverText("주차 확인해 줘")
        runCurrent()
        assertEquals(1, requests)
        advanceTimeBy(20_001)
        assertEquals("'예' 또는 '아니요'라고 말씀해 주세요.", speaker.said.last())
        assertEquals(2, requests)
        advanceTimeBy(60_000)
        assertEquals(2, requests)
        c.onDriverIntent(DriverIntent.NO)
        runCurrent()
        assertEquals("알겠어요. 주차를 마쳤어요.", speaker.said.last())
    }

    @Test fun yesWhileIdle_isIgnored() = runTest {
        val (_, speaker, c) = setup()
        c.onDriverText("응")
        runCurrent()
        assertEquals(SessionState.Idle, c.state.value)
        assertTrue(speaker.said.isEmpty())
    }
}

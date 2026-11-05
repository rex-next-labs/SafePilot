package ai.safepilot.engine

import ai.safepilot.vehicle.Gear
import ai.safepilot.vehicle.Known
import ai.safepilot.vehicle.VehicleSnapshot
import ai.safepilot.vehicle.Wheel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentClassifierTest {
    private fun check(expected: DriverIntent, vararg texts: String) =
        texts.forEach { assertEquals("'$it'", expected, IntentClassifier.classify(it)) }

    @Test fun yes() = check(DriverIntent.YES, "응", "네", "예.", "응 경사야", "맞아", "그래", "걸었어", "돌렸어요", "했어", "네 돌렸어요", "어 맞아")
    @Test fun no() = check(DriverIntent.NO, "아니", "아니요", "아냐 평지야", "아뇨", "안 했어", "아직 안 걸었어", "못 했어", "안 돌렸어", "평지야")
    @Test fun cancel() = check(DriverIntent.CANCEL, "그만", "취소해 줘", "안내 멈춰")
    @Test fun tire() = check(DriverIntent.TIRE_ISSUE, "타이어 펑크 난 것 같아", "공기압 경고 떴어", "바퀴가 이상해")
    @Test fun slope() = check(DriverIntent.SLOPE_PARKING, "주차 확인해 줘", "경사로 주차 도와줘", "언덕에 세웠는데")
    @Test fun unknown() = check(DriverIntent.UNKNOWN, "", "오늘 날씨 어때", "음악 틀어 줘")
}

class SituationDetectorTest {
    private val driving = VehicleSnapshot(gear = Known.Value(Gear.DRIVE), parkingBrakeOn = Known.Value(false), tpmsWarning = Known.Value(false))

    @Test fun parkWithBrakeOff_startsSlopeParking() {
        val parked = driving.copy(gear = Known.Value(Gear.PARK))
        assertEquals(Situation.SLOPE_PARKING, SituationDetector.detect(driving, parked))
    }

    @Test fun parkWithBrakeOn_noGuidance() {
        val parked = driving.copy(gear = Known.Value(Gear.PARK), parkingBrakeOn = Known.Value(true))
        assertNull(SituationDetector.detect(driving, parked))
    }

    @Test fun parkWithBrakeUnknown_asksDriver() {
        val parked = driving.copy(gear = Known.Value(Gear.PARK), parkingBrakeOn = Known.Unsupported)
        assertEquals(Situation.SLOPE_PARKING, SituationDetector.detect(driving, parked))
    }

    @Test fun alreadyParked_noRepeat() {
        val parked = driving.copy(gear = Known.Value(Gear.PARK))
        assertNull(SituationDetector.detect(parked, parked))
    }

    @Test fun tpmsTurnsOn_startsTire() {
        assertEquals(Situation.TIRE_ISSUE, SituationDetector.detect(driving, driving.copy(tpmsWarning = Known.Value(true))))
    }

    @Test fun wheelBelowThreshold_startsTire() {
        val base = driving.copy(lowTirePressureKpa = Known.Value(137f),
            tirePressureKpa = Known.Value(Wheel.entries.associateWith { 230f }))
        val low = base.copy(tirePressureKpa = Known.Value(Wheel.entries.associateWith { if (it == Wheel.RIGHT_FRONT) 120f else 230f }))
        assertEquals(Situation.TIRE_ISSUE, SituationDetector.detect(base, low))
    }
}

class ScenarioTest {
    @Test fun slope_brakeOff_waitsForBrakeThenAsksSlope() {
        val g = SlopeParking.build(VehicleSnapshot(parkingBrakeOn = Known.Value(false)))
        assertEquals(3, g.steps.size)
        assertTrue(g.steps[0].confirm is Confirm.VehicleCondition)
        assertTrue(g.steps[1].speech.startsWith("확인됐어요"))
        assertEquals("도로교통법 시행령 제11조 제3항", g.steps[2].source)
    }

    @Test fun slope_brakeUnknown_asksInsteadOfClaiming() {
        val g = SlopeParking.build(VehicleSnapshot(parkingBrakeOn = Known.Unsupported))
        assertTrue(g.steps[0].confirm is Confirm.DriverAnswer)
        assertTrue(g.steps.none { it.speech.contains("확인됐어요") })
    }

    @Test fun tire_neverClaimsPuncture() {
        val g = TireIssue.build(VehicleSnapshot(speedKmh = Known.Value(0f), tpmsWarning = Known.Value(true)))
        val all = g.steps.joinToString(" ") { it.speech }
        assertTrue(all.contains("펑크일 수 있어요"))
        assertTrue(!all.contains("펑크입니다") && !all.contains("펑크가 났"))
    }

    @Test fun tire_whileMoving_asksToStopFirst() {
        val g = TireIssue.build(VehicleSnapshot(speedKmh = Known.Value(60f), tpmsWarning = Known.Value(true)))
        assertTrue(g.steps.any { it.confirm is Confirm.VehicleCondition })
    }

    @Test fun tire_namesWheelWhenKnown() {
        val s = VehicleSnapshot(speedKmh = Known.Value(0f), lowTirePressureKpa = Known.Value(137f),
            tirePressureKpa = Known.Value(Wheel.entries.associateWith { if (it == Wheel.RIGHT_FRONT) 110f else 230f }))
        assertTrue(TireIssue.build(s).steps[0].speech.startsWith("앞 오른쪽 타이어"))
    }
}

package ai.safepilot.engine

import ai.safepilot.vehicle.Gear
import ai.safepilot.vehicle.Known
import ai.safepilot.vehicle.VehicleSnapshot
import ai.safepilot.vehicle.valueOrNull

private const val SLOPE_LAW = "도로교통법 시행령 제11조 제3항"

/** 경사로 주차 (설계서 5.2). 앱은 경사를 알 수 없으므로 운전자에게 묻는다. */
object SlopeParking {
    fun build(s: VehicleSnapshot): Guidance {
        val steps = mutableListOf<GuidanceStep>()
        when (s.parkingBrakeOn) {
            Known.Value(false) -> steps += GuidanceStep(
                speech = "주차 브레이크가 꺼져 있어요.",
                card = "주차 브레이크를 걸어 주세요",
                confirm = Confirm.VehicleCondition(reminder = "주차 브레이크를 걸어 주세요.") {
                    it.parkingBrakeOn == Known.Value(true)
                },
            )
            Known.Value(true) -> Unit
            // 읽을 수 없으면 묻는다
            else -> steps += GuidanceStep(
                speech = "주차 브레이크를 거셨나요?",
                card = "주차 브레이크 확인 불가 · 거셨나요?",
                confirm = Confirm.DriverAnswer(
                    onNo = Outcome("주차 브레이크를 꼭 걸어 주세요.", listOf("주차 브레이크 걸어 주세요")),
                ),
            )
        }
        steps += GuidanceStep(
            speech = if (steps.isEmpty() || s.parkingBrakeOn !is Known.Value) "혹시 경사진 곳인가요?" else "확인됐어요. 혹시 경사진 곳인가요?",
            card = "경사진 곳인가요?",
            confirm = Confirm.DriverAnswer(onNo = Outcome("알겠어요. 주차를 마쳤어요.", listOf("주차 브레이크 ✓"))),
        )
        steps += GuidanceStep(
            speech = "경사진 곳에선 핸들을 도로 가장자리 쪽으로 돌려 두셔야 해요. 돌리셨나요?",
            card = "핸들을 도로 가장자리 쪽으로",
            confirm = Confirm.DriverAnswer(
                onNo = Outcome(
                    "핸들을 도로 가장자리 쪽으로 돌려 두세요. 바퀴에 고임목을 받쳐도 돼요.",
                    listOf("주차 브레이크 ✓", "핸들 방향 확인 필요"),
                ),
            ),
            source = SLOPE_LAW,
        )
        return Guidance(
            situation = Situation.SLOPE_PARKING,
            steps = steps,
            done = Outcome("경사로 주차를 마쳤어요.", listOf("주차 브레이크 ✓", "핸들 방향 ✓")),
        )
    }
}

/** 타이어 공기압 경고. "펑크일 수 있어요"까지만 말하고, 교체는 조건을 붙인다. */
object TireIssue {
    fun build(s: VehicleSnapshot): Guidance {
        val wheels = s.lowPressureWheels.sortedBy { it.ordinal }
        val where = wheels.joinToString(", ") { it.label }
        val steps = mutableListOf<GuidanceStep>()
        steps += GuidanceStep(
            speech = if (wheels.isEmpty()) "타이어 공기압 경고가 켜졌어요. 펑크일 수 있어요."
            else "$where 타이어 공기압이 낮아요. 펑크일 수 있어요.",
            card = if (wheels.isEmpty()) "공기압 경고 · 어느 바퀴인지 확인해 보세요" else "$where 공기압 낮음",
            confirm = Confirm.None,
        )
        if (!s.isStopped) {
            steps += GuidanceStep(
                speech = "속도를 줄이고 안전한 곳에 차를 세워 주세요.",
                card = "안전한 곳에 정차",
                confirm = Confirm.VehicleCondition(reminder = "안전한 곳에 차를 세워 주세요.", timeoutSec = 60) { it.isStopped },
            )
        }
        steps += GuidanceStep(
            speech = "정비소에서 점검을 받아 보세요. 타이어 하나만 바꿔도 되는지는 구동 방식과 다른 타이어 마모 상태에 따라 달라서, 정비소에서 함께 확인해 보세요.",
            card = "정비소 점검 · 교체 범위는 정비소에서 확인",
            confirm = Confirm.None,
        )
        return Guidance(
            situation = Situation.TIRE_ISSUE,
            steps = steps,
            done = Outcome(
                "안내를 마쳤어요. 가까운 정비소에서 점검을 받아 주세요.",
                listOfNotNull(
                    if (wheels.isEmpty()) "공기압 경고 (바퀴 확인 필요)" else "$where 공기압 낮음",
                    "정차 ✓".takeIf { !s.isStopped },
                    "정비소 점검 필요",
                ),
            ),
        )
    }
}

/** 차량 상태 변화에서 안내가 필요한 순간을 찾는다. 앱이 먼저 알아채는 경우만 다룬다. */
object SituationDetector {
    fun detect(prev: VehicleSnapshot, curr: VehicleSnapshot): Situation? {
        val enteredPark = prev.gear.valueOrNull().let { it != null && it != Gear.PARK } &&
            curr.gear == Known.Value(Gear.PARK)
        if (enteredPark && curr.parkingBrakeOn != Known.Value(true)) return Situation.SLOPE_PARKING

        val tpmsOn = prev.tpmsWarning != Known.Value(true) && curr.tpmsWarning == Known.Value(true)
        val newLowWheel = (curr.lowPressureWheels - prev.lowPressureWheels).isNotEmpty()
        if (tpmsOn || newLowWheel) return Situation.TIRE_ISSUE
        return null
    }

    fun guidanceFor(situation: Situation, s: VehicleSnapshot): Guidance = when (situation) {
        Situation.SLOPE_PARKING -> SlopeParking.build(s)
        Situation.TIRE_ISSUE -> TireIssue.build(s)
    }
}

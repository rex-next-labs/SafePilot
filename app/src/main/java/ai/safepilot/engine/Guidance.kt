package ai.safepilot.engine

import ai.safepilot.vehicle.VehicleSnapshot

enum class Situation(val title: String) {
    SLOPE_PARKING("주차 확인"),
    TIRE_ISSUE("타이어 공기압 경고"),
}

/** 안내 한 단계: 말하고, 결과를 확인한다. */
data class GuidanceStep(
    val speech: String,
    /** 화면 카드 문구. 짧게 */
    val card: String,
    val confirm: Confirm,
    val source: String? = null,
)

sealed interface Confirm {
    /** 차량 데이터가 조건을 만족하면 다음 단계. 시간 안에 안 되면 한 번 더 안내한다 */
    data class VehicleCondition(
        val reminder: String,
        val timeoutSec: Int = 20,
        val check: (VehicleSnapshot) -> Boolean,
    ) : Confirm

    /** 운전자 답변(음성 또는 버튼)으로 다음 단계를 정한다. 아니라고 하면 [onNo]로 끝낸다 */
    data class DriverAnswer(val onNo: Outcome) : Confirm

    /** 말하고 바로 다음 단계 */
    data object None : Confirm
}

/** 시나리오가 끝날 때 보여 주는 요약 */
data class Outcome(val speech: String, val summary: List<String>)

/** 한 상황의 안내 전체. 단계는 앞에서부터 차례로 진행한다 */
data class Guidance(
    val situation: Situation,
    val steps: List<GuidanceStep>,
    val done: Outcome,
)

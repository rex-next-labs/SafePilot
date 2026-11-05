package ai.safepilot.engine

enum class DriverIntent { YES, NO, CANCEL, SLOPE_PARKING, TIRE_ISSUE, UNKNOWN }

/** 짧은 운전자 발화를 키워드와 규칙으로 분류한다. 생성형 AI를 쓰지 않는다. */
object IntentClassifier {
    private val cancel = listOf("그만", "취소", "멈춰", "꺼 줘", "꺼줘")
    // 부정이 먼저다: "안 걸었어"는 "걸었어"를 포함한다
    private val no = listOf("아니", "아냐", "아뇨", "안 했", "안했", "안 걸", "안걸", "못 했", "못했", "안 돌", "안돌", "평지", "아직")
    // 짧은 대답은 문장 첫 낱말일 때만 인정한다 ("틀어 줘"의 "어"를 잡지 않도록)
    private val shortYes = setOf("응", "네", "예", "어", "넵", "엉")
    private val yes = listOf("맞아", "그래", "했어", "걸었", "돌렸", "좋아")
    private val tire = listOf("타이어", "공기압", "펑크", "바퀴")
    private val slope = listOf("주차", "언덕", "경사", "브레이크")

    fun classify(raw: String): DriverIntent {
        val text = raw.trim().replace(Regex("[.!?,~]"), "") + " "
        if (text.isBlank()) return DriverIntent.UNKNOWN
        return when {
            cancel.any { it in text } -> DriverIntent.CANCEL
            tire.any { it in text } -> DriverIntent.TIRE_ISSUE
            no.any { it in text } -> DriverIntent.NO
            text.split(" ").first() in shortYes || yes.any { it in text } -> DriverIntent.YES
            slope.any { it in text } -> DriverIntent.SLOPE_PARKING
            else -> DriverIntent.UNKNOWN
        }
    }
}

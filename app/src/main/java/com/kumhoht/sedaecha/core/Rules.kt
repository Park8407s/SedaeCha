package com.kumhoht.sedaecha.core

/**
 * 개발 명세 3. 상수. 기본 모드와 데모 모드의 차이는 여기에만 둔다.
 * 판단 로직(EventDetector, ClassEngine)은 이 값만 읽는다.
 */
data class Rules(
    val demo: Boolean,
    /** 신호 대기: 차속 0 이 이만큼 이어지면 */
    val stopQuizAfterMs: Long = 3_000,
    /** 정체: 이 속도 미만이 [congestionMs] 연속 */
    val congestionKmh: Float = 30f,
    val congestionMs: Long = if (demo) 20_000 else 60_000,
    /** 급가속·급제동: 1초 사이 속도 변화 (km/h) */
    val hardAccelKmh: Float = 10f,
    val hardBrakeKmh: Float = 14f,
    /** 급가속·급제동 뒤 아무 코너도 시작하지 않는 시간 */
    val resumeAfterHardMs: Long = 10_000,
    /** 방향지시등이 꺼진 뒤 이어 듣기까지 */
    val resumeAfterTurnMs: Long = 1_000,
    val quizThinkMs: Long = 5_000,
    /** 직전 신호 대기 퀴즈가 끝나고 다음 퀴즈까지 */
    val quizIntervalMs: Long = 60_000,
    /** 같은 말 퀴즈 (한 클래스) */
    val maxQuizPerWord: Int = 2,
    /** 기록이 없을 때 예상 주행 시간 (분) */
    val defaultTripMin: Double = 15.0,
    /** 문장 사이 쉼 */
    val sentenceGapMs: Long = 400,
) {
    /**
     * 데모 모드의 말 배치 간격을 정할 가상 주행 시간 (분).
     * 명세상 데모 모드는 예상 주행 시간을 쓰지 않고 '말 3개 + 덤' 고정이라, Virtual Console 로
     * 약 3분 직접 운전하는 시연에 맞춰 3분 기준으로 고르게 나눈다.
     */
    val demoTripMin: Double get() = 3.0

    /** 말 개수: 10분 미만 2 / 10~30분 3 / 30분 이상 5, 데모 3 */
    fun wordCount(tripMin: Double): Int = when {
        demo -> 3
        tripMin < 10 -> 2
        tripMin < 30 -> 3
        else -> 5
    }

    /** 덤 코너: 10분 이상 편성, 데모는 항상 */
    fun hasBonus(tripMin: Double): Boolean = demo || tripMin >= 10

    companion object {
        val BASIC = Rules(demo = false)
        val DEMO = Rules(demo = true)
        fun of(demo: Boolean) = if (demo) DEMO else BASIC

        /** 말하기 속도: 천천히 / 보통 / 빠르게 */
        val SPEECH_RATES = listOf("천천히" to 0.85f, "보통" to 0.95f, "빠르게" to 1.05f)
        const val DEFAULT_SPEECH_RATE = 0.95f

        /** 복습 퀴즈 최대 문제 수 */
        const val REVIEW_QUIZ_MAX = 5

        /** 예상 주행 시간: 출발 시각 ±이 시간 안에 시작한 최근 운행 */
        const val TRIP_WINDOW_MIN = 60
        const val TRIP_SAMPLES = 5
    }
}

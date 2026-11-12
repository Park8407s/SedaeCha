package com.kumhoht.sedaecha.core

/**
 * Event Detector: 신호 대기·정체·급가속·급제동 판단 (개발 명세 3·5).
 * 차량 신호를 시간 순서대로 받는다. 실차는 약 4Hz, 데모 기록은 1Hz.
 *
 * 정체 판단: 30km/h 미만이 연속으로 [Rules.congestionMs] 이어지면 정체.
 * 단, 신호 대기(차속 0 이 3초 이상)는 정체가 아니라 퀴즈 시간이므로 정체 시간을 처음부터 다시 센다.
 * (가다 서다 하는 짧은 정차는 정체로 이어서 센다)
 */
class EventDetector(var rules: Rules) {
    enum class Hard { ACCEL, BRAKE }

    private val history = ArrayDeque<Pair<Long, Float>>()
    private var stoppedSince: Long? = null
    private var slowSince: Long? = null
    private var lastHardAt: Long? = null

    /** 정차 묶음 번호. 새로 멈출 때마다 1 씩 늘어난다 (신호 대기 한 번에 퀴즈 1문제) */
    var stopId = 0
        private set
    var last: Signal = Signal.PARKED
        private set
    private var lastAt = 0L

    fun reset() {
        history.clear(); stoppedSince = null; slowSince = null; lastHardAt = null
        last = Signal.PARKED; lastAt = 0L
    }

    /** 새 신호. 급가속·급제동이면 그 종류를 돌려준다. */
    fun update(now: Long, s: Signal): Hard? {
        last = s; lastAt = now
        // 정차
        if (s.stopped) {
            if (stoppedSince == null) { stoppedSince = now; stopId++ }
        } else stoppedSince = null
        // 정체
        slowSince = when {
            s.stopped && stoppedFor(now) >= rules.stopQuizAfterMs -> null
            s.speedKmh < rules.congestionKmh -> slowSince ?: now
            else -> null
        }
        // 급가속·급제동: 최근 1초 창 안에서 가장 오래된 값과 비교.
        // (1초보다 오래된 값과 비교하면 창이 1초를 넘어 계단식 차속 두 칸이 한꺼번에 잡힌다: Virtual Console 에서 확인)
        val ref = history.firstOrNull { it.first >= now - 1_000 }?.takeIf { now - it.first >= MIN_WINDOW_MS }
        history.addLast(now to s.speedKmh)
        while (history.size > 2 && history.first().first < now - 2_500) history.removeFirst()
        if (ref == null) return null
        if (lastHardAt?.let { now - it < HARD_COOLDOWN_MS } == true) return null
        val delta = s.speedKmh - ref.second
        val hard = when {
            delta >= rules.hardAccelKmh -> Hard.ACCEL
            delta <= -rules.hardBrakeKmh -> Hard.BRAKE
            else -> null
        }
        if (hard != null) lastHardAt = now
        return hard
    }

    fun stoppedFor(now: Long): Long = stoppedSince?.let { now - it } ?: 0L

    /** 신호 대기 (차속 0 이 3초 이상) */
    fun isWaitingAtSignal(now: Long): Boolean = stoppedFor(now) >= rules.stopQuizAfterMs

    fun isCongested(now: Long): Boolean = slowSince?.let { now - it >= rules.congestionMs } ?: false

    /** 데모 장면 바로 가기: 그 시점까지의 기록을 소리 없이 먼저 넣어 정체 시간 등을 맞춘다 */
    fun prime(samples: List<Pair<Long, Signal>>) { samples.forEach { (t, s) -> update(t, s) } }

    private companion object {
        /** 한 번의 급제동이 여러 번 잡히지 않도록 */
        const val HARD_COOLDOWN_MS = 2_000L
        /** 비교 창이 이보다 짧으면 판단하지 않는다 (신호가 막 들어오기 시작했을 때) */
        const val MIN_WINDOW_MS = 700L
    }
}

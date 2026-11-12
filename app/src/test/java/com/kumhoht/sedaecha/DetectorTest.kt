package com.kumhoht.sedaecha

import com.kumhoht.sedaecha.core.EventDetector
import com.kumhoht.sedaecha.core.Gear
import com.kumhoht.sedaecha.core.Rules
import com.kumhoht.sedaecha.core.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorTest {
    /**
     * Virtual Console 처럼 차속이 약 1.15초마다 8km/h 씩 계단으로 들어오고, 앱은 100ms 마다 읽는다.
     * 보통 가속(약 7km/h/s)이므로 급가속이 아니다.
     */
    @Test
    fun staircaseSpeedIsNotHard() {
        val d = EventDetector(Rules.DEMO)
        val hard = ArrayList<EventDetector.Hard>()
        var v = 0f
        var nextStep = 1_150L
        for (t in 0L..20_000L step 100) {
            if (t >= nextStep && v < 64f) { v += 8f; nextStep += 1_150 }
            d.update(t, Signal(v, Gear.D))?.let { hard += it }
        }
        assertTrue("판정됨: $hard", hard.isEmpty())
    }

    /** 1초 사이 −17km/h (가상 출근길 148초) 는 급제동 */
    @Test
    fun oneSecondDropIsHardBrake() {
        val d = EventDetector(Rules.DEMO)
        var found: EventDetector.Hard? = null
        for (s in 0..10) {
            val v = if (s < 5) 55f else 38f
            d.update(s * 1000L, Signal(v, Gear.D))?.let { found = it }
        }
        assertEquals(EventDetector.Hard.BRAKE, found)
    }

    /** 신호 대기(3초 이상 정차)는 정체 시간을 다시 센다 */
    @Test
    fun signalStopResetsCongestion() {
        val d = EventDetector(Rules.DEMO)
        for (s in 0..15) d.update(s * 1000L, Signal(20f, Gear.D))
        for (s in 16..30) d.update(s * 1000L, Signal(0f, Gear.D))
        for (s in 31..40) d.update(s * 1000L, Signal(10f, Gear.D))
        assertFalse(d.isCongested(40_000))
        for (s in 41..52) d.update(s * 1000L, Signal(10f, Gear.D))
        assertTrue(d.isCongested(52_000))
    }
}

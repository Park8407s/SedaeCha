package com.kumhoht.sedaecha

import com.kumhoht.sedaecha.core.ClassEngine
import com.kumhoht.sedaecha.core.ClassListener
import com.kumhoht.sedaecha.core.ClassPlan
import com.kumhoht.sedaecha.core.ClassPlanner
import com.kumhoht.sedaecha.core.ClassSummary
import com.kumhoht.sedaecha.core.CornerKind
import com.kumhoht.sedaecha.core.DemoDriveLog
import com.kumhoht.sedaecha.core.Direction
import com.kumhoht.sedaecha.core.Gear
import com.kumhoht.sedaecha.core.LearnedInfo
import com.kumhoht.sedaecha.core.PlannedWord
import com.kumhoht.sedaecha.core.PlannerInput
import com.kumhoht.sedaecha.core.Rules
import com.kumhoht.sedaecha.core.Signal
import com.kumhoht.sedaecha.core.Speaker
import com.kumhoht.sedaecha.core.Turn
import com.kumhoht.sedaecha.core.WordMode
import com.kumhoht.sedaecha.core.content.ContentPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 가상 시계로 엔진을 돌린다. 가짜 스피커는 글자당 [MS_PER_CHAR] 로 말을 끝낸다
 * (한국어 TTS 보통 속도 ≈ 초당 7~8자).
 */
class EngineTest {
    private val pack = ContentPack.parse(asset("sedae_radio_content_pack_v0.5.json"))
    private val log = DemoDriveLog.parse(asset("sedae_demo_drive_log_v2.json"))
    private val planner = ClassPlanner(pack)

    private class Spoken(val at: Long, val text: String)

    private inner class Harness(rules: Rules, plan: (Long) -> ClassPlan) : Speaker, ClassListener {
        var now = 0L
        val spoken = ArrayList<Spoken>()
        val stops = ArrayList<Long>()
        var pending: Pair<String, Long>? = null
        var focus = false
        var ended: ClassSummary? = null
        val heard = ArrayList<Pair<String, Boolean>>()
        val quizzes = ArrayList<Pair<Long, String>>()
        val engine = ClassEngine(pack, this, this, rules, plan)

        override fun speak(id: String, text: String) { spoken += Spoken(now, text); pending = id to now + text.length * MS_PER_CHAR }
        override fun stop() { pending = null; stops += now }
        override fun requestFocus(): Boolean { focus = true; return true }
        override fun abandonFocus() { focus = false }
        override fun onWordHeard(word: PlannedWord, replay: Boolean) { heard += word.item.word to replay }
        override fun onQuizAsked(word: PlannedWord) { quizzes += now to word.item.word }
        override fun onClassEnded(summary: ClassSummary) { ended = summary }

        /** [to] 까지 100ms 단위로 진행. 매 1초마다 signal(sec) 를 넣는다 */
        fun runUntil(to: Long, signal: (Int) -> Signal) {
            while (now < to) {
                now += 100
                pending?.let { (id, done) -> if (now >= done) { pending = null; engine.onSpeechDone(id, now) } }
                if (now % 1000 == 0L) engine.onSignal(now, signal((now / 1000).toInt())) else engine.tick(now)
            }
        }

        fun said(part: String) = spoken.any { part in it.text }
        fun firstAt(part: String) = spoken.firstOrNull { part in it.text }?.at
    }

    private fun demoHarness() = Harness(Rules.DEMO) { planner.demoPlan(log.wordTimes, log.cardId, log.route) }

    @Test
    fun contentPackParses() {
        assertEquals(28, pack.items.size)
        assertEquals(17, pack.items.count { it.thenMode != null })
        assertEquals(3, pack.cultureCards.size)
        assertEquals("출근길", pack.routeLabel(8, 0))
        assertEquals("퇴근길", pack.routeLabel(18, 30))
        assertEquals("운전길", pack.routeLabel(13, 0))
        assertEquals(190, log.speed.size)
        assertEquals(listOf("킹받다" to 8, "중꺾마" to 60, "럭키비키" to 140), log.wordTimes)
    }

    /** 가상 출근길 3분 10초: 기록의 장면이 의도한 대로 한 번씩 판정되는지 */
    @Test
    fun demoDriveFollowsTimeline() {
        val h = demoHarness()
        h.engine.onSignal(0, Signal.PARKED) // 주차 상태에서 시작
        h.runUntil(200_000) { sec -> if (sec < log.speed.size) log.signalAt(sec - 1) else Signal.PARKED }
        val e = h.engine

        // 인트로 → 말 1
        assertTrue(h.said("오늘 출근길엔 요즘말 3개를"))
        assertTrue(h.said("오늘의 말은 ‘킹받다’"))
        // 첫 신호 대기 퀴즈는 킹받다, 두 번째는 다시 듣기 표시된 럭키비키
        assertEquals(listOf("킹받다", "럭키비키"), h.quizzes.map { it.second })
        val q1 = h.quizzes[0].first / 1000
        assertTrue("첫 퀴즈 ${q1}초", q1 in 40..45)
        val q2 = h.quizzes[1].first / 1000
        assertTrue("두 번째 퀴즈 ${q2}초", q2 in 172..176)
        // 덤 코너는 정체 20초 지나 한 번
        val bonusAt = h.firstAt("요즘 문화 한 스푼")!! / 1000
        assertTrue("덤 ${bonusAt}초", bonusAt in 119..122)
        assertEquals(1, h.spoken.count { it.text.startsWith("길이 막히네요") })
        // 급제동 → 10초 뒤 '이어서 들을게요'
        val resume = h.firstAt("이어서 들을게요")!! / 1000
        assertTrue("이어 듣기 ${resume}초", resume in 158..160)
        // 세 말 모두 배운 말, 럭키비키만 다시 듣기
        assertEquals(listOf("킹받다" to false, "중꺾마" to false, "럭키비키" to true), h.heard)
        // 도착: 다시 듣기가 있어 '놓친 말은…' 마무리
        assertTrue(h.said("놓친 말은 주차하고 다시 들어 봐요"))
        val s = h.ended!!
        assertTrue(s.showArrival)
        assertEquals(setOf(pack.wordByName("럭키비키")!!.id), s.replay)
        assertEquals(1, s.turnStops)
        assertEquals(1, s.hardStops)
        assertEquals(2, s.quizzes)
        assertEquals(ClassEngine.State.IDLE, e.state)
        assertFalse(h.focus)
    }

    /** 방향지시등: 주행 중 켜면 멈추고, 꺼지고 1초 뒤 멈춘 문장 처음부터 */
    @Test
    fun turnSignalPausesAndResumesSentence() {
        val h = demoHarness()
        h.engine.onSignal(0, Signal.PARKED)
        h.runUntil(74_000) { log.signalAt(it - 1) }
        val before = h.spoken.last()
        h.runUntil(76_000) { log.signalAt(it - 1) } // 75 초에 깜빡이
        assertEquals("깜빡이 켜지면 멈춤", "방향지시등", h.engine.status().hold)
        h.runUntil(82_500) { log.signalAt(it - 1) }
        val resumed = h.spoken.first { it.at > 76_000 }
        assertTrue("꺼진 뒤 1초 이상 지나 재개: ${resumed.at}", resumed.at >= 81_000)
        assertTrue("멈춘 문장(또는 다음 문장)부터", resumed.text == before.text || h.stops.isNotEmpty())
    }

    /** 신호 대기 퀴즈는 60초 안에 다시 정차해도 나오지 않음 */
    @Test
    fun quizIntervalSixtySeconds() {
        val plan = { _: Long -> planner.plan(PlannerInput(), Direction.NOW, Rules.BASIC, 0L) }
        val h = Harness(Rules.BASIC, plan)
        h.engine.onSignal(0, Signal.PARKED)
        // 출발 → 계속 50km/h 로 말 1 을 듣고, 120초에 정차 10초, 140초 다시 정차 10초
        val sig = { s: Int ->
            when (s) {
                in 0..1 -> Signal.PARKED
                in 120..129, in 140..149 -> Signal(0f, Gear.D)
                else -> Signal(50f, Gear.D)
            }
        }
        h.runUntil(160_000, smooth(sig))
        assertEquals(1, h.quizzes.size)
        h.runUntil(260_000, smooth { s -> if (s in 240..249) Signal(0f, Gear.D) else sig(s) })
        assertEquals("60초 지나 새 정차엔 퀴즈", 2, h.quizzes.size)
    }

    /** 정차 중 켠 방향지시등은 무시 (좌회전 대기 중에도 퀴즈) */
    @Test
    fun turnSignalWhileStoppedIgnored() {
        val plan = { _: Long -> planner.plan(PlannerInput(), Direction.NOW, Rules.BASIC, 0L) }
        val h = Harness(Rules.BASIC, plan)
        h.engine.onSignal(0, Signal.PARKED)
        h.runUntil(100_000, smooth { s -> if (s < 2) Signal.PARKED else if (s in 60..80) Signal(0f, Gear.D, if (s >= 68) Turn.LEFT else Turn.OFF) else Signal(50f, Gear.D) })
        assertEquals(1, h.quizzes.size)
    }

    /** 기어 R: 멈춤, D 로 돌아오면 이어 듣기 */
    @Test
    fun gearReversePauses() {
        val plan = { _: Long -> planner.plan(PlannerInput(), Direction.NOW, Rules.BASIC, 0L) }
        val h = Harness(Rules.BASIC, plan)
        h.engine.onSignal(0, Signal.PARKED)
        h.runUntil(12_000, smooth { s -> if (s < 2) Signal.PARKED else Signal(8f, Gear.D) })
        h.runUntil(20_000) { Signal(0f, Gear.R) }
        assertEquals(ClassEngine.State.PAUSED, h.engine.state)
        val n = h.spoken.size
        h.runUntil(25_000) { Signal(0f, Gear.R) }
        assertEquals(n, h.spoken.size)
        h.runUntil(40_000) { Signal(5f, Gear.D) }
        assertEquals(ClassEngine.State.DRIVING, h.engine.state)
        assertTrue(h.spoken.size > n)
    }

    /** 들은 말 0개로 도착하면 도착 화면 없이 끝나고 편성한 말은 남은 말로 */
    @Test
    fun shortDriveNoArrival() {
        val plan = { _: Long -> planner.plan(PlannerInput(), Direction.NOW, Rules.BASIC, 0L) }
        val h = Harness(Rules.BASIC, plan)
        h.engine.onSignal(0, Signal.PARKED)
        h.runUntil(10_000) { s -> if (s < 2 || s > 8) Signal.PARKED else Signal(20f, Gear.D) }
        val s = h.ended
        assertNotNull(s)
        assertFalse(s!!.showArrival)
        assertEquals(s.plan.words.size, s.unheard.size)
        assertFalse(h.said("도착했어요"))
    }

    /** 편성: 남은 말 → 안 배운 쉬운 말(격차 큰 말 먼저) → 다시 듣기, 인트로에 남은 말 멘트 */
    @Test
    fun plannerOrderAndCarryOver() {
        val learnedAll = pack.items.filter { it.levelRank == 0 }.associate { it.id to LearnedInfo(it.id, 1L, replay = false) }
        val p = planner.plan(PlannerInput(learned = learnedAll, carryOver = listOf("W015")), Direction.NOW, Rules.BASIC, 0L)
        assertEquals("W015", p.words.first().item.id)
        assertEquals(1, p.carryOverCount)
        assertTrue(p.words.drop(1).all { it.item.levelRank == 1 })
        // 섞어서 듣기: 두 번째 말은 그때말 (짝이 있으면)
        val mix = planner.plan(PlannerInput(), Direction.MIX, Rules.DEMO, 0L)
        assertEquals(3, mix.words.size)
        assertEquals(WordMode.NOW, mix.words[0].mode)
        assertEquals(if (mix.words[1].item.thenMode != null) WordMode.THEN else WordMode.NOW, mix.words[1].mode)
        assertNotNull(mix.bonus)
        // 그때말 배우기는 짝이 있는 말만
        val then = planner.plan(PlannerInput(), Direction.THEN, Rules.BASIC, 0L)
        assertTrue(then.words.all { it.item.thenMode != null && it.mode == WordMode.THEN })
        // 기록이 없으면 15분 → 말 3개 + 덤
        assertEquals(3, p.words.size)
        assertNotNull(p.bonus)
        assertNull(planner.expectedTripMin(emptyList(), 0L))
    }

    /** 데모 장면 바로 가기 (급제동 140초): 이전 말은 들은 것으로, 정체 등 시간은 기록으로 맞춤 */
    @Test
    fun demoSeekToHardBrake() {
        val h = demoHarness()
        h.now = 140_000
        val plan = planner.demoPlan(log.wordTimes, log.cardId, log.route)
        h.engine.detector.prime((0 until 140).map { it * 1000L to log.signalAt(it) })
        h.engine.restoreDemoClass(h.now, plan, startedAt = 0L, heardCount = 2, quizzes = 1, bonusDone = true, replay = emptySet(), lastQuizEnd = 53_000L)
        assertEquals(CornerKind.WORD, h.engine.status().corner)
        assertEquals("럭키비키", h.engine.status().word)
        h.runUntil(200_000) { sec -> if (sec < log.speed.size) log.signalAt(sec) else Signal.PARKED }
        assertEquals(listOf("럭키비키" to true), h.heard)
        assertEquals("럭키비키", h.quizzes.single().second)
        assertTrue(h.ended!!.showArrival)
    }

    /** 목표 속도로 1초에 최대 8km/h 씩 바뀌게 (급가속·급제동이 잡히지 않는 보통 운전) */
    private fun smooth(target: (Int) -> Signal): (Int) -> Signal {
        var v = 0f
        return { s ->
            val t = target(s)
            v = if (t.speedKmh > v) minOf(t.speedKmh, v + 8f) else maxOf(t.speedKmh, v - 8f)
            t.copy(speedKmh = v)
        }
    }

    /** 복습 퀴즈: 저장된 다시 듣기 표시(이전에 틀린 말)도 먼저 낸다 */
    @Test
    fun reviewPutsStoredReplayFirst() {
        val plan = planner.demoPlan(log.wordTimes, log.cardId, log.route)
        val s = com.kumhoht.sedaecha.core.ClassSummary(plan, 0, 1, plan.words, emptySet(), emptyList(), 0, 0, 0, arrived = true)
        val joong = pack.wordByName("중꺾마")!!.id
        val q = com.kumhoht.sedaecha.core.Review.build(s, setOf(joong))
        assertEquals("중꺾마", q.first().word.item.word)
        assertEquals("다시 듣기로 표시된 말이에요", q.first().note)
        assertEquals(q.first().options[q.first().correctIndex].substring(2), pack.wordByName("중꺾마")!!.quiz.answer)
    }

    private fun asset(name: String) = File("src/main/assets/$name").readText()

    private companion object { const val MS_PER_CHAR = 130L }
}

package com.kumhoht.sedaecha.core

import com.kumhoht.sedaecha.core.content.ContentPack
import com.kumhoht.sedaecha.core.content.CultureCard
import com.kumhoht.sedaecha.core.content.WordItem
import java.util.Calendar
import kotlin.math.abs

/** 배운 말 하나의 저장 상태 (편성·퀴즈 출제용) */
data class LearnedInfo(val wordId: String, val firstLearnedAt: Long, val replay: Boolean, val lastHeardAt: Long = firstLearnedAt)

/** 지난 운행 (예상 주행 시간 계산용) */
data class TripStat(val startedAt: Long, val durationMs: Long)

/** 편성에 필요한 저장 데이터 스냅숏 */
data class PlannerInput(
    val learned: Map<String, LearnedInfo> = emptyMap(),
    /** 지난번 못 들은 말 (순서대로) */
    val carryOver: List<String> = emptyList(),
    val distanceKm: Double = 0.0,
    val recentTrips: List<TripStat> = emptyList(),
    /** 마지막으로 들려준 문화 카드 번호 (돌아가며 고른다) */
    val lastCardIndex: Int = -1,
)

/** 편성된 말 하나 */
data class PlannedWord(val item: WordItem, val mode: WordMode, val dueMs: Long)

/** 예정 시각의 기준 */
enum class TimeBase {
    /** 인트로가 끝난 시각부터 (기본·데모 모드) */
    INTRO_END,
    /** 클래스 시작 시각부터 (심사용 가상 주행: 기록의 장면 시각에 맞춤) */
    CLASS_START,
}

/** 오늘의 클래스 편성 결과 */
data class ClassPlan(
    val direction: Direction,
    val route: String,
    val tripMin: Double,
    /** 최근 기록으로 계산했는지 (false = 기록 없음, 기본값) */
    val tripFromHistory: Boolean,
    val words: List<PlannedWord>,
    val bonus: CultureCard?,
    val bonusIndex: Int,
    /** 지난번 못 들은 말로 시작하는 개수 */
    val carryOverCount: Int,
    /** 이번 클래스에서 들은 말이 없을 때 신호 대기 퀴즈에 쓸 지난 말 */
    val fallbackQuiz: List<PlannedWord>,
    val timeBase: TimeBase = TimeBase.INTRO_END,
)

/**
 * Class Scheduler 의 편성 부분 (개발 명세 4).
 * - 말 개수: 예상 주행 시간 T 기준 (Rules.wordCount)
 * - 고르는 순서: ① 남은 말 ② 안 배운 말 (쉬움→보통→어려움, 같은 난이도면 세대 격차 큰 말 먼저) ③ 다시 듣기 → 오래전에 배운 말
 * - 배치: 첫 말은 인트로 바로 뒤, 말 i 는 인트로 끝 + (i−1)×(T÷N)
 */
class ClassPlanner(private val pack: ContentPack) {

    fun plan(input: PlannerInput, direction: Direction, rules: Rules, wallNow: Long): ClassPlan {
        val cal = Calendar.getInstance().apply { timeInMillis = wallNow }
        val route = pack.routeLabel(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        val history = expectedTripMin(input.recentTrips, wallNow)
        val tripMin = when {
            rules.demo -> rules.demoTripMin
            history != null -> history
            else -> rules.defaultTripMin
        }
        val n = rules.wordCount(tripMin)
        val open = ThemePack.openThemes(input.distanceKm, rules.demo)

        val chosen = choose(input, direction, open, n)
        val gap = (tripMin * 60_000 / n).toLong()
        val words = chosen.mapIndexed { i, (item, mode) -> PlannedWord(item, mode, i * gap) }
        val carry = chosen.takeWhile { (item, _) -> item.id in input.carryOver }.size

        val bonusIndex = if (pack.cultureCards.isEmpty()) -1 else (input.lastCardIndex + 1).mod(pack.cultureCards.size)
        val bonus = if (rules.hasBonus(tripMin) && bonusIndex >= 0) pack.cultureCards[bonusIndex] else null

        return ClassPlan(
            direction = direction, route = route, tripMin = tripMin, tripFromHistory = !rules.demo && history != null,
            words = words, bonus = bonus, bonusIndex = bonusIndex, carryOverCount = carry,
            fallbackQuiz = fallbackQuiz(input, direction),
        )
    }

    /** 고르는 순서대로 n 개. 섞어서 듣기는 요즘말·그때말을 번갈아 (그때말 차례에 짝이 없으면 요즘말) */
    private fun choose(input: PlannerInput, direction: Direction, open: Set<String>, n: Int): List<Pair<WordItem, WordMode>> {
        val usable = pack.items.filter { it.theme in open && (direction != Direction.THEN || it.thenMode != null) }
        val usableIds = usable.associateBy { it.id }
        val order = ArrayList<WordItem>()
        fun add(w: WordItem) { if (order.none { it.id == w.id }) order += w }

        input.carryOver.mapNotNull { usableIds[it] }.forEach(::add)
        usable.filter { it.id !in input.learned }
            .sortedWith(compareBy<WordItem>({ it.levelRank }, { -(it.gapPoints ?: -1.0) }, { if (it.gapNote.isBlank()) 1 else 0 }, { it.id }))
            .forEach(::add)
        usable.filter { input.learned[it.id]?.replay == true }
            .sortedBy { input.learned[it.id]?.firstLearnedAt ?: 0L }.forEach(::add)
        usable.filter { it.id in input.learned }
            .sortedBy { input.learned[it.id]?.lastHeardAt ?: 0L }.forEach(::add)

        return order.take(n).mapIndexed { i, item ->
            val mode = when (direction) {
                Direction.NOW -> WordMode.NOW
                Direction.THEN -> WordMode.THEN
                Direction.MIX -> if (i % 2 == 1) WordMode.THEN else WordMode.NOW
            }
            item to item.modeOrNow(mode)
        }
    }

    /** 이번 클래스에서 들은 말이 없을 때: 지난 클래스에서 배운 말 (다시 듣기 → 최근에 들은 말) */
    private fun fallbackQuiz(input: PlannerInput, direction: Direction): List<PlannedWord> {
        val mode = if (direction == Direction.THEN) WordMode.THEN else WordMode.NOW
        return input.learned.values
            .sortedWith(compareBy<LearnedInfo>({ if (it.replay) 0 else 1 }, { -it.lastHeardAt }))
            .mapNotNull { pack.word(it.wordId) }
            .take(5)
            .map { PlannedWord(it, it.modeOrNow(mode), 0) }
    }

    /** 출발 시각 ±1시간 안에 시작한 최근 운행 5번의 평균 (분). 없으면 null */
    fun expectedTripMin(trips: List<TripStat>, wallNow: Long): Double? {
        val nowMin = minuteOfDay(wallNow)
        val near = trips.sortedByDescending { it.startedAt }
            .filter { circularDiff(minuteOfDay(it.startedAt), nowMin) <= Rules.TRIP_WINDOW_MIN }
            .take(Rules.TRIP_SAMPLES)
        if (near.isEmpty()) return null
        return near.map { it.durationMs / 60_000.0 }.average()
    }

    /**
     * 심사용 가상 주행 편성 (개발 명세 9): 요즘말 모드, 킹받다 → 중꺾마 → 럭키비키 + 덤 C001, 출근길.
     * 말 코너의 예정 시각은 가상 출근길 기록의 장면 시각(클래스 시작 기준)을 쓴다.
     */
    fun demoPlan(wordTimesSec: List<Pair<String, Int>>, cardId: String, route: String): ClassPlan {
        val words = wordTimesSec.mapNotNull { (word, t) -> pack.wordByName(word)?.let { PlannedWord(it, WordMode.NOW, t * 1000L) } }
        return ClassPlan(
            direction = Direction.NOW, route = route, tripMin = 17.0, tripFromHistory = false, words = words,
            bonus = pack.card(cardId), bonusIndex = -1, carryOverCount = 0, fallbackQuiz = emptyList(),
            timeBase = TimeBase.CLASS_START,
        )
    }

    private fun minuteOfDay(ms: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    private fun circularDiff(a: Int, b: Int): Int { val d = abs(a - b); return minOf(d, 24 * 60 - d) }
}

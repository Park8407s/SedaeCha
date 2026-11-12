package com.kumhoht.sedaecha.core

import kotlin.random.Random

/** 복습 퀴즈 한 문제 */
data class ReviewQuestion(
    val word: PlannedWord,
    /** 문제 위 작은 안내 ("오늘 급제동 때 듣던 말이에요") */
    val note: String,
    val prompt: String,
    val options: List<String>,
    val correctIndex: Int,
    /** 답을 고른 뒤 설명 */
    val explain: String,
    val replay: Boolean,
)

/** 복습 퀴즈 (개발 명세 6): 이번 클래스에서 들은 말 전부(최대 5문제), 다시 듣기 표시된 말 먼저, 정답 1 + 오답 3 */
object Review {
    private val NUMS = listOf("①", "②", "③", "④")

    /** @param storedReplay 지금 저장된 다시 듣기 표시 (복습 퀴즈에서 틀린 말 등). 이번 운행의 표시와 함께 먼저 낸다 */
    fun build(summary: ClassSummary, storedReplay: Set<String> = emptySet(), random: Random = Random.Default): List<ReviewQuestion> {
        val replayIds = summary.replay + storedReplay
        val words = summary.heard.sortedBy { if (it.item.id in replayIds) 0 else 1 }.take(Rules.REVIEW_QUIZ_MAX)
        return words.map { w ->
            val q = w.item.quiz(w.mode)
            val options = (q.wrong.take(3) + q.answer).shuffled(random)
            val correct = options.indexOf(q.answer)
            val replay = w.item.id in replayIds
            val note = when {
                w.item.id in summary.replay -> "오늘 급가속·급제동 때 듣던 말이에요"
                replay -> "다시 듣기로 표시된 말이에요"
                else -> "오늘 ${summary.plan.route}에 배운 말이에요"
            }
            if (w.mode == WordMode.THEN) {
                ReviewQuestion(
                    w, note, q.question, options.mapIndexed { i, o -> "${NUMS[i]} $o" }, correct,
                    "정답은 ${NUMS[correct]} ‘${q.answer}’. ‘${w.item.pair}’를 요즘은 이렇게 말해요.", replay,
                )
            } else {
                val pairLine = if (w.item.hasPair) " 그때는 ‘${w.item.pair}’라고 했죠." else ""
                ReviewQuestion(
                    w, note, "‘${w.item.word}’${topic(w.item.word)} 어떤 뜻일까요?", options.mapIndexed { i, o -> "${NUMS[i]} $o" }, correct,
                    "정답은 ${NUMS[correct]} ${q.answer}.$pairLine", replay,
                )
            }
        }
    }

    /** 받침이 있으면 '은', 없으면 '는' */
    fun topic(word: String): String {
        val c = word.trimEnd().lastOrNull() ?: return "는"
        if (c !in '가'..'힣') return "는"
        return if ((c - '가') % 28 != 0) "은" else "는"
    }

    /** 써먹기 미션: 다시 듣기 말 → 마지막에 들은 말 */
    fun mission(summary: ClassSummary): String? {
        val w = summary.heard.lastOrNull { it.item.id in summary.replay } ?: summary.heard.lastOrNull() ?: return null
        val word = if (w.mode == WordMode.THEN) w.item.word else w.item.word
        return "저녁 식탁에서 가족에게 ‘$word’ 한 번 써 보기"
    }
}

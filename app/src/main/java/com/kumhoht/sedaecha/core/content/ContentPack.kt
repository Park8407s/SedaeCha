package com.kumhoht.sedaecha.core.content

import com.kumhoht.sedaecha.core.WordMode
import org.json.JSONArray
import org.json.JSONObject

/** 퀴즈 (정답 1 + 오답 3, 음성용 질문·정답 문장) */
data class Quiz(
    val question: String,
    val answer: String,
    val wrong: List<String>,
    val voiceQuestion: String,
    val voiceAnswer: String,
)

/** 그때말 배우기 모드용 (짝이 있는 말만) */
data class ThenMode(val sentences: List<String>, val quiz: Quiz)

/** 요즘말 하나 (콘텐츠 팩 items) */
data class WordItem(
    val id: String,
    val word: String,
    val meaning: String,
    val origin: String,
    val example: String,
    val pair: String,
    val pairRelation: String,
    val pairEra: String,
    val pairMeaning: String,
    val theme: String,
    val level: String,
    val gapNote: String,
    val quiz: Quiz,
    val sentences: List<String>,
    val thenMode: ThenMode?,
) {
    val hasPair: Boolean get() = pair.isNotBlank()

    /** 난이도 순서: 쉬움 0 → 보통 1 → 어려움 2 */
    val levelRank: Int get() = when (level) { "쉬움" -> 0; "보통" -> 1; "어려움" -> 2; else -> 3 }

    /** 세대 격차 (%p). gap_note 에 '…%p' 가 있으면 그 값, 없으면 null */
    val gapPoints: Double? get() = GAP.find(gapNote)?.groupValues?.get(1)?.toDoubleOrNull()

    fun sentences(mode: WordMode): List<String> = if (mode == WordMode.THEN) thenMode?.sentences ?: sentences else sentences
    fun quiz(mode: WordMode): Quiz = if (mode == WordMode.THEN) thenMode?.quiz ?: quiz else quiz

    /** 그때말 모드를 쓸 수 없으면 요즘말로 */
    fun modeOrNow(mode: WordMode): WordMode = if (mode == WordMode.THEN && thenMode == null) WordMode.NOW else mode

    private companion object { val GAP = Regex("""(\d+(?:\.\d+)?)%p""") }
}

/** 요즘 문화 카드 (덤 코너) */
data class CultureCard(val id: String, val title: String, val relatedWord: String, val sentences: List<String>)

/** 공통 안내 멘트 */
data class Templates(
    val intro: List<String>,
    val introCarryOver: List<String>,
    val quizLead: List<String>,
    val quizThinkSec: Int,
    val resumeAfterHard: List<String>,
    val bonusLead: List<String>,
    val outroAllDone: List<String>,
    val outroReplay: List<String>,
    val outroCarryOver: List<String>,
    /** "05:00-10:59" → "출근길", "else" → "운전길" */
    val routeLabel: Map<String, String>,
    /** now/then/mix → 요즘말/그때말/말 */
    val modeLabel: Map<String, String>,
)

data class TtsConfig(val locale: String, val speechRate: Float, val pitch: Float, val sentenceGapMs: Long)

/** 콘텐츠 팩 v0.5 (assets/sedae_radio_content_pack_v0.5.json) */
data class ContentPack(
    val version: String,
    val items: List<WordItem>,
    val cultureCards: List<CultureCard>,
    val templates: Templates,
    val tts: TtsConfig,
) {
    private val byId = items.associateBy { it.id }
    fun word(id: String): WordItem? = byId[id]
    fun wordByName(word: String): WordItem? = items.firstOrNull { it.word == word }
    fun card(id: String): CultureCard? = cultureCards.firstOrNull { it.id == id }

    /** 출발 시각(시·분) → 출근길 / 퇴근길 / 운전길 */
    fun routeLabel(hour: Int, minute: Int): String {
        val m = hour * 60 + minute
        for ((range, label) in templates.routeLabel) {
            if (range == "else") continue
            val parts = range.split("-")
            if (parts.size != 2) continue
            val from = toMinutes(parts[0]) ?: continue
            val to = toMinutes(parts[1]) ?: continue
            if (m in from..to) return label
        }
        return templates.routeLabel["else"] ?: "운전길"
    }

    private fun toMinutes(hhmm: String): Int? {
        val p = hhmm.trim().split(":")
        return if (p.size == 2) (p[0].toIntOrNull() ?: return null) * 60 + (p[1].toIntOrNull() ?: return null) else null
    }

    companion object {
        /** 수록(status = 수록)된 말만 읽는다. 제외 목록은 앱에서 쓰지 않는다. */
        fun parse(json: String): ContentPack {
            val root = JSONObject(json)
            val items = root.getJSONArray("items").objects()
                .filter { it.optString("status", "수록") == "수록" }
                .map(::parseItem)
            val cards = root.optJSONArray("culture_cards")?.objects().orEmpty().map {
                CultureCard(it.getString("id"), it.optString("title"), it.optString("related_word"), it.getJSONArray("voice_sentences").strings())
            }
            val t = root.getJSONObject("templates")
            val templates = Templates(
                intro = t.getJSONArray("intro").strings(),
                introCarryOver = t.optJSONArray("intro_carry_over")?.strings().orEmpty(),
                quizLead = t.optJSONArray("quiz_lead")?.strings().orEmpty(),
                quizThinkSec = t.optInt("quiz_think_sec", 5),
                resumeAfterHard = t.optJSONArray("resume_after_hard_event")?.strings().orEmpty(),
                bonusLead = t.optJSONArray("bonus_lead")?.strings().orEmpty(),
                outroAllDone = t.optJSONArray("outro_all_done")?.strings().orEmpty(),
                outroReplay = t.optJSONArray("outro_replay")?.strings().orEmpty(),
                outroCarryOver = t.optJSONArray("outro_carry_over")?.strings().orEmpty(),
                routeLabel = t.optJSONObject("route_label")?.stringMap().orEmpty(),
                modeLabel = t.optJSONObject("mode_label")?.stringMap().orEmpty(),
            )
            val tts = root.optJSONObject("tts")
            return ContentPack(
                version = root.optString("version"),
                items = items,
                cultureCards = cards,
                templates = templates,
                tts = TtsConfig(
                    locale = tts?.optString("locale", "ko-KR") ?: "ko-KR",
                    speechRate = tts?.optDouble("speech_rate", 0.95)?.toFloat() ?: 0.95f,
                    pitch = tts?.optDouble("pitch", 1.0)?.toFloat() ?: 1f,
                    sentenceGapMs = tts?.optLong("sentence_gap_ms", 400) ?: 400,
                ),
            )
        }

        private fun parseItem(o: JSONObject): WordItem {
            val then = o.optJSONObject("then_mode")?.let { ThenMode(it.getJSONArray("voice_sentences").strings(), parseQuiz(it.getJSONObject("quiz"))) }
            return WordItem(
                id = o.getString("id"),
                word = o.getString("word"),
                meaning = o.optString("meaning"),
                origin = o.optString("origin"),
                example = o.optString("example"),
                pair = o.optString("pair"),
                pairRelation = o.optString("pair_relation"),
                pairEra = o.optString("pair_era"),
                pairMeaning = o.optString("pair_meaning"),
                theme = o.optString("theme"),
                level = o.optString("level"),
                gapNote = o.optString("gap_note"),
                quiz = parseQuiz(o.getJSONObject("quiz")),
                sentences = o.getJSONArray("voice_sentences").strings(),
                thenMode = then,
            )
        }

        private fun parseQuiz(q: JSONObject): Quiz {
            val voice = q.optJSONObject("voice")
            return Quiz(
                question = q.optString("question"),
                answer = q.optString("answer"),
                wrong = q.optJSONArray("wrong")?.strings().orEmpty(),
                voiceQuestion = voice?.optString("question") ?: q.optString("question"),
                voiceAnswer = voice?.optString("answer") ?: "정답은, ${q.optString("answer")}.",
            )
        }

        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
        private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
        private fun JSONObject.stringMap(): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            val it = keys()
            while (it.hasNext()) { val k = it.next(); out[k] = getString(k) }
            return out
        }
    }
}

package com.kumhoht.sedaecha.data

import android.content.SharedPreferences
import android.util.Log
import com.kumhoht.sedaecha.core.ClassPlan
import com.kumhoht.sedaecha.core.ClassSummary
import com.kumhoht.sedaecha.core.LearnedInfo
import com.kumhoht.sedaecha.core.PlannedWord
import com.kumhoht.sedaecha.core.PlannerInput
import com.kumhoht.sedaecha.core.TripStat
import com.kumhoht.sedaecha.core.WordMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Progress Repository: 배운 말·다시 듣기·남은 말·레벨·누적 거리를 차량 로컬 DB 에 저장 (개발 명세 8).
 * 서버로 보내지 않는다. 데모 결과는 여기로 오지 않는다 (DemoController 가 저장하지 않음).
 *
 * 편성은 출발 순간 동기로 해야 해서 [snapshot] 에 마지막 값을 들고 있다가 쓸 때마다 새로 읽는다.
 */
class ProgressRepository(db: SedaeDatabase, private val prefs: SharedPreferences) {
    val dao = db.progress()

    @Volatile var snapshot = PlannerInput()
        private set

    suspend fun refresh() {
        snapshot = PlannerInput(
            learned = dao.learned().associate { it.wordId to LearnedInfo(it.wordId, it.firstLearnedAt, it.replay, it.lastHeardAt) },
            carryOver = dao.carryOver().map { it.wordId },
            distanceKm = dao.distance()?.km ?: 0.0,
            recentTrips = dao.drives().map { TripStat(it.startedAt, it.durationMs) },
            lastCardIndex = prefs.getInt(KEY_LAST_CARD, -1),
        )
    }

    // ------------------------------------------------------------------ 클래스

    /** 출발: 남은 말은 이번 편성에 들어갔으므로 비우고, 진행 중 클래스를 기록해 둔다 (서비스가 죽으면 복구) */
    suspend fun onClassStarted(plan: ClassPlan) {
        dao.clearCarryOver()
        val o = JSONObject().put("words", JSONArray(plan.words.map { "${it.item.id}:${it.mode}" })).put("heard", JSONArray())
        prefs.edit().putString(KEY_ACTIVE, o.toString()).apply()
        refresh()
    }

    suspend fun onWordHeard(w: PlannedWord, replay: Boolean, route: String, wallNow: Long) {
        val old = dao.learned(w.item.id)
        dao.upsertLearned(
            LearnedWordEntity(
                wordId = w.item.id,
                firstLearnedAt = old?.firstLearnedAt ?: wallNow,
                lastHeardAt = wallNow,
                lastRoute = route,
                heardNow = (old?.heardNow ?: false) || w.mode == WordMode.NOW,
                heardThen = (old?.heardThen ?: false) || w.mode == WordMode.THEN,
                replay = (old?.replay ?: false) || replay,
                quizCorrect = old?.quizCorrect ?: 0,
                quizWrong = old?.quizWrong ?: 0,
            )
        )
        prefs.getString(KEY_ACTIVE, null)?.let { s ->
            runCatching { JSONObject(s).apply { getJSONArray("heard").put(w.item.id) } }.getOrNull()
                ?.let { prefs.edit().putString(KEY_ACTIVE, it.toString()).apply() }
        }
        refresh()
    }

    suspend fun onClassEnded(s: ClassSummary, wallStart: Long, wallEnd: Long, distanceKm: Double) {
        dao.clearCarryOver()
        dao.insertCarryOver(s.unheard.mapIndexed { i, w -> CarryOverEntity(i, w.item.id, w.mode.name) })
        dao.insertClass(
            ClassLogEntity(
                startedAt = wallStart, endedAt = wallEnd, route = s.plan.route, direction = s.plan.direction.key,
                planned = s.plan.words.joinToString(",") { "${it.item.id}:${it.mode}" },
                heard = s.heard.joinToString(",") { "${it.item.id}:${it.mode}" },
                replay = s.replay.joinToString(","), quizzes = s.quizzes, turnStops = s.turnStops, hardStops = s.hardStops,
                distanceKm = distanceKm, arrived = s.arrived,
            )
        )
        if (s.arrived) dao.insertDrive(DriveStatEntity(startedAt = wallStart, durationMs = (wallEnd - wallStart).coerceAtLeast(0)))
        val e = prefs.edit().remove(KEY_ACTIVE)
        if (s.bonusPlayed && s.plan.bonusIndex >= 0) e.putInt(KEY_LAST_CARD, s.plan.bonusIndex)
        e.apply()
        refresh()
    }

    /** 서비스가 중단됐다가 다시 살아남: 진행 중이던 클래스는 버리고 못 들은 말은 다음 운행으로 */
    suspend fun recoverInterruptedClass() {
        val s = prefs.getString(KEY_ACTIVE, null) ?: run { refresh(); return }
        runCatching {
            val o = JSONObject(s)
            val heard = o.getJSONArray("heard").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            val words = o.getJSONArray("words").let { a -> (0 until a.length()).map { a.getString(it) } }
            val left = words.map { it.substringBefore(":") to it.substringAfter(":") }.filter { it.first !in heard }
            val existing = dao.carryOver()
            val merged = (left.map { it.first to it.second } + existing.map { it.wordId to it.mode }).distinctBy { it.first }
            dao.clearCarryOver()
            dao.insertCarryOver(merged.mapIndexed { i, (id, mode) -> CarryOverEntity(i, id, mode) })
            Log.i(TAG, "중단된 클래스 복구: 남은 말 ${left.size}개")
        }
        prefs.edit().remove(KEY_ACTIVE).apply()
        refresh()
    }

    // ------------------------------------------------------------------ 주차 중

    /** 복습 퀴즈: 맞히면 다시 듣기 해제, 틀리면 다시 듣기 표시 */
    suspend fun onReviewAnswer(wordId: String, correct: Boolean) {
        val old = dao.learned(wordId) ?: return
        dao.upsertLearned(
            old.copy(
                replay = !correct,
                quizCorrect = old.quizCorrect + if (correct) 1 else 0,
                quizWrong = old.quizWrong + if (correct) 0 else 1,
            )
        )
        refresh()
    }

    /** 내 말모음에서 다시 듣기를 끝까지 들음 → 해제 */
    suspend fun onReplayFinished(wordId: String) {
        dao.setReplay(wordId, false)
        refresh()
    }

    /** 누적 거리. odometerKm: 차량 누적 주행 거리 (모르면 null), deltaKm: 차속 적분 증가분 */
    suspend fun addDistance(odometerKm: Double?, deltaKm: Double) {
        val d = dao.distance() ?: DistanceEntity()
        dao.upsertDistance(
            d.copy(
                odometerAtInstall = d.odometerAtInstall ?: odometerKm,
                odometerNow = odometerKm ?: d.odometerNow,
                integratedKm = d.integratedKm + deltaKm,
            )
        )
    }

    /** 학습 기록 초기화: 설정을 뺀 모든 표를 비운다 (거리는 지금 Odometer 부터 다시 센다) */
    suspend fun reset() {
        dao.clearLearned(); dao.clearCarryOver(); dao.clearClasses(); dao.clearDrives(); dao.clearDistance()
        prefs.edit().remove(KEY_ACTIVE).remove(KEY_LAST_CARD).apply()
        refresh()
    }

    private companion object {
        const val TAG = "ProgressRepository"
        const val KEY_ACTIVE = "activeClass"
        const val KEY_LAST_CARD = "lastCardIndex"
    }
}

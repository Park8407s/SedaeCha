package com.kumhoht.sedaecha.service

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.kumhoht.sedaecha.Arrival
import com.kumhoht.sedaecha.SedaeApp
import com.kumhoht.sedaecha.audio.SpeechClient
import com.kumhoht.sedaecha.core.ClassEngine
import com.kumhoht.sedaecha.core.ClassListener
import com.kumhoht.sedaecha.core.ClassPlan
import com.kumhoht.sedaecha.core.ClassSummary
import com.kumhoht.sedaecha.core.Gear
import com.kumhoht.sedaecha.core.PlannedWord
import com.kumhoht.sedaecha.core.Rules
import com.kumhoht.sedaecha.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 실제 차량 신호로 클래스를 돌린다 (메인 스레드).
 * 100ms 마다 차량 신호를 넣고 엔진 시계를 돌린다.
 * 클래스 결과는 Progress Repository 에 저장하고, 도착하면 도착 화면을 띄운다.
 */
class ClassRunner(private val app: SedaeApp) : ClassListener, SpeechClient {
    private val engine = ClassEngine(app.pack, app.speech, this, app.rules, ::plan, idPrefix = "c")
    private var loop: Job? = null

    var rules: Rules
        get() = engine.rules
        set(v) { if (engine.state == ClassEngine.State.IDLE) engine.rules = v else pendingRules = v }
    private var pendingRules: Rules? = null

    private val _state = MutableStateFlow(ClassEngine.State.IDLE)
    val state: StateFlow<ClassEngine.State> = _state.asStateFlow()

    private var wallStart = 0L
    private var tripKm = 0.0
    private var lastSignalAt = 0L
    private var lastDistanceSave = 0L
    private var pendingKm = 0.0
    private var lastStatus = ""

    val running: Boolean get() = loop?.isActive == true

    fun start() {
        if (running) return
        Log.i(TAG, "클래스 대기 시작")
        loop = app.main.launch {
            while (isActive) {
                step(SystemClock.elapsedRealtime())
                delay(TICK_MS)
            }
        }
    }

    /** 앱 끄기·서비스 종료: 진행 중인 클래스는 도착 없이 끝내고 (남은 말은 다음 운행으로) 멈춘다 */
    fun stop() {
        loop?.cancel()
        loop = null
        engine.abort(SystemClock.elapsedRealtime())
        if (app.speech.client === this) app.speech.client = null
        _state.value = engine.state
    }

    private fun step(now: Long) {
        // 한국어 음성이 없거나, 심사용 데모가 재생 중이면 클래스를 시작하지 않는다
        engine.enabled = app.speech.status.value.usable && !app.demo.active
        if (now - lastSignalAt >= SIGNAL_MS) {
            val v = app.telemetry.current()
            val dt = if (lastSignalAt == 0L) 0L else now - lastSignalAt
            lastSignalAt = now
            // 다시 듣기 재생 중 기어가 D 로 바뀌면 다시 듣기를 멈추고 클래스를 시작한다
            if (v.gear == Gear.D && app.playingKey.value != null) app.stopPlayback()
            engine.onSignal(now, v.signal)
            accumulateDistance(now, v.speedKmh, dt, v.odometerKm)
        } else engine.tick(now)
        val st = engine.status().let { "${it.state} ${it.corner ?: "-"} ${it.word ?: ""} hold=${it.hold}" }
        if (st != lastStatus) { lastStatus = st; Log.i(TAG, "상태: $st") }
        if (_state.value != engine.state) {
            _state.value = engine.state
            if (engine.state == ClassEngine.State.IDLE) pendingRules?.let { engine.rules = it; pendingRules = null }
        }
    }

    private fun accumulateDistance(now: Long, kmh: Float, dt: Long, odometer: Double?) {
        val km = kmh * dt / 3_600_000.0
        if (engine.state != ClassEngine.State.IDLE) tripKm += km
        pendingKm += km
        if (now - lastDistanceSave >= 30_000 && (pendingKm > 0 || odometer != null)) {
            lastDistanceSave = now
            val add = pendingKm
            pendingKm = 0.0
            app.io.launch { app.repo.addDistance(odometer, add) }
        }
    }

    private fun plan(now: Long): ClassPlan =
        app.planner.plan(app.repo.snapshot, app.direction.value, engine.rules, System.currentTimeMillis())

    // ------------------------------------------------------------------ ClassListener

    override fun onClassStarted(plan: ClassPlan, startedAt: Long) {
        Log.i(TAG, "클래스 시작: ${plan.route} ${plan.direction} ${plan.words.map { it.item.word }} T=${plan.tripMin}분 덤=${plan.bonus?.id}")
        wallStart = System.currentTimeMillis()
        tripKm = 0.0
        app.stopPlayback()
        app.speech.client = this
        app.io.launch { app.repo.onClassStarted(plan) }
    }

    override fun onWordHeard(word: PlannedWord, replay: Boolean) {
        Log.i(TAG, "배운 말: ${word.item.word} (${word.mode}) 다시 듣기=$replay")
        val route = engine.currentPlan?.route ?: ""
        val now = System.currentTimeMillis()
        app.io.launch { app.repo.onWordHeard(word, replay, route, now) }
    }

    override fun onQuizAsked(word: PlannedWord) { Log.i(TAG, "신호 대기 퀴즈: ${word.item.word}") }

    override fun onClassEnded(summary: ClassSummary) {
        val end = System.currentTimeMillis()
        val km = tripKm
        Log.i(TAG, "클래스 끝: 들은 말 ${summary.heard.size}, 남은 말 ${summary.unheard.size}, 퀴즈 ${summary.quizzes}, 도착 화면 ${summary.showArrival}")
        app.io.launch { app.repo.onClassEnded(summary, wallStart, end, km) }
        if (app.speech.client === this) app.speech.client = null
        if (summary.showArrival) {
            app.showArrival(Arrival(summary, end, ((end - wallStart) / 60_000.0).roundToInt(), km, sample = false))
            // 주차 상태라 화면을 띄울 수 있다
            runCatching { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    // ------------------------------------------------------------------ SpeechClient

    override fun onSpeechDone(id: String) = engine.onSpeechDone(id, SystemClock.elapsedRealtime())
    override fun onFocusLost(permanent: Boolean) = engine.onFocusLost(SystemClock.elapsedRealtime(), permanent)
    override fun onFocusGained() = engine.onFocusGained(SystemClock.elapsedRealtime())

    private companion object {
        const val TAG = "ClassRunner"
        const val TICK_MS = 100L
        /** 차량 신호를 엔진에 넣는 간격 (급가속·급제동 1초 창을 촘촘하게 보기 위해 100ms) */
        const val SIGNAL_MS = 100L
    }
}

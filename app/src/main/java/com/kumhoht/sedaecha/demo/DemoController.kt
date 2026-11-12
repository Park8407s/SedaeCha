package com.kumhoht.sedaecha.demo

import com.kumhoht.sedaecha.Arrival
import com.kumhoht.sedaecha.SedaeApp
import com.kumhoht.sedaecha.audio.SpeechClient
import com.kumhoht.sedaecha.core.ClassEngine
import com.kumhoht.sedaecha.core.ClassListener
import com.kumhoht.sedaecha.core.ClassSummary
import com.kumhoht.sedaecha.core.CornerKind
import com.kumhoht.sedaecha.core.EngineStatus
import com.kumhoht.sedaecha.core.Gear
import com.kumhoht.sedaecha.core.Rules
import com.kumhoht.sedaecha.core.Signal
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 심사용 데모 화면 상태 */
data class DemoState(
    val running: Boolean = false,
    val paused: Boolean = false,
    /** 기록 위치 (초) */
    val positionSec: Float = 0f,
    val signal: Signal = Signal.PARKED,
    val status: EngineStatus? = null,
    val finished: Boolean = false,
)

/** 장면 바로 가기 (개발 명세 9) */
enum class DemoScene(val label: String, val sec: Int) {
    STOP_QUIZ("신호 대기 · 듣기 퀴즈", 34),
    CONGESTION("정체 · 덤 코너", 115),
    HARD_BRAKE("급제동 · 즉시 멈춤", 140),
    ARRIVE("도착 · 도착 화면", 186),
}

/**
 * Demo Player (출품 빌드 전용): 가상 출근길 기록을 1초 간격으로 재생해 Drive Signal Source 를 바꿔 넣는다.
 * 판단 로직(ClassEngine)은 실차와 같고, 결과는 내 말모음·레벨·거리에 저장하지 않는다.
 *
 * 가상 시계: 일시 정지하면 시계도 멈춘다. 기록 0초 = 가상 시계 [OFFSET_MS] (그 전에 주차 신호를 한 번 넣는다).
 */
class DemoController(private val app: SedaeApp) : ClassListener, SpeechClient {
    private val log = app.demoLog
    private var engine = newEngine()
    private var loop: Job? = null
    private var vnow = 0L
    private var lastFedSec = -1

    private val _state = MutableStateFlow(DemoState())
    val state: StateFlow<DemoState> = _state.asStateFlow()

    /** 데모 재생 중이면 실제 클래스를 시작하지 않는다 */
    val active: Boolean get() = _state.value.running

    val durationSec: Int get() = log.durationSec

    private fun newEngine() = ClassEngine(app.pack, app.speech, this, Rules.DEMO, ::plan, idPrefix = "d")

    private fun plan(@Suppress("UNUSED_PARAMETER") now: Long) = app.planner.demoPlan(log.wordTimes, log.cardId, log.route)

    /** 처음부터 */
    fun start() {
        reset()
        engine.onSignal(0L, Signal.PARKED)
        vnow = OFFSET_MS
        lastFedSec = -1
        feed()
        runLoop()
    }

    /** 장면 바로 가기: 그 시점 이전의 말은 들은 것으로 처리하고 클래스 상태를 다시 맞춘다 */
    fun jump(scene: DemoScene) {
        reset()
        val t = scene.sec
        val plan = plan(0L)
        engine.onSignal(0L, Signal.PARKED)
        engine.detector.prime((0 until t).map { OFFSET_MS + it * 1000L to log.signalAt(it) })
        val sec = { s: Int -> OFFSET_MS + s * 1000L }
        val lucky = plan.words.getOrNull(2)?.item?.id
        when (scene) {
            DemoScene.STOP_QUIZ -> engine.restoreDemoClass(sec(t), plan, OFFSET_MS, heardCount = 1, quizzes = 0, bonusDone = false, replay = emptySet(), lastQuizEnd = null)
            DemoScene.CONGESTION -> engine.restoreDemoClass(sec(t), plan, OFFSET_MS, heardCount = 2, quizzes = 1, bonusDone = false, replay = emptySet(), lastQuizEnd = sec(55))
            DemoScene.HARD_BRAKE -> engine.restoreDemoClass(sec(t), plan, OFFSET_MS, heardCount = 2, quizzes = 1, bonusDone = true, replay = emptySet(), lastQuizEnd = sec(55))
            DemoScene.ARRIVE -> engine.restoreDemoClass(sec(t), plan, OFFSET_MS, heardCount = 3, quizzes = 2, bonusDone = true, replay = setOfNotNull(lucky), lastQuizEnd = sec(176))
        }
        vnow = sec(t)
        lastFedSec = t - 1
        feed()
        runLoop()
    }

    fun togglePause() {
        val s = _state.value
        if (!s.running) return
        val p = !s.paused
        engine.setUserHold(p, vnow)
        _state.value = s.copy(paused = p, status = engine.status())
    }

    /** 데모를 끝낸다 (홈으로, 앱 끄기, 실제 기어가 D 로 바뀜) */
    fun stop() {
        loop?.cancel(); loop = null
        engine.abort(vnow)
        if (app.speech.client === this) { app.speech.stop(); app.speech.abandonFocus(); app.speech.client = null }
        _state.value = DemoState()
    }

    private fun reset() {
        loop?.cancel(); loop = null
        engine.abort(vnow)
        app.stopPlayback()
        engine = newEngine()
        app.speech.client = this
        vnow = 0L
        _state.value = DemoState(running = true)
    }

    private fun runLoop() {
        loop = app.main.launch {
            while (isActive) {
                delay(TICK_MS)
                // 실제 차량이 출발하면 데모를 멈춘다 (데모는 주차 중에만)
                if (app.telemetry.current().gear != Gear.P) { stop(); return@launch }
                if (_state.value.paused) continue
                vnow += TICK_MS
                feed()
            }
        }
    }

    /** 새 초가 되면 기록의 신호를 넣고, 아니면 시계만 돌린다 */
    private fun feed() {
        val sec = ((vnow - OFFSET_MS) / 1000).toInt()
        val signal: Signal
        if (sec > lastFedSec) {
            lastFedSec = sec
            signal = if (sec < log.speed.size) log.signalAt(sec) else Signal.PARKED
            engine.onSignal(vnow, signal)
        } else {
            signal = if (sec in log.speed.indices) log.signalAt(sec) else Signal.PARKED
            engine.tick(vnow)
        }
        val pos = ((vnow - OFFSET_MS) / 1000f).coerceIn(0f, log.durationSec.toFloat())
        val cur = _state.value
        if (cur.running) _state.value = cur.copy(positionSec = pos, signal = signal, status = engine.status())
    }

    // ------------------------------------------------------------------ ClassListener (저장하지 않음)

    override fun onClassEnded(summary: ClassSummary) {
        loop?.cancel(); loop = null
        if (app.speech.client === this) app.speech.client = null
        _state.value = _state.value.copy(running = false, paused = false, finished = true, positionSec = log.durationSec.toFloat(), status = engine.status())
        if (summary.showArrival) app.showArrival(Arrival(summary, System.currentTimeMillis(), 17, 15.0, sample = true))
    }

    // ------------------------------------------------------------------ SpeechClient

    override fun onSpeechDone(id: String) { engine.onSpeechDone(id, vnow); refresh() }
    override fun onFocusLost(permanent: Boolean) { engine.onFocusLost(vnow, permanent); refresh() }
    override fun onFocusGained() { engine.onFocusGained(vnow); refresh() }

    private fun refresh() {
        val cur = _state.value
        if (cur.running) _state.value = cur.copy(status = engine.status())
    }

    companion object {
        private const val TICK_MS = 100L
        const val OFFSET_MS = 1_000L

        /** 데모 화면 문구 (개발 명세 9: '말 2 설명 중', '클래스 잠시 멈춤', '듣기 퀴즈') */
        fun headline(s: EngineStatus?): String {
            if (s == null) return "처음부터 또는 장면 바로 가기를 눌러 시작하세요"
            val what = when (s.corner) {
                CornerKind.INTRO -> "클래스 시작 · 오늘의 말 소개"
                CornerKind.WORD -> "말 ${s.wordNumber} ‘${s.word}’ 설명 중"
                CornerKind.QUIZ -> "듣기 퀴즈 · ‘${s.word}’"
                CornerKind.BONUS -> "덤 코너 · 요즘 문화 한 스푼"
                CornerKind.OUTRO -> "도착 · 마무리 멘트"
                null -> when (s.state) {
                    ClassEngine.State.IDLE -> if (s.heardCount > 0) "클래스 끝" else "주차 중"
                    else -> "코너 사이 · 원래 듣던 음악이 이어지는 중"
                }
            }
            return if (s.hold != null) "클래스 잠시 멈춤 · $what" else what
        }

        fun detail(s: EngineStatus?): String = when (s?.hold) {
            null -> when (s?.corner) {
                CornerKind.QUIZ -> "신호 대기 3초가 지나 퀴즈를 내요. 질문 → 5초 생각 → 정답 공개. 출발하면 정답만 들려주고 끝내요."
                CornerKind.BONUS -> "30km/h 미만이 이어져(데모 20초) 정체로 판단했어요. 곡은 재생하지 않고 소개만 해요."
                CornerKind.WORD -> "뜻 → 생긴 배경 → 예문 → 그때말 짝 순서로 들려줘요."
                CornerKind.INTRO -> "기어가 D 로 바뀌어 클래스를 시작했어요. 실제 차량에서는 앱 화면이 내려가요."
                CornerKind.OUTRO -> "기어 P + 차속 0 으로 도착을 판단했어요."
                null -> "코너가 끝나면 오디오 포커스를 돌려줘 원래 듣던 음악·라디오가 이어져요."
            }
            "방향지시등" -> "방향지시등이 꺼지면 1초 뒤 멈춘 문장의 처음부터 다시 들려줘요. 실제 차량에서는 이때 앱 화면이 보이지 않습니다."
            "급가속·급제동" -> "10초 동안 아무 코너도 시작하지 않고, ‘이어서 들을게요’ 뒤 멈춘 문장부터 이어 가요. 듣던 말은 ‘다시 듣기’로 표시해요."
            "내비 안내" -> "내비 음성 안내가 끝나 포커스를 돌려받으면 멈춘 문장의 처음부터 이어 가요."
            "일시 정지" -> "다시 재생하면 멈춘 문장의 처음부터 이어 가요."
            else -> "기어가 D 로 돌아오면 멈춘 문장의 처음부터 이어 가요."
        }
    }
}

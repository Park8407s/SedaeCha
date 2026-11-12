package com.kumhoht.sedaecha.core

import com.kumhoht.sedaecha.core.content.ContentPack

/** 소리 출력 (Speech Player). 문장 하나를 말하고 끝나면 [ClassEngine.onSpeechDone] 으로 알린다. */
interface Speaker {
    fun speak(id: String, text: String)
    fun stop()
    /** 코너 시작 시 오디오 포커스 (AUDIOFOCUS_GAIN_TRANSIENT). 못 받으면 false */
    fun requestFocus(): Boolean
    fun abandonFocus()
}

/** 클래스 진행 결과를 저장·화면에 넘기는 쪽 (Progress Repository 연결) */
interface ClassListener {
    fun onClassStarted(plan: ClassPlan, startedAt: Long) {}
    /** 말 코너를 끝까지 들음 = 배운 말. replay = 급가속·급제동으로 끊겼던 말 */
    fun onWordHeard(word: PlannedWord, replay: Boolean) {}
    fun onQuizAsked(word: PlannedWord) {}
    fun onClassEnded(summary: ClassSummary) {}
}

enum class CornerKind { INTRO, WORD, QUIZ, BONUS, OUTRO }

sealed interface Step {
    data class Say(val text: String) : Step
    data class Wait(val ms: Long) : Step
}

/** 한 번의 클래스 결과 */
data class ClassSummary(
    val plan: ClassPlan,
    val startedAt: Long,
    val endedAt: Long,
    /** 끝까지 들은 말 (들은 순서) */
    val heard: List<PlannedWord>,
    /** 다시 듣기 표시된 말 id */
    val replay: Set<String>,
    /** 편성했지만 끝까지 못 들은 말 → 다음 운행 첫 순서 */
    val unheard: List<PlannedWord>,
    val quizzes: Int,
    val turnStops: Int,
    val hardStops: Int,
    /** 기어 P + 차속 0 으로 도착했는지 */
    val arrived: Boolean,
    /** 덤 코너를 들려줬는지 (다음 클래스는 다음 문화 카드) */
    val bonusPlayed: Boolean = false,
) {
    /** 들은 말이 0개면 도착 화면 없이 끝낸다 */
    val showArrival: Boolean get() = arrived && heard.isNotEmpty()
}

/** 데모 화면 등에 보여 줄 현재 상태 */
data class EngineStatus(
    val state: ClassEngine.State,
    val corner: CornerKind?,
    /** 말 코너면 1부터 */
    val wordNumber: Int,
    val word: String?,
    /** 멈춘 이유 (없으면 null) */
    val hold: String?,
    val heardCount: Int,
    val quizzes: Int,
)

/**
 * Drive State Machine + Class Scheduler (개발 명세 2·4·5·6).
 *
 * 입력은 세 가지뿐이다: 차량 신호([onSignal]), 시간([tick]), 소리 콜백([onSpeechDone], 포커스).
 * 모두 같은 스레드에서 부른다. 시간은 밀리초 단조 시계(실차: elapsedRealtime, 데모: 가상 시계).
 *
 * 우선순위: 내비 안내(포커스 손실) > 급가속·급제동 > 방향지시등 > 기어 R·N > 신호 대기 퀴즈 > 정체 덤 코너 > 말 코너
 * 앞의 넷은 '멈춤 조건'이라 진행 중인 코너를 멈추고, 풀리면 멈춘 문장의 처음부터 이어 간다.
 */
class ClassEngine(
    private val pack: ContentPack,
    private val speaker: Speaker,
    private val listener: ClassListener,
    rules: Rules,
    private val planProvider: (now: Long) -> ClassPlan,
    /** 발화 id 머리말 (실제 클래스와 데모가 같은 TTS 를 나눠 쓸 때 구분) */
    private val idPrefix: String = "c",
) {
    enum class State { IDLE, DRIVING, PAUSED, ARRIVED }

    var rules: Rules = rules
        set(v) { field = v; detector.rules = v }
    val detector = EventDetector(rules)

    var state = State.IDLE
        private set

    /** false: 클래스를 시작하지 않음 (한국어 음성 없음, 앱 끄기, 데모 재생 중) */
    var enabled = true

    /** 주차(P)를 본 뒤에만 D 진입을 출발로 본다 (서비스가 주행 중에 다시 살아난 경우 그 운행은 버린다) */
    private var seenParked = false

    private var session: Session? = null
    private var run: Run? = null
    private var cornerSeq = 0
    private var utterSeq = 0

    // 멈춤 조건
    private var hardUntil: Long? = null
    private var turnHold = false
    private var turnOffSince: Long? = null
    private var navHold = false
    private var navRetryAt: Long? = null
    private var userHold = false

    private var hasFocus = false
    /** 코너가 끝나고 곧바로 다음 코너가 오면 포커스를 놓았다 다시 잡지 않도록 잠깐 기다렸다가 반납 */
    private var releaseFocusAt: Long? = null

    private class Session(val plan: ClassPlan, val startedAt: Long) {
        var introEndAt: Long? = null
        var next = 0
        val heard = ArrayList<PlannedWord>()
        val replay = LinkedHashSet<String>()
        val quizPerWord = HashMap<String, Int>()
        var quizzes = 0
        var lastQuizEnd: Long? = null
        var quizStopId = -1
        var bonusDone = false
        var turnStops = 0
        var hardStops = 0
    }

    private class Run(val kind: CornerKind, steps: List<Step>, val seq: Int, val word: PlannedWord? = null, val wordNumber: Int = 0) {
        val steps = steps.toMutableList()
        var idx = 0
        var speakingId: String? = null
        var waitUntil: Long? = null
        var held = false
        var needResumeMent = false
        /** 퀴즈: 정답 문장 위치 */
        var answerIdx = -1
    }

    // ------------------------------------------------------------------ 입력

    fun onSignal(now: Long, s: Signal) {
        val hard = detector.update(now, s)
        if (s.gear == Gear.P) seenParked = true
        when (state) {
            State.IDLE -> if (s.gear == Gear.D && seenParked && enabled) startClass(now)
            State.DRIVING -> {
                if (s.gear == Gear.P && s.stopped) { arrive(now); return }
                if (s.gear == Gear.R || s.gear == Gear.N) { pauseForGear(); return }
                if (hard != null) onHard(now)
                handleQuizDeparture(now, s)
                handleTurn(now, s)
            }
            State.PAUSED -> {
                if (s.gear == Gear.P && s.stopped) { arrive(now); return }
                if (s.gear == Gear.D) state = State.DRIVING
            }
            State.ARRIVED -> Unit
        }
        tick(now)
    }

    fun tick(now: Long) {
        if (hardUntil?.let { now >= it } == true) hardUntil = null
        if (turnHold && turnOffSince?.let { now - it >= rules.resumeAfterTurnMs } == true) { turnHold = false; turnOffSince = null }
        if (navHold && navRetryAt?.let { now >= it } == true) {
            if (speaker.requestFocus()) { hasFocus = true; navHold = false; navRetryAt = null } else navRetryAt = now + NAV_RETRY_MS
        }
        if (run == null && hasFocus && releaseFocusAt?.let { now >= it } == true) releaseFocus()
        if (state != State.DRIVING && state != State.ARRIVED) return
        if (isHeld()) return
        val r = run
        if (r != null) advance(now, r) else if (state == State.DRIVING) pickNext(now)
    }

    fun onSpeechDone(id: String, now: Long) {
        val r = run ?: return
        if (r.speakingId != id) return
        r.speakingId = null
        r.idx++
        val next = r.steps.getOrNull(r.idx)
        if (next is Step.Say) r.waitUntil = now + rules.sentenceGapMs
        tick(now)
    }

    /** 다른 앱(내비 안내 등)이 오디오 포커스를 가져감. permanent = LOSS (다시 돌려주지 않음) */
    fun onFocusLost(now: Long, permanent: Boolean) {
        hasFocus = false
        releaseFocusAt = null
        if (run != null) {
            interrupt()
            navHold = true
            navRetryAt = if (permanent) now + NAV_RETRY_MS else null
        }
    }

    fun onFocusGained(now: Long) {
        hasFocus = true
        navHold = false
        navRetryAt = null
        tick(now)
    }

    /** 데모 일시 정지 */
    fun setUserHold(hold: Boolean, now: Long) {
        if (hold == userHold) return
        userHold = hold
        if (hold) { interrupt(); releaseFocus() } else tick(now)
    }

    // ------------------------------------------------------------------ 상태

    fun status(): EngineStatus {
        val s = session
        val r = run
        val hold = when {
            navHold -> "내비 안내"
            hardUntil != null -> "급가속·급제동"
            turnHold -> "방향지시등"
            state == State.PAUSED -> "기어 ${detector.last.gear}"
            userHold -> "일시 정지"
            else -> null
        }
        return EngineStatus(
            state = state, corner = r?.kind, wordNumber = r?.wordNumber ?: 0, word = r?.word?.item?.word,
            hold = hold, heardCount = s?.heard?.size ?: 0, quizzes = s?.quizzes ?: 0,
        )
    }

    val currentPlan: ClassPlan? get() = session?.plan

    // ------------------------------------------------------------------ 출발·도착

    private fun startClass(now: Long) {
        val plan = planProvider(now)
        val s = Session(plan, now)
        session = s
        state = State.DRIVING
        clearHolds()
        listener.onClassStarted(plan, now)
        val t = pack.templates
        val lines = t.intro.map {
            it.replace("{route}", plan.route)
                .replace("{mode_label}", t.modeLabel[plan.direction.key] ?: "말")
                .replace("{n}", plan.words.size.toString())
        } + if (plan.carryOverCount > 0) t.introCarryOver else emptyList()
        startRun(now, Run(CornerKind.INTRO, lines.map { Step.Say(it) }, ++cornerSeq))
    }

    /**
     * 심사용 데모의 장면 바로 가기: 그 시점까지 진행된 클래스 상태를 바로 만든다.
     * 그 시점 이전의 말은 들은 것으로 처리한다 (개발 명세 9).
     */
    fun restoreDemoClass(now: Long, plan: ClassPlan, startedAt: Long, heardCount: Int, quizzes: Int, bonusDone: Boolean, replay: Set<String>, lastQuizEnd: Long?) {
        stopRun()
        clearHolds()
        val s = Session(plan, startedAt)
        s.introEndAt = startedAt + DEMO_INTRO_MS
        s.next = heardCount.coerceAtMost(plan.words.size)
        s.heard += plan.words.take(s.next)
        s.replay += replay
        s.quizzes = quizzes
        s.lastQuizEnd = lastQuizEnd
        s.bonusDone = bonusDone
        session = s
        state = State.DRIVING
        seenParked = true
        listener.onClassStarted(plan, startedAt)
        tick(now)
    }

    private fun arrive(now: Long) {
        val s = session
        stopRun()
        clearHolds()
        if (s == null) { state = State.IDLE; return }
        state = State.ARRIVED
        if (s.heard.isEmpty()) { endClass(now, arrived = true); return }
        val t = pack.templates
        val unheard = s.plan.words.size > s.heard.size
        val outro = when {
            s.replay.isNotEmpty() -> t.outroReplay
            unheard -> t.outroCarryOver
            else -> t.outroAllDone
        }
        startRun(now, Run(CornerKind.OUTRO, outro.map { Step.Say(it) }, ++cornerSeq))
    }

    /** 앱 끄기·서비스 종료: 진행 중인 클래스를 도착 없이 끝낸다 (못 들은 말은 다음 운행으로) */
    fun abort(now: Long) {
        stopRun()
        if (session != null) endClass(now, arrived = false)
        state = State.IDLE
        seenParked = false
    }

    private fun endClass(now: Long, arrived: Boolean) {
        val s = session ?: return
        session = null
        releaseFocus()
        clearHolds()
        state = State.IDLE
        seenParked = detector.last.gear == Gear.P
        val heardIds = s.heard.map { it.item.id }.toSet()
        listener.onClassEnded(
            ClassSummary(
                plan = s.plan, startedAt = s.startedAt, endedAt = now, heard = s.heard.toList(), replay = s.replay.toSet(),
                unheard = s.plan.words.filter { it.item.id !in heardIds }, quizzes = s.quizzes,
                turnStops = s.turnStops, hardStops = s.hardStops, arrived = arrived, bonusPlayed = s.bonusDone,
            )
        )
    }

    // ------------------------------------------------------------------ 멈춤 조건

    private fun isHeld(): Boolean = navHold || hardUntil != null || turnHold || userHold || state == State.PAUSED

    /** 진행 중인 문장을 멈춘다. 다시 이어 갈 때는 멈춘 문장(현재 step)의 처음부터 */
    private fun interrupt(): Boolean {
        val r = run ?: return false
        if (r.held) return false
        speaker.stop()
        r.held = true
        r.speakingId = null
        r.waitUntil = null
        return true
    }

    private fun onHard(now: Long) {
        hardUntil = now + rules.resumeAfterHardMs
        val r = run ?: return
        val s = session ?: return
        when (r.kind) {
            // 퀴즈 중이었다면 퀴즈 취소
            CornerKind.QUIZ -> { s.hardStops++; cancelQuiz(now) }
            else -> {
                if (!r.held) s.hardStops++
                r.word?.let { s.replay += it.item.id } // 그때 듣던 말은 '다시 듣기'
                interrupt()
                r.needResumeMent = true
            }
        }
    }

    private fun handleTurn(now: Long, s: Signal) {
        if (s.turn != Turn.OFF) {
            turnOffSince = null
            // 정차 중(차속 0)에 켠 방향지시등은 무시 (좌회전 대기 중에도 퀴즈 가능)
            if (!turnHold && !s.stopped) {
                turnHold = true
                if (run?.held == false) { session?.let { it.turnStops++ }; interrupt() }
            }
        } else if (turnHold && turnOffSince == null) turnOffSince = now
    }

    /** 퀴즈 중 출발: 생각 시간을 건너뛰고 정답 한 문장만. 방향지시등이 켜져 있으면 정답도 생략 */
    private fun handleQuizDeparture(now: Long, s: Signal) {
        val r = run ?: return
        if (r.kind != CornerKind.QUIZ || s.stopped) return
        if (s.turn != Turn.OFF) { cancelQuiz(now); return }
        if (r.idx < r.answerIdx) {
            if (r.speakingId != null) speaker.stop()
            r.speakingId = null
            r.waitUntil = null
            r.idx = r.answerIdx
        }
    }

    private fun cancelQuiz(now: Long) {
        speaker.stop()
        run = null
        session?.lastQuizEnd = now
        releaseFocusAt = now + FOCUS_LINGER_MS
    }

    private fun pauseForGear() {
        state = State.PAUSED
        interrupt()
        releaseFocus()
    }

    private fun clearHolds() {
        hardUntil = null; turnHold = false; turnOffSince = null; navHold = false; navRetryAt = null
    }

    // ------------------------------------------------------------------ 코너 진행

    private fun pickNext(now: Long) {
        val s = session ?: return
        if (s.introEndAt == null) return
        // 5. 신호 대기 퀴즈
        if (detector.isWaitingAtSignal(now) && detector.stopId != s.quizStopId &&
            s.lastQuizEnd?.let { now - it >= rules.quizIntervalMs } != false
        ) {
            val w = quizWord(s)
            if (w != null) { startQuiz(now, s, w); return }
        }
        // 6. 정체 덤 코너
        val bonus = s.plan.bonus
        if (!s.bonusDone && bonus != null && detector.isCongested(now)) {
            s.bonusDone = true
            startRun(now, Run(CornerKind.BONUS, (pack.templates.bonusLead + bonus.sentences).map { Step.Say(it) }, ++cornerSeq))
            return
        }
        // 7. 말 코너 (예정 시각이 됐으면. 예상보다 길어지면 더 편성하지 않고 퀴즈·덤만)
        val w = s.plan.words.getOrNull(s.next) ?: return
        val base = if (s.plan.timeBase == TimeBase.CLASS_START) s.startedAt else s.introEndAt!!
        if (now >= base + w.dueMs) {
            s.next++
            startRun(now, Run(CornerKind.WORD, w.item.sentences(w.mode).map { Step.Say(it) }, ++cornerSeq, w, s.next))
        }
    }

    /** 이번 클래스에서 들은 말 (다시 듣기 우선, 같은 말은 2번까지). 없으면 지난 클래스의 말 */
    private fun quizWord(s: Session): PlannedWord? {
        fun ok(w: PlannedWord) = (s.quizPerWord[w.item.id] ?: 0) < rules.maxQuizPerWord
        val heard = s.heard.filter(::ok)
        if (heard.isNotEmpty()) {
            return heard.sortedWith(
                compareBy<PlannedWord>({ if (it.item.id in s.replay) 0 else 1 }, { s.quizPerWord[it.item.id] ?: 0 }, { -s.heard.indexOf(it) })
            ).first()
        }
        if (s.heard.isNotEmpty()) return null
        return s.plan.fallbackQuiz.firstOrNull(::ok)
    }

    private fun startQuiz(now: Long, s: Session, w: PlannedWord) {
        s.quizStopId = detector.stopId
        s.quizPerWord[w.item.id] = (s.quizPerWord[w.item.id] ?: 0) + 1
        s.quizzes++
        listener.onQuizAsked(w)
        val q = w.item.quiz(w.mode)
        val steps = pack.templates.quizLead.map { Step.Say(it) } + Step.Say(q.voiceQuestion) + Step.Wait(rules.quizThinkMs) + Step.Say(q.voiceAnswer)
        val r = Run(CornerKind.QUIZ, steps, ++cornerSeq, w)
        r.answerIdx = steps.size - 1
        startRun(now, r)
    }

    private fun startRun(now: Long, r: Run) {
        run = r
        releaseFocusAt = null
        advance(now, r)
    }

    private fun advance(now: Long, r: Run) {
        if (r.held) {
            r.held = false
            if (r.needResumeMent) {
                r.needResumeMent = false
                pack.templates.resumeAfterHard.reversed().forEach { r.steps.add(r.idx, Step.Say(it)) }
                if (r.answerIdx >= r.idx) r.answerIdx += pack.templates.resumeAfterHard.size
            }
        }
        if (r.speakingId != null) return
        r.waitUntil?.let { if (now < it) return; r.waitUntil = null }
        if (r.idx >= r.steps.size) { finishRun(now, r); return }
        if (!hasFocus) {
            if (speaker.requestFocus()) hasFocus = true
            else { r.held = true; navHold = true; navRetryAt = now + NAV_RETRY_MS; return }
        }
        when (val step = r.steps[r.idx]) {
            is Step.Say -> {
                val id = "$idPrefix${r.seq}_${r.idx}_${++utterSeq}"
                r.speakingId = id
                speaker.speak(id, step.text)
            }
            is Step.Wait -> {
                r.idx++
                r.waitUntil = now + step.ms
            }
        }
    }

    private fun finishRun(now: Long, r: Run) {
        run = null
        releaseFocusAt = now + FOCUS_LINGER_MS
        val s = session ?: return
        when (r.kind) {
            CornerKind.INTRO -> s.introEndAt = now
            CornerKind.WORD -> r.word?.let { w ->
                s.heard += w
                listener.onWordHeard(w, w.item.id in s.replay)
            }
            CornerKind.QUIZ -> s.lastQuizEnd = now
            CornerKind.BONUS -> Unit
            CornerKind.OUTRO -> { endClass(now, arrived = true); return }
        }
        tick(now)
    }

    private fun stopRun() {
        if (run != null) speaker.stop()
        run = null
    }

    private fun releaseFocus() {
        releaseFocusAt = null
        if (hasFocus) { hasFocus = false; speaker.abandonFocus() }
    }

    companion object {
        private const val NAV_RETRY_MS = 5_000L
        private const val FOCUS_LINGER_MS = 600L
        /** 가상 주행 기록의 인트로 길이 (말 1 이 8초에 시작) */
        const val DEMO_INTRO_MS = 8_000L
    }
}

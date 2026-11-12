package com.kumhoht.sedaecha.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.kumhoht.sedaecha.Arrival
import com.kumhoht.sedaecha.BuildConfig
import com.kumhoht.sedaecha.SedaeApp
import com.kumhoht.sedaecha.core.Review
import com.kumhoht.sedaecha.core.ReviewQuestion
import com.kumhoht.sedaecha.core.WordMode
import com.kumhoht.sedaecha.data.ClassLogEntity
import com.kumhoht.sedaecha.data.LearnedWordEntity
import com.kumhoht.sedaecha.service.ClassService
import com.kumhoht.sedaecha.ui.screens.ArrivalScreen
import com.kumhoht.sedaecha.ui.screens.BookEntry
import com.kumhoht.sedaecha.ui.screens.BookScreen
import com.kumhoht.sedaecha.ui.screens.DemoScreen
import com.kumhoht.sedaecha.ui.screens.HomeScreen
import com.kumhoht.sedaecha.ui.screens.LastClassUi
import com.kumhoht.sedaecha.ui.screens.QuizScreen
import com.kumhoht.sedaecha.ui.screens.SettingsScreen
import com.kumhoht.sedaecha.ui.screens.whenLabel
import com.kumhoht.sedaecha.ui.theme.Sd
import com.kumhoht.sedaecha.ui.theme.SedaeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

enum class Screen { HOME, ARRIVAL, QUIZ, BOOK, DEMO, SETTINGS }

/**
 * 화면 목록과 이동 (개발 명세 1). 모든 화면은 주차 중에만 보인다 (주행 규제가 켜지면 플랫폼이 숨긴다).
 *
 * adb (디버그 빌드): adb shell am start -n com.kumhoht.sedaecha/.ui.MainActivity --es screen DEMO
 */
class MainActivity : ComponentActivity() {
    private val app by lazy { SedaeApp.of(this) }

    private var screen by mutableStateOf(Screen.HOME)
    private val backStack = mutableStateListOf<Screen>()
    private var quiz by mutableStateOf<List<ReviewQuestion>>(emptyList())
    /** 샘플(데모) 도착 화면에서 해제된 다시 듣기 (저장하지 않으므로 화면에서만) */
    private var sampleReplay by mutableStateOf<Set<String>>(emptySet())
    private var lastArrivalId = 0L

    private fun go(to: Screen) {
        if (to == screen) return
        if (screen == Screen.DEMO && to != Screen.DEMO && to != Screen.ARRIVAL) app.demo.stop()
        if (to == Screen.HOME) backStack.clear() else backStack.add(screen)
        screen = to
    }

    private fun back() {
        val to = backStack.removeLastOrNull() ?: Screen.HOME
        if (screen == Screen.DEMO) app.demo.stop()
        screen = to
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) app.telemetry.onPermissionsGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        ClassService.start(this)
        lifecycleScope.launch { app.repo.refresh() }
        // 도착: 도착 화면으로 (데모 결과도 '샘플'로)
        lifecycleScope.launch {
            app.arrival.collect { a ->
                if (a != null && a.id != lastArrivalId) {
                    lastArrivalId = a.id
                    sampleReplay = if (a.sample) a.summary.replay else emptySet()
                    backStack.clear()
                    screen = Screen.ARRIVAL
                }
            }
        }
        handleIntent(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (backStack.isNotEmpty() || screen != Screen.HOME) back()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
        setContent {
            SedaeTheme {
                Box(Modifier.fillMaxSize().background(Sd.Bg).verticalScroll(rememberScrollState()).padding(horizontal = 64.dp, vertical = 56.dp)) {
                    Content()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (!BuildConfig.DEBUG) return
        i?.getStringExtra("screen")?.let { s -> Screen.entries.firstOrNull { it.name == s }?.let { go(it) } }
    }

    @Composable
    private fun Content() {
        val learned by app.repo.dao.learnedFlow().collectAsState(initial = emptyList())
        val distance by app.repo.dao.distanceFlow().collectAsState(initial = null)
        val lastClass by app.repo.dao.lastClassFlow().collectAsState(initial = null)
        val demoMode by app.demoMode.collectAsState()
        val direction by app.direction.collectAsState()
        val voice by app.speech.status.collectAsState()
        val playingKey by app.playingKey.collectAsState()
        val rate by app.speechRate.collectAsState()
        val arrival by app.arrival.collectAsState()
        val km = distance?.km ?: 0.0

        when (screen) {
            Screen.HOME -> {
                // 미리 보기 편성: 출발할 때와 같은 입력으로 계산 (1분마다 시간대 갱신)
                var minute by remember { mutableStateOf(System.currentTimeMillis() / 60_000) }
                LaunchedEffect(Unit) { while (true) { delay(30_000); minute = System.currentTimeMillis() / 60_000 } }
                val plan = remember(learned, km, demoMode, direction, minute) {
                    app.planner.plan(app.repo.snapshot.copy(distanceKm = km), direction, app.rules, System.currentTimeMillis())
                }
                val last = lastClass?.let { lastClassUi(it) }
                HomeScreen(
                    plan = plan, demoMode = demoMode, clockLabel = clockLabel(), direction = direction, onDirection = app::setDirection,
                    learnedCount = learned.size, lastClass = last, playingKey = playingKey,
                    onReplayLast = {
                        if (last == null) return@HomeScreen
                        if (playingKey == last.playKey) app.stopPlayback()
                        else app.playSentences(last.playKey, lastClassSentences(lastClass!!))
                    },
                    voice = voice, onOpenBook = { go(Screen.BOOK) }, onOpenSettings = { go(Screen.SETTINGS) },
                    onOpenDemo = if (BuildConfig.DEMO_ENABLED) ({ go(Screen.DEMO) }) else null,
                )
            }
            Screen.ARRIVAL -> {
                val a = arrival ?: run { screen = Screen.HOME; return }
                val replayIds = if (a.sample) sampleReplay else learned.filter { it.replay }.map { it.wordId }.toSet()
                ArrivalScreen(
                    arrival = a, replayIds = replayIds, playingKey = playingKey,
                    onReplay = { w ->
                        val key = "word:${w.item.id}"
                        if (playingKey == key) app.stopPlayback()
                        else app.playSentences(key, w.item.sentences(w.mode)) { replayFinished(a, w.item.id) }
                    },
                    onQuiz = { quiz = Review.build(a.summary, replayIds); go(Screen.QUIZ) },
                    onBook = { go(Screen.BOOK) }, onHome = { go(Screen.HOME) },
                )
            }
            Screen.QUIZ -> {
                val a = arrival
                QuizScreen(
                    questions = quiz, sample = a?.sample == true,
                    onAnswer = { q, right -> onReviewAnswer(a, q, right) },
                    onBackToArrival = { back() },
                    onFinish = { if (a?.sample == true) go(Screen.HOME) else go(Screen.BOOK) },
                )
            }
            Screen.BOOK -> BookScreen(
                entries = bookEntries(learned), km = km, demoMode = demoMode, playingKey = playingKey,
                onPlay = { e, mode ->
                    val key = "word:${e.item.id}"
                    if (playingKey == key) app.stopPlayback()
                    else app.playSentences(key, e.item.sentences(e.item.modeOrNow(mode))) {
                        lifecycleScope.launch { app.repo.onReplayFinished(e.item.id) }
                    }
                },
                onHome = { go(Screen.HOME) },
            )
            Screen.DEMO -> {
                val st by app.demo.state.collectAsState()
                DemoScreen(
                    state = st, log = app.demoLog,
                    onStart = { app.demo.start() }, onPause = { app.demo.togglePause() }, onJump = { app.demo.jump(it) },
                    onHome = { go(Screen.HOME) },
                )
            }
            Screen.SETTINGS -> SettingsScreen(
                demoMode = demoMode, onDemoMode = app::setDemoMode,
                speechRate = rate, onSpeechRate = app::setSpeechRate,
                previewing = playingKey == PREVIEW,
                onPreview = {
                    if (playingKey == PREVIEW) app.stopPlayback()
                    else app.playSentences(PREVIEW, listOf(app.pack.templates.intro.first(), app.pack.items.first().sentences.first()))
                },
                voice = voice,
                onInstallVoice = { runCatching { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                onRecheckVoice = { app.speech.recheck() },
                learnedCount = learned.size, km = km,
                onReset = { lifecycleScope.launch { app.repo.reset() } },
                onShutdown = { ClassService.shutdown(this) },
                versionLabel = "세대차 ${BuildConfig.VERSION_NAME} (" + (if (BuildConfig.DEMO_ENABLED) "출품 빌드" else "서비스 빌드") + ")",
                onHome = { go(Screen.HOME) },
            )
        }
    }

    // ------------------------------------------------------------------ 도우미

    private fun onReviewAnswer(a: Arrival?, q: ReviewQuestion, right: Boolean) {
        if (a == null) return
        if (a.sample) {
            sampleReplay = if (right) sampleReplay - q.word.item.id else sampleReplay + q.word.item.id
            return
        }
        lifecycleScope.launch { app.repo.onReviewAnswer(q.word.item.id, right) }
    }

    private fun replayFinished(a: Arrival, wordId: String) {
        if (a.sample) sampleReplay = sampleReplay - wordId
        else lifecycleScope.launch { app.repo.onReplayFinished(wordId) }
    }

    private fun bookEntries(learned: List<LearnedWordEntity>): List<BookEntry> =
        learned.mapNotNull { l -> app.pack.word(l.wordId)?.let { BookEntry(l, it) } }

    private fun lastClassUi(c: ClassLogEntity): LastClassUi? {
        val words = parseWords(c.heard).map { (item, mode) -> if (mode == WordMode.THEN) item.pair else item.word }
        if (words.isEmpty()) return null
        return LastClassUi("${whenLabel(c.endedAt)} ${c.route}에 들은 말", words, "class:${c.id}")
    }

    private fun lastClassSentences(c: ClassLogEntity): List<String> =
        parseWords(c.heard).flatMap { (item, mode) -> item.sentences(mode) }

    private fun parseWords(csv: String) = csv.split(",").filter { it.isNotBlank() }.mapNotNull { s ->
        val item = app.pack.word(s.substringBefore(":")) ?: return@mapNotNull null
        item to (if (s.substringAfter(":") == "THEN") WordMode.THEN else WordMode.NOW)
    }

    /** "오전 8시" */
    private fun clockLabel(): String {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY)
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "${if (h < 12) "오전" else "오후"} ${h12}시"
    }

    private fun requestRuntimePermissions() {
        val wanted = buildList {
            add("android.car.permission.CAR_SPEED")
            add("android.car.permission.CAR_POWERTRAIN")
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }

    private companion object { const val PREVIEW = "preview" }
}

package com.kumhoht.sedaecha

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.kumhoht.sedaecha.audio.SentencePlayback
import com.kumhoht.sedaecha.audio.SpeechPlayer
import com.kumhoht.sedaecha.core.ClassPlanner
import com.kumhoht.sedaecha.core.ClassSummary
import com.kumhoht.sedaecha.core.DemoDriveLog
import com.kumhoht.sedaecha.core.Direction
import com.kumhoht.sedaecha.core.Gear
import com.kumhoht.sedaecha.core.Rules
import com.kumhoht.sedaecha.core.Turn
import com.kumhoht.sedaecha.core.content.ContentPack
import com.kumhoht.sedaecha.data.ProgressRepository
import com.kumhoht.sedaecha.data.SedaeDatabase
import com.kumhoht.sedaecha.demo.DemoController
import com.kumhoht.sedaecha.service.ClassRunner
import com.kumhoht.sedaecha.vehicle.LiveVehicle
import com.kumhoht.sedaecha.vehicle.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 도착 화면에 보여 줄 클래스 결과 */
data class Arrival(
    val summary: ClassSummary,
    val endedAtWall: Long,
    val minutes: Int,
    val km: Double,
    /** 심사용 데모 결과 ('샘플' 표시, 저장 안 함) */
    val sample: Boolean,
    val id: Long = System.nanoTime(),
)

/** 앱 전체에서 하나씩만 쓰는 객체 */
class SedaeApp : Application() {
    /** 백그라운드 작업 (DB) */
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 엔진·TTS 는 메인 스레드 */
    val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var pack: ContentPack private set
    lateinit var demoLog: DemoDriveLog private set
    lateinit var planner: ClassPlanner private set
    lateinit var repo: ProgressRepository private set
    lateinit var telemetry: Telemetry private set
    lateinit var speech: SpeechPlayer private set
    lateinit var runner: ClassRunner private set
    lateinit var demo: DemoController private set
    val prefs by lazy { getSharedPreferences("sedaecha", MODE_PRIVATE) }

    /** 도착 화면 (실제 클래스·데모). 새 값이 오면 화면이 도착 화면으로 바뀐다 */
    private val _arrival = MutableStateFlow<Arrival?>(null)
    val arrival: StateFlow<Arrival?> = _arrival.asStateFlow()
    fun showArrival(a: Arrival) { _arrival.value = a }

    // ------------------------------------------------------------------ 설정

    private val _demoMode = MutableStateFlow(false)
    /** 클래스 편성: 기본 모드 / 데모 모드 (출품 빌드는 데모 모드가 기본) */
    val demoMode: StateFlow<Boolean> = _demoMode.asStateFlow()
    fun setDemoMode(v: Boolean) {
        _demoMode.value = v
        prefs.edit().putBoolean("demoMode", v).apply()
        runner.rules = Rules.of(v)
    }
    val rules: Rules get() = Rules.of(_demoMode.value)

    private val _speechRate = MutableStateFlow(Rules.DEFAULT_SPEECH_RATE)
    val speechRate: StateFlow<Float> = _speechRate.asStateFlow()
    fun setSpeechRate(v: Float) {
        _speechRate.value = v
        speech.speechRate = v
        prefs.edit().putFloat("speechRate", v).apply()
    }

    private val _direction = MutableStateFlow(Direction.NOW)
    /** 배우는 방향 (고르지 않으면 마지막 선택) */
    val direction: StateFlow<Direction> = _direction.asStateFlow()
    fun setDirection(d: Direction) {
        _direction.value = d
        prefs.edit().putString("direction", d.key).apply()
    }

    // ------------------------------------------------------------------ 주차 중 다시 듣기

    private var playback: SentencePlayback? = null
    private val _playingKey = MutableStateFlow<String?>(null)
    /** 지금 다시 듣기 중인 항목 (버튼 표시용) */
    val playingKey: StateFlow<String?> = _playingKey.asStateFlow()

    fun playSentences(key: String, sentences: List<String>, onFinished: () -> Unit = {}) {
        stopPlayback()
        if (sentences.isEmpty()) return
        _playingKey.value = key
        lateinit var p: SentencePlayback
        p = SentencePlayback(speech, sentences, pack.tts.sentenceGapMs) { finished ->
            if (playback === p) { playback = null; _playingKey.value = null }
            if (finished) onFinished()
        }
        playback = p
        p.start()
    }

    fun stopPlayback() {
        playback?.cancel()
        playback = null
        _playingKey.value = null
    }

    override fun onCreate() {
        super.onCreate()
        pack = ContentPack.parse(assets.open(CONTENT_PACK).bufferedReader().use { it.readText() })
        demoLog = DemoDriveLog.parse(assets.open(DEMO_LOG).bufferedReader().use { it.readText() })
        planner = ClassPlanner(pack)
        repo = ProgressRepository(SedaeDatabase.create(this), prefs)
        _demoMode.value = prefs.getBoolean("demoMode", BuildConfig.DEMO_ENABLED)
        _speechRate.value = prefs.getFloat("speechRate", pack.tts.speechRate)
        _direction.value = Direction.of(prefs.getString("direction", null)) ?: Direction.NOW
        telemetry = Telemetry(this).also { it.start() }
        speech = SpeechPlayer(this).also { it.speechRate = _speechRate.value }
        runner = ClassRunner(this)
        demo = DemoController(this)
        io.launch { repo.recoverInterruptedClass() }
        if (BuildConfig.DEBUG) registerDebugSim()
    }

    /**
     * 디버그 빌드 전용: adb 로 차량 신호 흉내 (Virtual Console 없이 시험할 때)
     *   adb shell am broadcast -a com.kumhoht.sedaecha.SIM --es gear D --ef speed 40 [--es turn R]
     *   adb shell am broadcast -a com.kumhoht.sedaecha.SIM --ez clear true   (실제 신호로 돌아가기)
     */
    private fun registerDebugSim() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getBooleanExtra("clear", false)) { telemetry.override = null; Log.i("DebugSim", "clear"); return }
                val base = telemetry.override ?: telemetry.live.value
                val v = LiveVehicle(
                    gear = i.getStringExtra("gear")?.let { Gear.valueOf(it) } ?: base.gear,
                    speedKmh = if (i.hasExtra("speed")) i.getFloatExtra("speed", 0f) else base.speedKmh,
                    turn = if (i.hasExtra("turn")) when (i.getStringExtra("turn")) { "L" -> Turn.LEFT; "R" -> Turn.RIGHT; else -> Turn.OFF } else base.turn,
                    odometerKm = base.odometerKm,
                )
                telemetry.override = v
                Log.i("DebugSim", "$v")
            }
        }
        ContextCompat.registerReceiver(this, r, IntentFilter("com.kumhoht.sedaecha.SIM"), ContextCompat.RECEIVER_EXPORTED)
    }

    companion object {
        const val CONTENT_PACK = "sedae_radio_content_pack_v0.5.json"
        const val DEMO_LOG = "sedae_demo_drive_log_v2.json"
        fun of(c: Context) = c.applicationContext as SedaeApp
    }
}

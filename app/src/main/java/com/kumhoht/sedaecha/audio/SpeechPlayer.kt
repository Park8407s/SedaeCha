package com.kumhoht.sedaecha.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.kumhoht.sedaecha.core.Rules
import com.kumhoht.sedaecha.core.Speaker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** 한국어 음성 상태 (설정 화면 '음성 상태', 홈 안내) */
enum class VoiceStatus(val label: String) {
    CHECKING("확인 중"),
    AVAILABLE("사용 가능"),
    MISSING_DATA("음성 데이터 없음"),
    NOT_SUPPORTED("한국어 미지원"),
    NO_ENGINE("음성 엔진 없음"),
    /** 기기에 한국어 TTS 가 없어 내장 음성 파일(assets/voice)로 읽는 중 */
    FILES("내장 음성 사용 중");

    val usable: Boolean get() = this == AVAILABLE || this == FILES
}

/** 소리를 받는 쪽 (실제 클래스 엔진, 데모 엔진, 주차 중 다시 듣기) */
interface SpeechClient {
    fun onSpeechDone(id: String)
    fun onFocusLost(permanent: Boolean) {}
    fun onFocusGained() {}
}

/**
 * Speech Player: android.speech.tts.TextToSpeech (ko-KR) 로 문장 하나씩 읽는다 (개발 명세 7).
 * - 문장마다 utteranceId 를 붙여 끝나면 그 문장을 말한 쪽(client)에 알린다. 문장 사이 0.4초는 엔진이 둔다.
 * - 코너 시작 시 AUDIOFOCUS_GAIN_TRANSIENT(USAGE_MEDIA) 를 요청하고 코너가 끝나면 반납한다.
 *   포커스 손실(LOSS·LOSS_TRANSIENT·LOSS_TRANSIENT_CAN_DUCK)은 모두 일시 정지 (음량만 줄이지 않는다).
 * - 대비책(음성 파일 모드)이 필요해지면 이 클래스의 speak/stop 만 MediaPlayer 로 바꾸면 된다.
 *
 * 콜백은 모두 메인 스레드로 보낸다.
 */
class SpeechPlayer(context: Context) : Speaker {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val _status = MutableStateFlow(VoiceStatus.CHECKING)
    val status: StateFlow<VoiceStatus> = _status.asStateFlow()

    /** 지금 소리를 쓰는 쪽. 발화 완료·포커스 변화가 여기로 간다 */
    @Volatile var client: SpeechClient? = null

    var speechRate: Float = Rules.DEFAULT_SPEECH_RATE
        set(v) { field = v; tts?.setSpeechRate(v) }

    private var tts: TextToSpeech? = null
    private var ready = false
    private var focusRequest: AudioFocusRequest? = null
    private var focused = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.i(TAG, "focus change $change")
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> if (!focused) { focused = true; client?.onFocusGained() }
            AudioManager.AUDIOFOCUS_LOSS -> { focused = false; tts?.stop(); client?.onFocusLost(permanent = true) }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                focused = false; tts?.stop(); client?.onFocusLost(permanent = false)
            }
        }
    }

    init { start() }

    // ---------------------------------------------------------------- 음성 파일 모드 (대비책)

    /** 문장 → assets/voice 파일 (tools/gen_voice.ps1 로 만든 목록). 없으면 빈 맵 */
    private val voiceIndex: Map<String, String> by lazy {
        runCatching {
            val o = org.json.JSONObject(app.assets.open("voice/index.json").bufferedReader().use { it.readText() })
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrElse { emptyMap() }
    }
    private var media: MediaPlayer? = null
    private val fileMode: Boolean get() = _status.value == VoiceStatus.FILES

    /** 기기 TTS 를 쓸 수 없을 때: 내장 음성 파일이 있으면 그것으로 */
    private fun fallback(status: VoiceStatus) {
        _status.value = if (voiceIndex.isNotEmpty()) VoiceStatus.FILES else status
        Log.i(TAG, "TTS 사용 불가($status) → ${_status.value} (내장 문장 ${voiceIndex.size}개)")
    }

    private fun playFile(id: String, text: String) {
        releaseMedia()
        val name = voiceIndex[text.trim()]
        if (name == null) {
            Log.w(TAG, "내장 음성 없음: $text")
            main.postDelayed({ client?.onSpeechDone(id) }, 300)
            return
        }
        try {
            val mp = MediaPlayer()
            media = mp
            app.assets.openFd("voice/$name").use { fd -> mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
            mp.setAudioAttributes(attrs)
            mp.setOnCompletionListener { if (media === it) { releaseMedia(); client?.onSpeechDone(id) } }
            mp.setOnErrorListener { p, what, _ -> Log.w(TAG, "media error $what"); if (media === p) { releaseMedia(); client?.onSpeechDone(id) }; true }
            mp.prepare()
            // 말하기 속도: 보통(0.95) = 원래 속도
            runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speechRate / Rules.DEFAULT_SPEECH_RATE) }
            mp.start()
        } catch (t: Throwable) {
            Log.w(TAG, "음성 파일 재생 실패: ${t.message}")
            releaseMedia()
            main.postDelayed({ client?.onSpeechDone(id) }, 300)
        }
    }

    private fun releaseMedia() {
        media?.let { runCatching { it.stop() }; it.release() }
        media = null
    }

    private fun start() {
        _status.value = VoiceStatus.CHECKING
        tts = TextToSpeech(app) { result ->
            main.post {
                if (result != TextToSpeech.SUCCESS) { Log.w(TAG, "TTS init failed $result"); fallback(VoiceStatus.NO_ENGINE); return@post }
                val t = tts ?: return@post
                val lang = t.setLanguage(Locale.KOREA)
                when (lang) {
                    TextToSpeech.LANG_MISSING_DATA -> { fallback(VoiceStatus.MISSING_DATA); return@post }
                    TextToSpeech.LANG_NOT_SUPPORTED -> { fallback(VoiceStatus.NOT_SUPPORTED); return@post }
                    else -> _status.value = VoiceStatus.AVAILABLE
                }
                Log.i(TAG, "TTS ready lang=$lang engine=${t.defaultEngine} status=${_status.value}")
                t.setAudioAttributes(attrs)
                t.setSpeechRate(speechRate)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { if (id != null) main.post { client?.onSpeechDone(id) } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { if (id != null) main.post { client?.onSpeechDone(id) } }
                    override fun onError(id: String?, errorCode: Int) { Log.w(TAG, "tts error $errorCode"); if (id != null) main.post { client?.onSpeechDone(id) } }
                    override fun onStop(id: String?, interrupted: Boolean) {}
                })
                ready = true
            }
        }
    }

    /** 음성 데이터를 설치한 뒤 다시 확인 */
    fun recheck() {
        runCatching { tts?.shutdown() }
        ready = false
        start()
    }

    override fun speak(id: String, text: String) {
        Log.i(TAG, "말하기 [$id] $text")
        if (fileMode) { playFile(id, text); return }
        val t = tts
        if (t == null || !ready) { main.postDelayed({ client?.onSpeechDone(id) }, 300); return }
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        val r = t.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
        if (r != TextToSpeech.SUCCESS) main.postDelayed({ client?.onSpeechDone(id) }, 300)
    }

    override fun stop() { tts?.stop(); releaseMedia() }

    override fun requestFocus(): Boolean {
        if (focused) return true
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(true)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(focusListener, main)
            .build().also { focusRequest = it }
        focused = audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.i(TAG, "requestFocus granted=$focused")
        return focused
    }

    override fun abandonFocus() {
        focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        focused = false
    }

    fun shutdown() {
        stop(); abandonFocus()
        runCatching { tts?.shutdown() }
        tts = null; ready = false
    }

    private companion object { const val TAG = "SpeechPlayer" }
}

/**
 * 주차 중 다시 듣기·미리 듣기: 문장 목록을 0.4초 간격으로 읽는다 (포커스 포함).
 * 끝까지 들으면 onComplete(true), 중간에 멈추면 onComplete(false).
 */
class SentencePlayback(
    private val player: SpeechPlayer,
    private val sentences: List<String>,
    private val gapMs: Long,
    private val onComplete: (finished: Boolean) -> Unit,
) : SpeechClient {
    private val main = Handler(Looper.getMainLooper())
    private var idx = 0
    private var active = false
    private val tag = "p${System.nanoTime()}_"

    fun start() {
        if (!player.requestFocus()) { onComplete(false); return }
        player.client = this
        active = true
        speakCurrent()
    }

    fun cancel() {
        if (!active) return
        active = false
        main.removeCallbacksAndMessages(null)
        player.stop(); player.abandonFocus()
        if (player.client === this) player.client = null
        onComplete(false)
    }

    private fun speakCurrent() { player.speak("$tag$idx", sentences[idx]) }

    override fun onSpeechDone(id: String) {
        if (!active || id != "$tag$idx") return
        idx++
        if (idx >= sentences.size) {
            active = false
            player.abandonFocus()
            if (player.client === this) player.client = null
            onComplete(true)
        } else main.postDelayed({ if (active) speakCurrent() }, gapMs)
    }

    override fun onFocusLost(permanent: Boolean) { cancel() }
}

package com.kumhoht.sedaecha.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.kumhoht.sedaecha.R
import com.kumhoht.sedaecha.SedaeApp
import com.kumhoht.sedaecha.core.ClassEngine
import com.kumhoht.sedaecha.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 주행 클래스 Foreground Service (개발 명세 2).
 * 앱을 한 번 실행하면 백그라운드에서 기어 D 를 기다렸다가 클래스를 진행한다. 시동(부팅) 때 저절로 켜지지 않는다.
 * 알림은 소리·진동 없는 상태 알림만 쓴다 (주행 중 팝업·알림 없음).
 */
class ClassService : Service() {
    private val app by lazy { SedaeApp.of(this) }
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "세대차 클래스", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) }
        )
        ServiceCompat.startForeground(this, NOTIF_ID, notification(false), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        app.runner.start()
        watch = app.main.launch {
            app.runner.state.collect { s ->
                nm.notify(NOTIF_ID, notification(s == ClassEngine.State.DRIVING || s == ClassEngine.State.PAUSED))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        watch?.cancel()
        app.runner.stop()
        super.onDestroy()
    }

    private fun notification(inClass: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (inClass) "세대차 클래스 진행 중" else "세대차")
            .setContentText(if (inClass) "운전에 맞춰 소리로만 진행해요" else "기어를 D로 바꾸면 클래스가 시작돼요")
            .setOngoing(true).setSilent(true).setContentIntent(open)
            .build()
    }

    companion object {
        private const val TAG = "ClassService"
        private const val CHANNEL = "class"
        private const val NOTIF_ID = 1

        fun start(c: Context) = runCatching { c.startForegroundService(Intent(c, ClassService::class.java)) }
            .onFailure { Log.w(TAG, "서비스 시작 실패: ${it.message}") }

        /**
         * 앱 끄기 (설정, 주차 중에만): TTS·차량 데이터 수신을 멈추고 서비스를 끝낸 뒤 앱을 닫는다 (학습 기록은 유지).
         * 다음에 앱 아이콘으로 실행하면 다시 동작한다.
         */
        fun shutdown(activity: Activity) {
            Log.i(TAG, "앱 끄기")
            val app = SedaeApp.of(activity)
            app.demo.stop()
            app.stopPlayback()
            app.runner.stop()
            activity.stopService(Intent(activity, ClassService::class.java))
            app.telemetry.stop()
            app.speech.shutdown()
            activity.finishAndRemoveTask()
            Handler(Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 400)
        }
    }
}

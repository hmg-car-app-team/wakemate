package com.hmgcarapp.wakemate.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.wakemate.domain.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 화면 밖에서도 졸음 감시(차속·조향 등 차량 신호)를 이어가는 비위치 유형(specialUse) 포그라운드 서비스.
 * 로직은 갖지 않고 프로세스를 유지할 뿐이다(컨트롤러는 [WakeRuntime] 이 소유). 위치를 구독하지 않으며,
 * 화면을 띄우지 않는다(알림의 '열기'는 사용자가 누를 때만). 외부에서 시작할 수 없다(exported=false).
 * 알림 '끄기'는 설정 스위치와 같은 경로([AppController.disableBackgroundWatch])로 끈다.
 */
class WatchService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var userReceiver: BroadcastReceiver? = null
    private val recheck = object : Runnable {
        override fun run() { if (WakeRuntime.stillAllowed(this@WatchService)) handler.postDelayed(this, RECHECK_MS) else stopSelf() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        WakeRuntime.serviceCreated(this)
        val c = WakeRuntime.acquire(this)
        scope.launch { c.state.map { it.risk?.stage }.distinctUntilChanged().collect { refreshNotification(it) } }
        // 사용자 전환으로 뒤로 가면 즉시 정지(시스템이 프로세스를 끝내기도 하지만 스스로도 멈춘다)
        val r = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { HmgLog.i("Service", "사용자 전환 -> 정지"); stopSelf() } }
        if (BackgroundWatch.attempt("Service") { ContextCompat.registerReceiver(this, r, IntentFilter("android.intent.action.USER_BACKGROUND"), ContextCompat.RECEIVER_NOT_EXPORTED) }) userReceiver = r
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TURN_OFF) {
            WakeRuntime.current()?.disableBackgroundWatch() // 설정 스위치와 같은 경로(동의 철회 포함)
            stopSelf()
            return START_NOT_STICKY
        }
        // startForegroundService 로 시작됐으므로 먼저 startForeground 를 호출해야 한다(그 뒤에 조건을 확인해 멈춘다).
        val ok = BackgroundWatch.attempt("Service") {
            val n = buildNotification(WakeRuntime.current()?.state?.value?.risk?.stage)
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NOTIF_ID, n)
        }
        if (!ok || !WakeRuntime.stillAllowed(this)) { stopSelf(); return START_NOT_STICKY } // 실패 시 재시도 없음
        handler.removeCallbacks(recheck); handler.postDelayed(recheck, RECHECK_MS)
        return START_NOT_STICKY // 죽으면 자동 재시작 없음(부팅 수신기 또는 앱 실행 때만 다시 시작)
    }

    private fun refreshNotification(stage: Stage?) {
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification(stage)) }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        userReceiver?.let { runCatching { unregisterReceiver(it) } }
        WakeRuntime.serviceDestroyed()
        super.onDestroy()
    }

    /** 색은 지정하지 않는다(setColor·커스텀 뷰 없음): 시스템 기본 스타일을 써야 어두운 쉐이드에서도 본문이 보인다. */
    private fun buildNotification(stage: Stage?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm?.createNotificationChannel(NotificationChannel(CHANNEL, "졸음 감시", NotificationManager.IMPORTANCE_LOW))
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val off = PendingIntent.getService(this, 1, Intent(this, WatchService::class.java).setAction(ACTION_TURN_OFF), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let { PendingIntent.getActivity(this, 2, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        b.setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(BackgroundWatch.NOTIF_TITLE).setContentText(BackgroundWatch.notificationBody(stage))
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
        open?.let { b.setContentIntent(it) }
        if (Build.VERSION.SDK_INT >= 23) {
            b.addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), BackgroundWatch.ACTION_LABEL_OFF, off).build())
            open?.let { b.addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_view), BackgroundWatch.ACTION_LABEL_OPEN, it).build()) }
        }
        return b.build()
    }

    companion object {
        const val CHANNEL = "wakemate_watch"
        const val NOTIF_ID = 2001
        const val ACTION_TURN_OFF = "com.hmgcarapp.wakemate.TURN_OFF_WATCH"
        const val RECHECK_MS = 60_000L

        fun start(context: Context) {
            BackgroundWatch.attempt("Service") {
                val i = Intent(context, WatchService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            }
        }

        fun stop(context: Context) { BackgroundWatch.attempt("Service") { context.stopService(Intent(context, WatchService::class.java)) } }
    }
}

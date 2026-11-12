package com.hmgcarapp.wakemate.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import com.hmgcarapp.core.CarSignals
import com.hmgcarapp.core.log.HmgLog

/**
 * 프로세스 수명의 컨트롤러 소유자. 화면(Activity)이 없어도 [WatchService] 가 떠 있으면 컨트롤러(감시 로직)는 유지되고,
 * 화면도 서비스도 없으면 해제한다. 모든 호출은 메인 스레드. 위치는 다루지 않는다(위치 구독은 화면이 보일 때만 컨트롤러가 한다).
 */
object WakeRuntime {
    private var controller: AppController? = null
    private var appContext: Context? = null
    private var activities = 0
    private var service: WatchService? = null

    fun acquire(context: Context): AppController {
        val app = context.applicationContext
        appContext = app
        return controller ?: AppController(app, CarSignals.create(app), serviceControl = ::applyService).also { controller = it }
    }

    fun current(): AppController? = controller
    fun activityCreated() { activities++ }
    fun activityDestroyed() { activities = (activities - 1).coerceAtLeast(0); releaseIfIdle() }
    fun serviceCreated(s: WatchService) { service = s }
    fun serviceDestroyed() { service = null; releaseIfIdle() }

    /** 환경(사용자·권한) 입력. 설정 값은 호출자가 채운다. */
    fun env(context: Context): Triple<Boolean, Boolean, Boolean> {
        val um = context.getSystemService(UserManager::class.java)
        val unlocked = um?.isUserUnlocked ?: false
        val fg = if (Build.VERSION.SDK_INT >= 31) um?.isUserForeground ?: false else true
        val speed = context.checkSelfPermission("android.car.permission.CAR_SPEED") == PackageManager.PERMISSION_GRANTED
        return Triple(unlocked, fg, speed)
    }

    fun decide(context: Context, s: Settings): BackgroundWatch.Decision {
        val (u, f, p) = env(context)
        return BackgroundWatch.decide(BackgroundWatch.Inputs(s.backgroundWatch, s.backgroundConsentVer, u, f, p, notificationsGranted(context)))
    }

    /** 알림 권한(API 33+). 낮은 버전은 기본 허용. 알림이 막혀 있으면 false. */
    fun notificationsGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return false
        return runCatching { context.getSystemService(android.app.NotificationManager::class.java)?.areNotificationsEnabled() ?: true }.getOrDefault(true)
    }

    /** 설정이 바뀌었거나 복원되었을 때: 조건을 만족하면 서비스 시작, 아니면 정지. 실패는 [BackgroundWatch.attempt] 가 삼킨다. */
    fun applyService(s: Settings) {
        val ctx = appContext ?: return
        if (decide(ctx, s) == BackgroundWatch.Decision.START) { if (service == null) WatchService.start(ctx) }
        else WatchService.stop(ctx)
    }

    /** 서비스가 주기적으로 묻는다: 아직 계속해도 되는가(설정 복원 전에는 환경 조건만 본다). */
    fun stillAllowed(context: Context): Boolean {
        val c = controller
        val st = c?.state?.value
        val s = if (st != null && !st.loading) st.p.settings else Settings(backgroundWatch = true, backgroundConsentVer = BackgroundWatch.NOTICE_VERSION)
        val d = decide(context, s)
        if (d != BackgroundWatch.Decision.START) HmgLog.i("Runtime", "백그라운드 감시 중단 사유: $d")
        return d == BackgroundWatch.Decision.START
    }

    private fun releaseIfIdle() {
        if (activities == 0 && service == null) {
            controller?.let { HmgLog.i("Runtime", "화면·서비스 없음 -> 컨트롤러 해제"); it.release() }
            controller = null
        }
    }
}

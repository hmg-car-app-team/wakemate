package com.hmgcarapp.wakemate.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.core.store.LocalJsonStore

/**
 * 기기 부팅 완료(잠금 해제 후 BOOT_COMPLETED) 때, 사용자가 '백그라운드 감시'를 동의하고 켜 둔 경우에만 [WatchService] 를 시작한다.
 * 기본값은 꺼짐이라 아무 것도 하지 않는다. 시작 실패(SecurityException 등)는 모두 삼키고 로그만 남기며 재시도하지 않는다.
 * 위치를 구독하지 않고 화면을 띄우지 않는다. LOCKED_BOOT_COMPLETED 는 받지 않는다(저장소 잠김 상태에서는 설정을 읽을 수 없다).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            val settings = StateStore.peekSettings(LocalJsonStore.forApp(context))
            val (unlocked, fg, speed) = WakeRuntime.env(context)
            val inputs = BackgroundWatch.settingsInputs(settings, unlocked, fg, speed, WakeRuntime.notificationsGranted(context))
            if (!BackgroundWatch.shouldStartOnBoot(intent?.action, inputs)) { HmgLog.i("Boot", "부팅 시작 안 함"); return }
            WatchService.start(context.applicationContext)
        } catch (t: Throwable) {
            HmgLog.w("Boot", "부팅 처리 실패(무동작): ${t.javaClass.simpleName}")
        }
    }
}

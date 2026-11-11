package com.hmgcarapp.wakemate.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.hmgcarapp.core.location.GeoPoint
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.wakemate.domain.SimInput

/**
 * adb 시연 주입 수신기(debuggable 전용, DUMP 권한 보유자=adb shell 만 전송 가능). 시스템 주도라 주행 가드 없음.
 *  패턴      --es pattern drowsy|normal|off      (조향·속도·기어 합성 신호; 졸음 패턴은 약 1~2분 뒤 지수에 반영)
 *  시각      --ei hour 3 [--ei minute 30]  (--ei hour -1 로 해제)
 *  주행·내기 --ei drivemin 90   --ei recircmin 30
 *  신호      --ef speed 80  --es gear D|P  --ef steer 7.5  --ef lat 37.5 --ef lon 127.03 [--ef heading 90(0~360, lat/lon 과 함께)]  --ez hub true|false
 *  단계      --ei stage 1|2|3     --es heard "응"     --ez rest true     --ez reset true
 */
internal object SimReceiver {
    const val ACTION = "com.hmgcarapp.wakemate.SIM"
    const val PERMISSION = "android.permission.DUMP"
    const val MIN_INTERVAL_MS = 300L

    fun register(context: Context, c: AppController, monoClock: () -> Long): BroadcastReceiver {
        var lastKey: String? = null; var lastAt = 0L
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                val ex = i.extras
                val key = ex?.keySet()?.sorted()?.joinToString(",") ?: ""
                val now = monoClock()
                if (key == lastKey && now - lastAt in 0 until MIN_INTERVAL_MS) { HmgLog.i("WakeMate", "SIM 무시: ${MIN_INTERVAL_MS}ms 이내 재수신"); return }
                lastKey = key; lastAt = now
                fun raw(k: String): Any? = ex?.get(k)
                fun bad(k: String) = HmgLog.w("WakeMate", "SIM 무시: $k=${raw(k)} (형식 또는 범위 오류)")
                if (i.getBooleanExtra("reset", false)) c.simReset()
                if (i.hasExtra("pattern")) {
                    val s = i.getStringExtra("pattern")
                    when { SimInput.isOff(s) -> c.simPattern(null); SimInput.pattern(s) != null -> c.simPattern(SimInput.pattern(s)); else -> bad("pattern") }
                }
                if (i.hasExtra("hour")) {
                    if ((SimInput.number(raw("hour")) ?: 0.0) < 0) c.simMinute(null)
                    else SimInput.minuteOfDay(raw("hour"), raw("minute"))?.let { c.simMinute(it) } ?: bad("hour")
                }
                if (i.hasExtra("drivemin")) SimInput.minutes(raw("drivemin"))?.let { c.simAddDrive(it) } ?: bad("drivemin")
                if (i.hasExtra("recircmin")) SimInput.minutes(raw("recircmin"))?.let { c.simRecirc(it) } ?: bad("recircmin")
                val lat = SimInput.number(raw("lat")); val lon = SimInput.number(raw("lon"))
                val heading = if (i.hasExtra("heading")) SimInput.heading(raw("heading")).also { if (it == null) bad("heading") } else null
                val loc = if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) GeoPoint(lat, lon, heading) else null
                if (heading != null && loc == null) HmgLog.w("WakeMate", "SIM 무시: heading 은 lat/lon 과 함께 보내야 함")
                if ((i.hasExtra("lat") || i.hasExtra("lon")) && loc == null) bad("lat/lon")
                val enabled = if (i.hasExtra("hub")) i.getBooleanExtra("hub", false) else null
                val speed = if (i.hasExtra("speed")) SimInput.speed(raw("speed")).also { if (it == null) bad("speed") } else null
                val gear = if (i.hasExtra("gear")) SimInput.gear(i.getStringExtra("gear")).also { if (it == null) bad("gear") } else null
                // 신호를 주입하면 허브를 켠다(꺼 둔 채 값만 넣으면 효과가 없어 혼동되므로)
                c.simHub(enabled ?: if (speed != null || gear != null || loc != null) true else null, speed, gear, loc)
                if (i.hasExtra("steer")) SimInput.steer(raw("steer"))?.let { c.simSteer(it) } ?: bad("steer")
                if (i.hasExtra("stage")) SimInput.stage(raw("stage"))?.let { c.simStage(it) } ?: bad("stage")
                if (i.hasExtra("heard")) i.getStringExtra("heard")?.let { c.simHeard(it) }
                if (i.getBooleanExtra("rest", false)) c.simRest()
                HmgLog.i("WakeMate", "SIM 수신: ${ex?.keySet()}")
            }
        }
        val f = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, f, PERMISSION, null, Context.RECEIVER_EXPORTED) else context.registerReceiver(r, f, PERMISSION, null)
        return r
    }
}

package com.hmgcarapp.core.sim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.SystemClock
import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.core.drive.Gear
import com.hmgcarapp.core.location.GeoPoint
import com.hmgcarapp.core.log.HmgLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 시뮬레이션 값 묶음. null 인 항목은 실신호를 그대로 쓴다(부분 override). */
data class SimState(
    val enabled: Boolean = false,
    val speedKmh: Float? = null,
    val gear: Gear? = null,
    val location: GeoPoint? = null,
    val outsideTempC: Float? = null,
    val driveOverride: DriveState? = null,
)

/**
 * 시연 모드 공통 주입 훅.
 * 앱은 [com.hmgcarapp.core.CarSignals] 를 통해 실신호/시뮬레이션을 같은 인터페이스로 구독한다.
 * 프로세스 내 싱글턴 [SimulationHub.shared] 를 기본으로 쓰되 테스트에서는 새 인스턴스를 만들 수 있다.
 *
 * adb 로도 주입 가능(앱 실행 중, [registerAdbReceiver] 로 등록된 경우):
 *   adb shell am broadcast -a com.hmgcarapp.SIM --ez enabled true --ef speed 80 --es gear D --ef lat 37.5 --ef lon 127.03
 *   adb shell am broadcast -a com.hmgcarapp.SIM --ez enabled false
 */
class SimulationHub {
    private val _state = MutableStateFlow(SimState())
    val state: StateFlow<SimState> = _state.asStateFlow()

    fun setEnabled(on: Boolean) = _state.update { it.copy(enabled = on) }
    fun setSpeedKmh(v: Float?) = _state.update { it.copy(speedKmh = v) }
    fun setGear(g: Gear?) = _state.update { it.copy(gear = g) }
    fun setLocation(p: GeoPoint?) = _state.update { it.copy(location = p) }
    fun setOutsideTempC(v: Float?) = _state.update { it.copy(outsideTempC = v) }
    fun setDriveOverride(s: DriveState?) = _state.update { it.copy(driveOverride = s) }
    fun reset() { _state.value = SimState() }

    private var receiver: BroadcastReceiver? = null

    /**
     * adb broadcast 로 시뮬레이션 주입을 받는다. 디버그/시연용. [Context] 수명에 맞춰 [unregisterAdbReceiver] 호출.
     * **앱이 debuggable 일 때만 등록**한다(release 에서는 no-op). 라이브러리의 BuildConfig.DEBUG 는 앱 빌드타입을
     * 반영하지 않으므로 런타임에 앱의 FLAG_DEBUGGABLE 로 판단한다. 등록했으면 true.
     */
    fun registerAdbReceiver(context: Context): Boolean {
        if (receiver != null) return true
        if (!isDebuggable(context)) return false
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { applyIntentDebounced(i) }
        }
        val f = IntentFilter(ACTION)
        // broadcastPermission=DUMP: adb shell 은 보유, 일반 앱은 미보유 -> 다른 앱의 브로드캐스트 차단
        val flags = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0
        context.applicationContext.registerReceiver(r, f, SENDER_PERMISSION, null, flags)
        receiver = r
        appCtx = context.applicationContext
        return true
    }

    private var appCtx: Context? = null

    fun unregisterAdbReceiver() {
        receiver?.let { r -> runCatching { appCtx?.unregisterReceiver(r) } }
        receiver = null
    }

    private var lastAction: String? = null
    private var lastAtMs = 0L

    private fun applyIntentDebounced(i: Intent) {
        val now = SystemClock.elapsedRealtime()
        if (shouldIgnore(lastAction, lastAtMs, i.action, now)) { HmgLog.w("Sim", "디바운스로 무시: ${i.action}"); return }
        lastAction = i.action; lastAtMs = now
        applyIntent(i)
    }

    internal fun applyIntent(i: Intent) {
        // 모든 값은 검증 후 반영한다(범위 밖/NaN/알 수 없는 값은 무시).
        if (i.hasExtra("enabled")) setEnabled(i.getBooleanExtra("enabled", false))
        if (i.hasExtra("speed")) validSpeed(i.getFloatExtra("speed", Float.NaN))?.let { setSpeedKmh(it) }
        i.getStringExtra("gear")?.let { g -> parseGear(g)?.let { setGear(it) } }
        if (i.hasExtra("lat") && i.hasExtra("lon")) {
            validLatLon(i.getFloatExtra("lat", Float.NaN).toDouble(), i.getFloatExtra("lon", Float.NaN).toDouble())?.let { setLocation(it) }
        }
        if (i.hasExtra("temp")) validTemp(i.getFloatExtra("temp", Float.NaN))?.let { setOutsideTempC(it) }
        i.getStringExtra("drive")?.let { d -> parseDrive(d)?.let { setDriveOverride(it) } }
        HmgLog.i("Sim", "주입: ${state.value}")
    }

    companion object {
        const val ACTION = "com.hmgcarapp.SIM"
        const val SENDER_PERMISSION = "android.permission.DUMP"
        const val DEBOUNCE_MS = 300L

        /** 같은 액션이 [DEBOUNCE_MS] 이내 연속 도착하면 무시(순수 함수; 테스트용). */
        internal fun shouldIgnore(lastAction: String?, lastAtMs: Long, action: String?, nowMs: Long): Boolean =
            lastAction != null && lastAction == action && nowMs - lastAtMs in 0 until DEBOUNCE_MS
        val shared = SimulationHub()
        const val MAX_SPEED_KMH = 300f

        fun isDebuggable(context: Context): Boolean =
            (context.applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        fun validSpeed(v: Float): Float? = if (v.isFinite() && v >= 0f && v <= MAX_SPEED_KMH) v else null
        fun validTemp(v: Float): Float? = if (v.isFinite() && v >= -60f && v <= 80f) v else null
        fun validLatLon(lat: Double, lon: Double): GeoPoint? =
            if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) GeoPoint(lat, lon) else null
        fun parseDrive(s: String): DriveState? = when (s.uppercase()) {
            "PARKED" -> DriveState.PARKED
            "DRIVING" -> DriveState.DRIVING
            else -> null
        }

        fun parseGear(s: String): Gear? = when (s.uppercase()) {
            "P", "PARK" -> Gear.PARK
            "D", "DRIVE" -> Gear.DRIVE
            "R", "REVERSE" -> Gear.REVERSE
            "N", "NEUTRAL" -> Gear.NEUTRAL
            else -> null
        }
    }
}

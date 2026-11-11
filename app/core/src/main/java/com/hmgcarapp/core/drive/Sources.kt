package com.hmgcarapp.core.drive

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.content.Context
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.core.connection.ManagedConnection
import com.hmgcarapp.core.vehicle.ReflectiveCar
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** 기어 소스. */
interface GearSource {
    /** 현재 기어 + 변경 시 갱신. 실패 시 UNKNOWN 을 내보내고 계속 살아있다. */
    fun gear(): Flow<Gear>
}

/** 기어용 Vehicle 포트(테스트에서 가짜 주입). 콜백 람다는 항상 Unit. */
internal interface GearVehicle {
    fun initialize()
    fun queryCurrent(onGear: (Gear) -> Unit, onError: (Throwable) -> Unit)
    /** 반환: 해제용 핸들. */
    fun register(onGear: (Gear) -> Unit, onError: (Throwable) -> Unit): () -> Unit
    fun release()
}

/** 기어 오류 시 마지막 확인 기어(D/R/N)를 유지하는 시간(이후에도 복구 안 되면 UNKNOWN). 유지는 '주행 쪽 값' 이므로 fail-safe. */
internal const val GEAR_HOLD_MS = 30_000L
/**
 * 마지막 확인 기어가 PARK 일 때의 유지 시간. 비대칭인 이유: P 를 오래 붙들면 P→D 전환을 가려 '정차' 로 오판(위험 방향)하지만,
 * D/R/N 을 붙드는 것은 '주행' 으로 오판(안전 방향)이므로 길어도 된다. 5초 뒤 UNKNOWN 이면 DriveStateCalculator 가 속도·UX 로 다시 판정한다.
 */
internal const val GEAR_HOLD_PARK_MS = 5_000L

/** [GearHold.onError] 결과: 지금 내보낼 값, 새 오류 구간이면 그 id(진행 중이면 null), 그 값을 유지하는 최대 시간. */
internal data class GearErrorReport(val gear: Gear, val newEpisode: Long?, val windowMs: Long)

/** 기어 오류 구간 동안 마지막 확인 기어를 유지하는 순수 로직(시간 주입, 스레드 안전). 유지 시간은 기어에 따라 비대칭. */
internal class GearHold(private val holdMs: Long = GEAR_HOLD_MS, private val parkHoldMs: Long = GEAR_HOLD_PARK_MS) {
    private var last: Gear? = null
    private var errorSince: Long? = null
    private var episode = 0L          // 오류 구간 id(시각이 같아도 구간을 구분)
    private var retried = false
    fun windowFor(g: Gear?): Long = if (g == Gear.PARK) parkHoldMs else holdMs
    @Synchronized fun onGear(g: Gear) { last = g; errorSince = null; retried = false }
    /** 오류 기록 + 지금 내보낼 값 판정을 하나의 락에서(기록과 판정 사이에 다른 스레드가 끼어들지 못한다). */
    @Synchronized fun onError(now: Long): GearErrorReport {
        var started: Long? = null
        if (errorSince == null) { errorSince = now; episode++; started = episode }
        return GearErrorReport(resolveLocked(now), started, windowFor(last))
    }
    /** 지금 내보낼 값: 마지막 확인 기어가 유효하고 오류 후 그 기어의 유지 시간 이내면 그 기어, 아니면 UNKNOWN. */
    @Synchronized fun resolve(now: Long): Gear = resolveLocked(now)
    private fun resolveLocked(now: Long): Gear {
        val l = last; val s = errorSince
        return if (l != null && l != Gear.UNKNOWN && s != null && now - s < windowFor(l)) l else Gear.UNKNOWN
    }
    @Synchronized fun isErrorEpisode(id: Long) = errorSince != null && episode == id
    /** 비연결 오류 뒤 queryCurrent 재시도는 오류 구간당 1회. */
    @Synchronized fun tryRetry(): Boolean = if (retried) false else { retried = true; true }
}

/** 기어용 연결 한 개: Vehicle 과 구독 해제 핸들(해제 순서: unregister -> release). */
internal class GearLink(val vehicle: GearVehicle) {
    @Volatile var unregister: (() -> Unit)? = null
    fun close() { runCatching { unregister?.invoke() }; runCatching { vehicle.release() } }
}

/**
 * 기어 흐름. 연결·재연결·세대 필터·백오프(2->30초, 10초 안정 뒤 리셋)는 [ManagedConnection] 이 맡고,
 * 여기서는 오류 때 마지막 확인 기어를 유지하는 정책([GearHold])과 연결 후 질의/구독 순서만 둔다. 취소 시 해제.
 */
internal fun gearFlowOf(
    factory: () -> GearVehicle,
    sleep: suspend (Long) -> Unit = { delay(it) },
    nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
): Flow<Gear> = callbackFlow {
    val hold = GearHold()
    var expiry: kotlinx.coroutines.Job? = null
    /** 오류: UNKNOWN 을 바로 내보내지 않고 마지막 확인 기어를 기어별 시간만큼 유지, 만료 시에도 복구 안 됐으면 UNKNOWN. */
    fun reportError() {
        val r = hold.onError(nowMs())
        if (r.gear == Gear.UNKNOWN) { trySend(Gear.UNKNOWN); return }
        val id = r.newEpisode
        if (id != null) {
            expiry?.cancel()
            expiry = launch {
                delay(r.windowMs)
                if (hold.isErrorEpisode(id)) trySend(hold.resolve(nowMs()))
            }
        }
    }
    lateinit var conn: ManagedConnection<GearLink>
    conn = ManagedConnection(
        tag = "Gear",
        create = { GearLink(factory()) },
        open = { link, gen ->
            val v = link.vehicle
            v.initialize()
            val onGear: (Gear) -> Unit = conn.callback(gen) { g -> hold.onGear(g); trySend(g); Unit }
            lateinit var onError: (Throwable) -> Unit
            onError = conn.callback(gen) { e: Throwable ->
                HmgLog.w("Gear", "기어 오류: ${e.javaClass.simpleName}")
                reportError()
                // 연결 실패 계열이면 연결 교체, 아니면 현재 기어를 오류 구간당 1회 다시 질의
                if (!conn.onFailure(gen, e) && hold.tryRetry()) runCatching { v.queryCurrent(onGear, onError) }
                Unit
            }
            v.queryCurrent(onGear, onError)
            link.unregister = v.register(onGear, onError)
        },
        dispose = { it.close() },
        clock = nowMs,
        onCreateFailed = { t -> HmgLog.w("Gear", "Vehicle SDK 사용 불가: ${t.javaClass.simpleName}"); reportError() },
    )
    try { conn.supervise(sleep) } finally { expiry?.cancel() }
    awaitClose { }
}

/** Pleos Vehicle SDK 2.3.2 기반 기어 (permission: pleos.car.permission.CAR_DRIVE). */
class PleosGearSource(private val context: Context) : GearSource {
    /** 수집(collect)마다 연결을 새로 만든다(이전 수집의 상태가 새 수집을 흔들지 않도록). */
    override fun gear(): Flow<Gear> = flow {
        emitAll(gearFlowOf({ SdkGearVehicle(context.applicationContext) }))
    }

    private class SdkGearVehicle(ctx: Context) : GearVehicle {
        private val v = Vehicle(ctx)
        override fun initialize() { v.initialize() }
        override fun queryCurrent(onGear: (Gear) -> Unit, onError: (Throwable) -> Unit) {
            v.getGear().getCurrentGearSelectionState({ onGear(it.toGear()); Unit }, { e -> onError(e); Unit })
        }
        override fun register(onGear: (Gear) -> Unit, onError: (Throwable) -> Unit): () -> Unit {
            val l = object : GearSelectionListener {
                override fun onSelected(gear: VehicleGear?) { onGear(gear.toGear()); Unit }
                override fun onFailed(e: Exception) { onError(e); Unit }
            }
            val g = v.getGear()
            g.registerGearSelection(l)
            return { g.unregisterGearSelection(l); Unit }
        }
        override fun release() { v.release() }
    }

}

private fun VehicleGear?.toGear(): Gear = when (this) {
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        null, VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
        else -> Gear.DRIVE // GEAR_DRIVE, GEAR_1..9
}

/** CarUxRestrictions.requiresDistractionOptimization 소스. */
interface UxRestrictionSource {
    /** true=주행 제한 중, false=제한 없음, null=알 수 없음 */
    fun requiresDistractionOptimization(intervalMs: Long = 1000L): Flow<Boolean?>
}

/**
 * android.car CarUxRestrictionsManager 를 리플렉션으로 폴링한다 (core 가 android.car 라이브러리에 컴파일 의존하지 않도록).
 * 디자인시스템의 PleosUxRestrictionsProvider 는 UI 용이고, 이쪽은 비-UI(서비스) 용.
 */
class ReflectiveUxRestrictionSource(private val context: Context) : UxRestrictionSource {
    override fun requiresDistractionOptimization(intervalMs: Long): Flow<Boolean?> = flow {
        val conn = ReflectiveCar.connection("UxRestriction", context.applicationContext, "uxrestriction")
        conn.connect(force = true)
        try {
            while (true) {
                // 서비스 재시작 등으로 연결이 없으면 폴링 루프 안에서 간격(ConnectionPolicy.minAttemptGapMs)을 두고 재연결
                val c = conn.current() ?: conn.connect()
                val v: Boolean? = try {
                    val m = c?.value?.mgr
                    if (m == null) null else {
                        val r = m.javaClass.getMethod("getCurrentCarUxRestrictions").invoke(m)
                        r?.javaClass?.getMethod("isRequiresDistractionOptimization")?.invoke(r) as? Boolean
                    }
                } catch (t: Throwable) {
                    val e = ReflectiveCar.unwrap(t)
                    HmgLog.w("UxRestriction", "읽기 실패: $e")
                    if (c != null && conn.onFailure(c.gen, e)) conn.reap() // 서비스 사망: 매니저 폐기, 다음 주기에 재연결
                    null
                }
                emit(v)
                delay(intervalMs)
            }
        } finally {
            conn.shutdown()
        }
    }
}

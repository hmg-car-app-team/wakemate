package com.hmgcarapp.wakemate.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.capability.HvacTemperatureConfigArray
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import android.content.Context
import android.os.SystemClock
import com.hmgcarapp.core.connection.Conn
import com.hmgcarapp.core.connection.ConnectionPolicy
import com.hmgcarapp.core.connection.ManagedConnection
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.wakemate.domain.AreaPicker
import com.hmgcarapp.wakemate.domain.HvacCaps
import com.hmgcarapp.wakemate.domain.HvacErrors
import com.hmgcarapp.wakemate.domain.HvacNow
import com.hmgcarapp.wakemate.domain.HvacTargets
import com.hmgcarapp.wakemate.domain.ItemResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** 공조 상태 스냅샷. [connected] = 연결 + capability 응답 완료 + 쓰기/읽기 가능 항목이 하나 이상. */
data class HvacSnapshot(
    val connected: Boolean = false, val caps: HvacCaps = HvacCaps(), val now: HvacNow = HvacNow(), val error: String? = null,
    /** 연결이 확인되고 capability 응답이 끝났는지(미지원과 '아직 모름'을 구분). */
    val capsKnown: Boolean = false,
)

/** 공조 하드웨어 포트(컨트롤러가 의존하는 최소 인터페이스; 테스트는 가짜). 콜백은 SDK 스레드에서 올 수 있다. */
interface WakeHvac {
    var onState: (HvacSnapshot) -> Unit
    suspend fun run()
    fun retryNow()
    fun snapshot(): HvacSnapshot
    /** 쓰기 가능한 항목만 보낸다. 결과는 [onDone] 에 항목별로(응답 없는 항목은 일정 시간 뒤 실패로 채워). 반환: 보낸 명령 수. */
    fun apply(t: HvacTargets, onDone: (List<ItemResult>) -> Unit): Int
    fun close()
}

/**
 * Pleos Vehicle SDK HVAC 어댑터(운전석 기준). 외기 순환/풍량/온도를 쓰고, 내기순환·풍량·온도·전원은 읽기만 한다.
 * 연결·복구는 core [ManagedConnection] 이 맡는다(재연결 로직 없음). 모든 SDK 콜백은 Unit 을 반환하고 세대 필터를 거친다.
 * 영역은 속성별 capability 응답에서 [AreaPicker] 로 고른 것만 쓴다(고정 영역 금지). 쓰기 성공은 SDK 의 성공 콜백을 받은 항목만 성공으로 센다.
 * 권한: pleos.car.permission.CAR_CLIMATE / CONTROL_CAR_CLIMATE.
 */
class PleosWakeHvac(context: Context, private val clock: () -> Long = SystemClock::elapsedRealtime) : WakeHvac {
    override var onState: (HvacSnapshot) -> Unit = {}
    private val appCtx = context.applicationContext
    private val lock = Any()
    private val areas = HashMap<String, SeatArea>()
    private val kick = Channel<Unit>(Channel.CONFLATED)

    private class Session(val vehicle: Vehicle) { fun close() { runCatching { vehicle.release() } } }

    private val conn = ManagedConnection<Session>(
        tag = "WakeHvac",
        create = { Session(Vehicle(appCtx)) },
        open = { s, _ -> s.vehicle.initialize() },
        dispose = { it.close() },
        clock = clock,
        policy = ConnectionPolicy(silenceCycles = SILENCE_CYCLES, deathWaitsBackoff = false, minReplaceIntervalMs = 60_000L),
        onCreateFailed = { t -> HmgLog.w("WakeHvac", "Vehicle SDK 사용 불가: ${t.javaClass.simpleName}"); publish(error = "SDK 불가") },
    )

    private var syncedGen = 0
    private var pendingCaps = 0
    private var capsAt = 0L
    private var capsKnown = false
    private var caps = HvacCaps()
    private var now = HvacNow()
    private var err: String? = null
    private var respSeen = false
    private val readable = HashSet<String>()

    override fun snapshot(): HvacSnapshot = synchronized(lock) { snapLocked() }
    private fun snapLocked() = HvacSnapshot(
        connected = pendingCaps == 0 && syncedGen != 0 && conn.isReady() && capsKnown && (caps.anyWritable || readable.isNotEmpty()),
        caps = caps, now = now, error = err, capsKnown = capsKnown && pendingCaps == 0,
    )

    private fun publish(error: String? = null) {
        val s = synchronized(lock) { if (error != null) err = error; snapLocked() }
        onState(s)
    }

    private fun areaOf(kind: String): SeatArea = synchronized(lock) { areas[kind] } ?: throw IllegalStateException("area not ready: $kind")
    private fun writable(c: CapabilityConfig) = c.accessType == AccessType.WRITE || c.accessType == AccessType.READ_WRITE
    private fun readableAccess(c: CapabilityConfig) = c.accessType == AccessType.READ || c.accessType == AccessType.READ_WRITE

    private fun pick(kind: String, list: Collection<SeatArea>): SeatArea? {
        val l = list.toList()
        val i = AreaPicker.pickDriverArea(l.map { a -> a.values.map { it.name } })
        HmgLog.i("WakeHvac", "area $kind 후보=${l.map { a -> a.values.map { it.name } }} 선택=${i?.let { l[it].values.map { v -> v.name } } ?: "없음(미지원)"}")
        return i?.let { l[it] }
    }

    private fun capDone(gen: Int, kind: String, cfg: CapabilityConfig?, area: SeatArea?, update: (HvacCaps, Boolean) -> HvacCaps) {
        if (!conn.isCurrent(gen)) return
        conn.onAlive(gen)
        val w = cfg != null && writable(cfg) && area != null
        val r = cfg != null && readableAccess(cfg) && area != null
        HmgLog.i("WakeHvac", "capability $kind access=${cfg?.accessType} 쓰기=$w 읽기=$r")
        synchronized(lock) {
            if (area != null) areas[kind] = area
            if (r) readable += kind
            caps = update(caps, w); respSeen = true
            if (pendingCaps > 0) pendingCaps--
            if (pendingCaps == 0) capsKnown = true
        }
        publish()
    }

    private fun requestCaps(c: Conn<Session>) {
        val v = c.value.vehicle; val gen = c.gen
        val h = v.getHVAC()
        synchronized(lock) { pendingCaps = 4; capsAt = clock(); caps = HvacCaps(); now = HvacNow(); err = null; areas.clear(); readable.clear(); capsKnown = false }
        val fail: (String) -> (Exception) -> Unit = { name -> { e -> conn.ifCurrent(gen) { conn.onFailure(gen, e); HmgLog.w("WakeHvac", "capability $name 실패: ${e.javaClass.simpleName}"); capDone(gen, name, null, null) { cp, _ -> cp } } } }
        h.checkHvacRecirculationCapabilities({ cfg: CapabilityConfig, l: List<SeatArea> -> capDone(gen, "recirc", cfg, pick("recirc", l)) { cp, ok -> cp.copy(recirc = ok) } }, fail("recirc"))
        h.checkHvacFanSpeedCapabilities({ cfg: CapabilityConfig, m: Map<SeatArea, AreaBounds<Int>> ->
            val a = pick("fan", m.keys); val b = a?.let { m[it] }
            capDone(gen, "fan", cfg, a) { cp, ok -> cp.copy(fan = ok, fanMin = b?.minValue?.coerceAtLeast(1) ?: cp.fanMin, fanMax = b?.maxValue?.coerceAtLeast(1) ?: cp.fanMax) } }, fail("fan"))
        h.checkHvacTemperatureCapabilities({ cfg: CapabilityConfig, m: Map<SeatArea, AreaBounds<Float>>, _: HvacTemperatureConfigArray? ->
            val a = pick("temp", m.keys); val b = a?.let { m[it] }
            capDone(gen, "temp", cfg, a) { cp, ok -> cp.copy(temp = ok, tempMin = if (b != null && b.minValue < b.maxValue) b.minValue else cp.tempMin, tempMax = if (b != null && b.minValue < b.maxValue) b.maxValue else cp.tempMax) } }, fail("temp"))
        h.checkHvacPowerCapabilities({ cfg: CapabilityConfig, l: List<SeatArea> -> capDone(gen, "power", cfg, pick("power", l)) { cp, _ -> cp } }, fail("power"))
    }

    private fun observe(kind: String, f: (HvacNow) -> HvacNow) {
        synchronized(lock) { now = f(now); respSeen = true }
        publish()
    }

    private fun readAll(c: Conn<Session>) {
        val h = c.value.vehicle.getHVAC(); val gen = c.gen
        val bad: (Exception) -> Unit = { e -> conn.ifCurrent(gen) { conn.onAlive(gen); conn.onFailure(gen, e) } }
        val rd = synchronized(lock) { readable.toSet() }
        if ("recirc" in rd) h.getHvacRecirculationState(areaOf("recirc"), { b: Boolean? -> conn.ifCurrent(gen) { conn.onResponse(gen); observe("recirc") { it.copy(recirc = b) } } }, bad)
        if ("fan" in rd) h.getHvacFanSpeed(areaOf("fan"), { x: Int? -> conn.ifCurrent(gen) { conn.onResponse(gen); observe("fan") { it.copy(fan = x) } } }, bad)
        if ("temp" in rd) h.getHvacTemperature(areaOf("temp"), TemperatureUnit.CELSIUS, { x: Float? -> conn.ifCurrent(gen) { conn.onResponse(gen); observe("temp") { it.copy(tempC = x) } } }, bad)
        if ("power" in rd) h.getHvacPowerState(areaOf("power"), { b: Boolean? -> conn.ifCurrent(gen) { conn.onResponse(gen); observe("power") { it.copy(power = b) } } }, bad)
    }

    private class Batch(val kinds: List<String>, val at: Long, val cb: (List<ItemResult>) -> Unit) { val results = ArrayList<ItemResult>() }
    private var batch: Batch? = null

    private fun record(b: Batch, kind: String, ok: Boolean, code: Int?) {
        var done: List<ItemResult>? = null
        synchronized(lock) {
            if (b.results.any { it.kind == kind }) return
            b.results += ItemResult(kind, ok, code)
            if (b.results.size >= b.kinds.size) { if (batch === b) batch = null; done = b.results.toList() }
        }
        if (!ok) HmgLog.w("WakeHvac", "쓰기 실패 kind=$kind errorCode=${code ?: "없음"}")
        done?.let { b.cb(it) }
    }

    private fun flushStale() {
        val b = synchronized(lock) { batch?.takeIf { clock() - it.at > APPLY_TIMEOUT_MS } } ?: return
        b.kinds.filter { k -> synchronized(lock) { b.results.none { it.kind == k } } }.forEach { record(b, it, false, 4) }
    }

    override fun apply(t: HvacTargets, onDone: (List<ItemResult>) -> Unit): Int {
        val c = conn.readyConn()
        val cp = synchronized(lock) { caps }
        // capability 가 쓰기 가능이라고 한 항목만 보낸다
        val kinds = buildList {
            if (t.recirc != null && cp.recirc) add("recirc")
            if (t.fan != null && cp.fan) add("fan")
            if (t.tempC != null && cp.temp) add("temp")
        }
        if (c == null || kinds.isEmpty()) { onDone(emptyList()); return 0 }
        val gen = c.gen; val h = c.value.vehicle.getHVAC()
        val b = Batch(kinds, clock(), onDone)
        synchronized(lock) { batch = b }
        fun ok(k: String): (Any?, Any?) -> Unit = { _, _ -> conn.ifCurrent(gen) { conn.onResponse(gen); record(b, k, true, null) } }
        fun fail(k: String): (Exception) -> Unit = { e -> conn.ifCurrent(gen) { conn.onAlive(gen); conn.onFailure(gen, e); record(b, k, false, HvacErrors.codeOf(e)) } }
        var n = 0
        fun guard(k: String, block: () -> Unit) { try { block(); n++ } catch (t2: Throwable) { if (t2 is CancellationException) throw t2; conn.onFailure(gen, t2); record(b, k, false, HvacErrors.codeOf(t2)) } }
        if ("recirc" in kinds) guard("recirc") { h.setHvacRecirculation(areaOf("recirc"), t.recirc!!, { a, r -> ok("recirc")(a, r) }, fail("recirc")) }
        if ("fan" in kinds) guard("fan") { h.setHvacFanSpeed(areaOf("fan"), t.fan!!, { a, r -> ok("fan")(a, r) }, fail("fan")) }
        if ("temp" in kinds) guard("temp") { h.setHvacTemperature(areaOf("temp"), t.tempC!!, TemperatureUnit.CELSIUS, { a, r -> ok("temp")(a, r) }, fail("temp")) }
        HmgLog.i("WakeHvac", "공조 명령 ${n}건 전송(${kinds.joinToString(",")})")
        return n
    }

    override fun retryNow() { conn.manualRetry(); kick.trySend(Unit) }

    override suspend fun run() {
        var polled = false
        while (true) {
            val c = conn.connect()
            if (c == null) { withTimeoutOrNull(conn.retryDelayMs().coerceIn(1L, 5_000L)) { kick.receive() }; continue }
            try {
                if (c.gen != syncedGen) { synchronized(lock) { syncedGen = c.gen }; polled = false; requestCaps(c) }
                else {
                    flushStale()
                    val stalled = synchronized(lock) { pendingCaps > 0 && clock() - capsAt > CAPS_TIMEOUT_MS }
                    if (stalled) { synchronized(lock) { pendingCaps = 0; capsKnown = true }; HmgLog.w("WakeHvac", "capability 응답 대기 초과 -> 응답한 항목만 사용"); publish() }
                    val (had, canRead) = synchronized(lock) { respSeen.also { respSeen = false } to (pendingCaps == 0 && readable.isNotEmpty()) }
                    if (polled && canRead && !had) conn.onSilentCycle()
                    if (canRead) { readAll(c); polled = true }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                conn.onFailure(c.gen, t)
                HmgLog.w("WakeHvac", "폴링 예외: ${t.javaClass.simpleName}")
            }
            withTimeoutOrNull(POLL_MS) { kick.receive() }
        }
    }

    override fun close() { conn.shutdown() }

    companion object {
        const val POLL_MS = 3_000L
        const val CAPS_TIMEOUT_MS = 4_000L
        const val APPLY_TIMEOUT_MS = 4_000L
        const val SILENCE_CYCLES = 3
    }
}

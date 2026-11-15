package com.hmgcarapp.wakemate.vehicle

import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.wakemate.domain.HvacCaps
import com.hmgcarapp.wakemate.domain.HvacNow
import com.hmgcarapp.wakemate.domain.HvacTargets
import com.hmgcarapp.wakemate.domain.ItemResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 시연용 가상 차량(debuggable 빌드에서 시연 화면이나 adb 가 켤 때만 쓴다).
 * Pleos 에뮬레이터의 Virtual Console 처럼 공조·시트 통풍·핸들 열선·무드등 쓰기를 받아 값을 바꾸고 성공을 알려 준다.
 * 표준 Android Automotive 에뮬레이터에는 Pleos 차량 서비스가 없어 실제 SDK 쓰기가 모두 실패하므로, 화면 흐름을 끝까지 확인하는 용도다.
 */
class VirtualWakeHvac(private val latencyMs: Long = LATENCY_MS) : WakeHvac {
    override var onState: (HvacSnapshot) -> Unit = {}
    private val lock = Any()
    private var now = INITIAL
    private val jobs = Channel<Pair<HvacTargets, (List<ItemResult>) -> Unit>>(Channel.UNLIMITED)

    override fun snapshot(): HvacSnapshot = synchronized(lock) { HvacSnapshot(connected = true, caps = CAPS, now = now, capsKnown = true) }

    override suspend fun run() {
        onState(snapshot())
        for ((t, done) in jobs) {
            delay(latencyMs) // 실제 차량처럼 응답이 조금 늦게 온다
            synchronized(lock) {
                now = now.copy(
                    recirc = t.recirc ?: now.recirc, fan = t.fan ?: now.fan, tempC = t.tempC ?: now.tempC,
                    seatVent = t.seatVent ?: now.seatVent, ambientColor = t.ambientColor ?: now.ambientColor, wheelHeat = t.wheelHeat ?: now.wheelHeat,
                )
            }
            onState(snapshot())
            HmgLog.i("VirtualCar", "쓰기 완료: ${t.kinds.joinToString(",")}")
            done(t.kinds.map { ItemResult(it, true, null) })
        }
    }

    override fun retryNow() { onState(snapshot()) }

    override fun apply(t: HvacTargets, onDone: (List<ItemResult>) -> Unit): Int {
        val kinds = t.kinds
        HmgLog.i("VirtualCar", "쓰기 요청: ${kinds.joinToString(",").ifEmpty { "없음" }}")
        if (kinds.isEmpty()) { onDone(emptyList()); return 0 }
        jobs.trySend(t to onDone)
        return kinds.size
    }

    /** 처음 상태(내기 순환·풍량 3단·23.5℃·통풍/열선 꺼짐·무드등 기본색)로 되돌린다. */
    fun reset() { synchronized(lock) { now = INITIAL }; onState(snapshot()) }

    override fun close() { jobs.close() }

    companion object {
        const val LATENCY_MS = 900L
        /** 무드등 기본색(불투명 하늘색 ARGB 0xFF2E9BFF). */
        const val DEFAULT_AMBIENT_ARGB = -13722625
        val CAPS = HvacCaps(
            recirc = true, fan = true, temp = true, fanMin = 1, fanMax = 8, tempMin = 17f, tempMax = 32f,
            seatVent = true, seatVentMax = 3, ambientColor = true, wheelHeat = true, wheelHeatMax = 3,
        )
        val INITIAL = HvacNow(power = true, recirc = true, fan = 3, tempC = 23.5f, seatVent = 0, ambientColor = DEFAULT_AMBIENT_ARGB, wheelHeat = 0)
    }
}

/**
 * 실제 어댑터와 가상 차량을 시연 중에만 바꿔 끼운다(debuggable 빌드 전용 배선). 바꾸면 새 쪽 상태를 곧바로 알린다.
 * 꺼져 있으면 실제 어댑터와 똑같이 동작한다.
 */
class SwitchableWakeHvac(private val real: WakeHvac, private val virtual: VirtualWakeHvac) : WakeHvac {
    @Volatile var virtualOn: Boolean = false
        private set

    override var onState: (HvacSnapshot) -> Unit = {}
        set(value) {
            field = value
            real.onState = { if (!virtualOn) value(it) }
            virtual.onState = { if (virtualOn) value(it) }
        }

    private val active: WakeHvac get() = if (virtualOn) virtual else real

    fun setVirtual(on: Boolean) {
        if (virtualOn == on) return
        virtualOn = on
        if (on) virtual.reset() else onState(real.snapshot())
    }

    override suspend fun run() { coroutineScope { launch { virtual.run() }; real.run() } }
    override fun retryNow() { real.retryNow(); if (virtualOn) virtual.retryNow() }
    override fun snapshot(): HvacSnapshot = active.snapshot()
    override fun apply(t: HvacTargets, onDone: (List<ItemResult>) -> Unit): Int = active.apply(t, onDone)
    override fun close() { real.close(); virtual.close() }
}

package com.hmgcarapp.wakemate.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import android.content.Context
import com.hmgcarapp.core.connection.ManagedConnection
import com.hmgcarapp.core.log.HmgLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 조향각(도) 소스. 값이 오지 않으면 아무것도 내보내지 않는다(지수에는 '수집 중'으로 반영). */
interface SteeringSource { fun angles(): Flow<Float> }

/** 조향용 Vehicle 포트(테스트에서 가짜). 콜백 람다는 항상 Unit. */
internal interface SteerVehicle {
    fun initialize()
    fun queryCurrent(onAngle: (Float) -> Unit, onError: (Throwable) -> Unit)
    fun register(onAngle: (Float) -> Unit, onError: (Throwable) -> Unit): () -> Unit
    fun release()
}

internal class SteerLink(val vehicle: SteerVehicle) {
    @Volatile var unregister: (() -> Unit)? = null
    fun close() { runCatching { unregister?.invoke() }; runCatching { vehicle.release() } }
}

/**
 * 조향각 흐름: 연결·재연결은 [ManagedConnection] 이 맡는다. 푸시 이벤트가 오지 않는 환경(속도 푸시가 0건이던 실측)에 대비해
 * [pollMs] 마다 현재값도 질의한다. 첫 값은 그대로 값으로 내보낸다(사용자 조작/변화로 해석하지 않는다: 소비자가 표본으로만 쓴다).
 */
internal fun steeringFlowOf(
    factory: () -> SteerVehicle,
    pollMs: Long = 500L,
    sleep: suspend (Long) -> Unit = { delay(it) },
    nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
): Flow<Float> = callbackFlow {
    lateinit var conn: ManagedConnection<SteerLink>
    conn = ManagedConnection(
        tag = "Steering",
        create = { SteerLink(factory()) },
        open = { link, gen ->
            val v = link.vehicle
            v.initialize()
            val onAngle: (Float) -> Unit = conn.callback(gen) { a: Float -> trySend(a); Unit }
            val onError: (Throwable) -> Unit = conn.callback(gen) { e: Throwable ->
                HmgLog.w("Steering", "조향각 오류: ${e.javaClass.simpleName}")
                conn.onFailure(gen, e)
                Unit
            }
            v.queryCurrent(onAngle, onError)
            link.unregister = v.register(onAngle, onError)
        },
        dispose = { it.close() },
        clock = nowMs,
        onCreateFailed = { t -> HmgLog.w("Steering", "Vehicle SDK 사용 불가: ${t.javaClass.simpleName}") },
    )
    val poller = launch {
        while (isActive) {
            sleep(pollMs)
            val c = conn.readyConn() ?: continue
            val gen = c.gen
            try {
                c.value.vehicle.queryCurrent(
                    conn.callback(gen) { a: Float -> trySend(a); Unit },
                    conn.callback(gen) { e: Throwable -> conn.onFailure(gen, e); Unit },
                )
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                conn.onFailure(gen, t)
            }
        }
    }
    try { conn.supervise(sleep) } finally { poller.cancel() }
    awaitClose { }
}

/** Pleos Vehicle SDK 2.3.2 SteeringWheel.SteeringWheelAngle (permission: pleos.car.permission.CAR_DRIVE). */
class PleosSteeringSource(private val context: Context) : SteeringSource {
    override fun angles(): Flow<Float> = flow { emitAll(steeringFlowOf({ SdkSteerVehicle(context.applicationContext) })) }

    private class SdkSteerVehicle(ctx: Context) : SteerVehicle {
        private val v = Vehicle(ctx)
        override fun initialize() { v.initialize() }
        override fun queryCurrent(onAngle: (Float) -> Unit, onError: (Throwable) -> Unit) {
            v.getSteeringWheel().getCurrentSteeringWheelAngle({ a: Float? -> if (a != null) onAngle(a); Unit }, { e: Exception -> onError(e); Unit })
        }
        override fun register(onAngle: (Float) -> Unit, onError: (Throwable) -> Unit): () -> Unit {
            val l = object : SteeringWheelAngleListener {
                override fun onSteeringWheelAngleUpdated(angle: Float?) { if (angle != null) onAngle(angle); Unit }
                override fun onFailed(e: Exception) { onError(e); Unit }
            }
            val w = v.getSteeringWheel()
            w.registerSteeringWheelAngle(l)
            return { w.unregisterSteeringWheelAngle(l); Unit }
        }
        override fun release() { v.release() }
    }
}

package com.hmgcarapp.core.vehicle

import android.content.Context
import com.hmgcarapp.core.connection.ConnectionPolicy
import com.hmgcarapp.core.connection.ManagedConnection
import com.hmgcarapp.core.connection.isCarServiceFailure
import com.hmgcarapp.core.log.HmgLog
import java.lang.reflect.InvocationTargetException

/** android.car.Car 와 그 매니저(예: property, uxrestriction). */
internal class CarHandle(val car: Any) {
    @Volatile var mgr: Any? = null
}

/** android.car 를 리플렉션으로 연결하는 공통 부분(core 가 android.car 에 컴파일 의존하지 않도록). 연결 관리는 [ManagedConnection]. */
internal object ReflectiveCar {
    /** 재연결 시도 사이 최소 간격(같은 값이 고정 백오프). */
    const val RECONNECT_GAP_MS = 7_000L

    /** 서비스 사망 후 즉시 폐기, 새 시도는 마지막 시도로부터 [RECONNECT_GAP_MS] 뒤에만. */
    val POLICY = ConnectionPolicy(
        backoffStartMs = RECONNECT_GAP_MS, backoffMaxMs = RECONNECT_GAP_MS,
        deathWaitsBackoff = false, minAttemptGapMs = RECONNECT_GAP_MS,
    )

    fun connection(tag: String, appContext: Context, service: String, clock: () -> Long = { System.nanoTime() / 1_000_000L }) =
        ManagedConnection(
            tag = tag,
            create = {
                try {
                    val carCls = Class.forName("android.car.Car")
                    CarHandle(carCls.getMethod("createCar", Context::class.java).invoke(null, appContext)!!)
                } catch (t: Throwable) { throw unwrap(t) }
            },
            open = { h, _ ->
                try {
                    val carCls = Class.forName("android.car.Car")
                    h.mgr = carCls.getMethod("getCarManager", String::class.java).invoke(h.car, service)
                        ?: throw IllegalStateException("$service 매니저 없음")
                } catch (t: Throwable) { throw unwrap(t) }
            },
            dispose = { h -> runCatching { h.car.javaClass.getMethod("disconnect").invoke(h.car) } },
            isConnectionFailure = ::isCarServiceFailure,
            clock = clock,
            policy = POLICY,
            onCreateFailed = { HmgLog.w(tag, "Car 연결 실패: $it") },
        )

    fun unwrap(e: Throwable): Throwable = if (e is InvocationTargetException && e.targetException != null) e.targetException else e
}

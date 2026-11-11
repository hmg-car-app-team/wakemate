package com.hmgcarapp.core.vehicle

import android.content.Context

/**
 * android.car.Car / CarPropertyManager 를 리플렉션으로 호출한다 (probe 실측 코드 재사용).
 * 일반 기기(android.car 없음)에서는 연결이 실패하고 getFloat 는 예외를 던진다 -> VehicleSignals 가 null+사유로 변환.
 * 연결·재연결(서비스 사망 시 폐기 후 7초 간격)은 [com.hmgcarapp.core.connection.ManagedConnection] 이 맡는다.
 */
class ReflectiveCarPropertyReader(context: Context) : CarPropertyReader {
    private val lock = Any()
    private var refs = 0
    private val conn = ReflectiveCar.connection("CarProp", context.applicationContext, "property")

    override fun acquire() {
        val first = synchronized(lock) { refs++; refs == 1 }
        if (first) {
            conn.connect(force = true) // 바인더 호출은 느릴 수 있으므로 락 밖
            if (synchronized(lock) { refs == 0 }) conn.disconnect() // 연결하는 동안 모두 release 됐으면 버린다
        }
    }

    override fun release() {
        val last = synchronized(lock) { if (refs > 0) refs--; refs == 0 }
        if (last) conn.disconnect()
    }

    override fun getFloat(propertyId: Int, areaId: Int): Float {
        // 서비스 재시작 등으로 연결을 잃었으면 사용 중(refs>0)일 때 간격을 두고 재연결
        val c = conn.current() ?: (if (synchronized(lock) { refs > 0 }) conn.connect() else null)
            ?: throw IllegalStateException("CarPropertyManager 없음(android.car 미지원 기기)")
        val pm = c.value.mgr ?: throw IllegalStateException("CarPropertyManager 없음(android.car 미지원 기기)")
        try {
            val r = pm.javaClass.getMethod("getFloatProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(pm, propertyId, areaId)
            return r as Float
        } catch (t: Throwable) {
            val e = ReflectiveCar.unwrap(t)
            // 서비스 사망 계열: 죽은 매니저를 폐기하고 다음 호출에서 간격을 두고 재연결
            if (conn.onFailure(c.gen, e)) conn.reap()
            throw e
        }
    }
}

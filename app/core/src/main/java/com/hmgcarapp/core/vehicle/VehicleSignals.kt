package com.hmgcarapp.core.vehicle

import com.hmgcarapp.core.log.HmgLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

/**
 * 신호 읽기 결과. 값이 없으면 [reason] 에 사유가 있다 (앱이 죽지 않도록 예외 대신 반환).
 */
data class SignalReading<out T>(val value: T?, val reason: String? = null) {
    val isOk: Boolean get() = value != null

    companion object {
        fun <T> ok(v: T) = SignalReading(v, null)
        fun <T> unavailable(reason: String): SignalReading<T> = SignalReading(null, reason)
    }
}

/** 차량 속성 ID (android.car.VehiclePropertyIds 와 동일한 값; 에뮬레이터 실측 확인). */
object VehiclePropertyIds {
    const val PERF_VEHICLE_SPEED = 291504647
    const val ENV_OUTSIDE_TEMPERATURE = 291505923
}

object Units {
    fun msToKmh(ms: Float): Float = ms * 3.6f
    fun kmhToMs(kmh: Float): Float = kmh / 3.6f
}

/**
 * 차량 신호(속도·외기온도).
 *
 * 권한 (AndroidManifest 에 선언; 설치 시 자동 grant 됨 - 에뮬레이터 실측):
 *  - android.car.permission.CAR_SPEED            (차속)
 *  - android.car.permission.CAR_EXTERIOR_ENVIRONMENT (외기온도)
 *  - <uses-library android:name="android.car" android:required="false" /> 권장
 * 푸시 콜백(registerCallback)은 실측에서 0건이므로 **폴링**을 쓴다.
 */
interface VehicleSignals {
    /** 현재 차속(km/h). 미지원/예외면 value=null + reason. */
    suspend fun speedKmhNow(): SignalReading<Float>

    /** 외기온도(℃). */
    suspend fun outsideTempCNow(): SignalReading<Float>

    /** 차속(km/h) 폴링 Flow. 수집 취소 시 내부 자원 정리. */
    fun speedKmh(intervalMs: Long = DEFAULT_INTERVAL_MS): Flow<SignalReading<Float>>

    companion object { const val DEFAULT_INTERVAL_MS = 1000L }
}

/** 속성 읽기 추상화 (테스트에서 가짜로 대체). 실패 시 예외를 던져도 된다. */
interface CarPropertyReader {
    /** 사용 시작(참조 +1). Car 연결 등. */
    fun acquire()
    /** 사용 종료(참조 -1). 0이 되면 연결 해제. */
    fun release()
    fun getFloat(propertyId: Int, areaId: Int = 0): Float
}

class CarPropertyVehicleSignals(private val reader: CarPropertyReader) : VehicleSignals {
    private val module = "VehicleSignals"

    private fun read(propId: Int, name: String, convert: (Float) -> Float = { it }): SignalReading<Float> =
        try {
            SignalReading.ok(convert(reader.getFloat(propId)))
        } catch (t: Throwable) {
            val reason = "$name 읽기 실패: ${t.javaClass.simpleName}: ${t.message}"
            HmgLog.w(module, reason)
            SignalReading.unavailable(reason)
        }

    override suspend fun speedKmhNow() = readWithLifecycle {
        read(VehiclePropertyIds.PERF_VEHICLE_SPEED, "차속", Units::msToKmh)
    }

    override suspend fun outsideTempCNow() = readWithLifecycle {
        read(VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE, "외기온도")
    }

    private inline fun <T> readWithLifecycle(block: () -> SignalReading<T>): SignalReading<T> {
        return try {
            reader.acquire()
            try { block() } finally { reader.release() }
        } catch (t: Throwable) {
            SignalReading.unavailable("Car 연결 실패: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override fun speedKmh(intervalMs: Long): Flow<SignalReading<Float>> = flow {
        while (true) {
            emit(read(VehiclePropertyIds.PERF_VEHICLE_SPEED, "차속", Units::msToKmh))
            delay(intervalMs)
        }
    }.onStart {
        try { reader.acquire() } catch (t: Throwable) {
            HmgLog.w(module, "acquire 실패: $t")
        }
    }.onCompletion {
        runCatching { reader.release() }
    }
}

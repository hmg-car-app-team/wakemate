package com.hmgcarapp.core.location

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.hmgcarapp.core.log.HmgLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import com.hmgcarapp.core.connection.ManagedConnection

data class GeoPoint(val latitude: Double, val longitude: Double, val heading: Float? = null, val timestampMillis: Long = 0L)

/** 위치 소스. */
interface LocationSource {
    /** 위치 Flow (약 1Hz). 수집 취소 시 콜백 해제. 오류는 로그 후 계속. */
    fun locations(): Flow<GeoPoint>
    /** 이 환경에서 위치 SDK 를 쓸 수 있는지. false 면 locations() 는 빈 Flow. */
    fun isSupported(): Boolean = true
}

/**
 * 권한 안내:
 *  - 매니페스트: pleos.car.permission.FUSED_LOCATION, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION,
 *    포그라운드 서비스에서 쓰려면 FOREGROUND_SERVICE + FOREGROUND_SERVICE_LOCATION 과 service foregroundServiceType="location".
 *  - **FINE_LOCATION 런타임 권한은 서비스를 시작하기 전에 Activity 에서 먼저 요청**해야 한다
 *    (백그라운드/부팅 수신기에서 startForeground(location) 은 SecurityException; probe 실측).
 */
object LocationPermissions {
    val RUNTIME = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, "pleos.car.permission.FUSED_LOCATION")

    fun missing(context: Context): List<String> =
        RUNTIME.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    const val PLEOS_FUSED = "pleos.car.permission.FUSED_LOCATION"

    /** FINE + pleos FUSED_LOCATION 모두 허용됐는지 */
    fun ready(context: Context) = hasFine(context) &&
        context.checkSelfPermission(PLEOS_FUSED) == PackageManager.PERMISSION_GRANTED

    fun hasFine(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}

/** 위치용 FusedLocation 포트(테스트에서 가짜 주입). 콜백 람다는 항상 Unit. */
internal interface FusedPort {
    fun initialize()
    /** 반환: 해제용 핸들. */
    fun register(onLocation: (GeoPoint) -> Unit, onError: (Throwable) -> Unit): () -> Unit
    fun release()
}

/** 위치용 연결 한 개: 포트와 구독 해제 핸들(해제 순서: unregister -> release). */
internal class FusedLink(val port: FusedPort) {
    @Volatile var unregister: (() -> Unit)? = null
    fun close() { runCatching { unregister?.invoke() }; runCatching { port.release() } }
}

/**
 * 권한 대기(1초 폴링, 같은 누락 목록은 한 번만 로그) -> 포트 생성/초기화/등록. 초기화·등록 예외나 연결 실패 오류 시
 * 해제 후 백오프(2->30초, 10초 이상 유지된 연결 뒤에만 리셋)로 재생성한다([ManagedConnection]). 취소 시 해제.
 */
internal fun locationFlowOf(
    factory: () -> FusedPort,
    missing: () -> List<String>,
    sleep: suspend (Long) -> Unit = { delay(it) },
    nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
): Flow<GeoPoint> = callbackFlow {
    var lastMissing: List<String>? = null
    lateinit var conn: ManagedConnection<FusedLink>
    conn = ManagedConnection(
        tag = "Location",
        create = { FusedLink(factory()) },
        open = { link, gen ->
            link.port.initialize()
            link.unregister = link.port.register(
                conn.callback(gen) { p: GeoPoint -> trySend(p); Unit },
                conn.callback(gen) { e: Throwable -> HmgLog.w("Location", "오류: $e"); conn.onFailure(gen, e); Unit },
            )
            HmgLog.i("Location", "FusedLocation 구독 시작")
        },
        dispose = { it.close() },
        clock = nowMs,
        onCreateFailed = { HmgLog.w("Location", "FusedLocation 사용 불가: $it") },
    )
    conn.supervise(sleep) {
        var m = missing()
        while (m.isNotEmpty() && isActive) {
            if (m != lastMissing) { HmgLog.w("Location", "위치 권한 대기: $m"); lastMissing = m }
            sleep(1000)
            m = missing()
        }
        lastMissing = null
    }
    awaitClose { }
}

/** Pleos FusedLocation 1.1.3 래퍼. 포그라운드 서비스에서 collect 해도 된다. */
class PleosLocationSource(private val context: Context) : LocationSource {
    /** SDK 클래스가 로드되는지(미지원 환경 판별용). 서비스 연결 여부는 구독 시 재시도로 처리한다. */
    override fun isSupported(): Boolean = runCatching { Class.forName("ai.pleos.playground.location.fused.FusedLocation"); true }.getOrDefault(false)

    override fun locations(): Flow<GeoPoint> {
        if (!isSupported()) { HmgLog.w("Location", "FusedLocation SDK 없음 - 위치 없이 동작"); return emptyFlow() }
        return locationFlowOf({ SdkFused(context.applicationContext) }, { LocationPermissions.missing(context) })
    }

    private class SdkFused(ctx: Context) : FusedPort {
        private val f = FusedLocation(ctx)
        override fun initialize() { f.initialize() }
        override fun register(onLocation: (GeoPoint) -> Unit, onFail: (Throwable) -> Unit): () -> Unit {
            val l = object : FusedLocationListener {
                override fun onChanged(location: LocationData?) {
                    if (location != null) { onLocation(GeoPoint(location.latitude, location.longitude, location.heading, location.timestampMillis)); Unit }
                }
                override fun onError(exception: Exception) { onFail(exception); Unit }
            }
            f.registerFusedLocationCallback(l)
            return { f.unregisterFusedLocationCallback(l); Unit }
        }
        override fun release() { f.release() }
    }
}

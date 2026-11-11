package com.hmgcarapp.wakemate.domain

import kotlin.math.PI
import kotlin.math.sin

enum class SimPattern { NORMAL, DROWSY }

data class SimSample(val steerDeg: Float, val speedKmh: Float)

/**
 * 시연용 신호 패턴(순수 함수). 실제 졸음 운전 데이터가 아니라 위험지수 엔진을 구동해 보이기 위한 합성 신호다.
 * - NORMAL: 조향이 계속 ±3° 안팎으로 움직이고 속도는 90km/h 부근에서 거의 일정.
 * - DROWSY: 12초 주기로 7초 동안 거의 움직이지 않다가 0.5초 만에 8°로 크게 바로잡고 1.5초에 걸쳐 복귀. 속도는 80±9km/h 로 20초 주기 출렁임.
 */
object SimPatterns {
    fun sample(p: SimPattern, tMs: Long): SimSample = when (p) {
        SimPattern.NORMAL -> SimSample(
            (3.0 * sin(2 * PI * tMs / 4000.0)).toFloat(),
            (90.0 + 0.8 * sin(2 * PI * tMs / 30000.0)).toFloat(),
        )
        SimPattern.DROWSY -> {
            val ph = Math.floorMod(tMs, 12_000L)
            val steer = when {
                ph < 7_000L -> 0.4 * sin(ph / 1000.0)
                ph < 7_500L -> 8.0
                ph < 9_000L -> 8.0 * (9_000L - ph) / 1_500.0
                else -> 0.3 * sin(ph / 1000.0)
            }
            SimSample(steer.toFloat(), (80.0 + 9.0 * sin(2 * PI * tMs / 20000.0)).toFloat())
        }
    }
}

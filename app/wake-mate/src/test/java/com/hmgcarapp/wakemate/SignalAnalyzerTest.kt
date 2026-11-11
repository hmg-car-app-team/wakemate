package com.hmgcarapp.wakemate

import com.hmgcarapp.wakemate.domain.SpeedAnalyzer
import com.hmgcarapp.wakemate.domain.SpeedSample
import com.hmgcarapp.wakemate.domain.SteerSample
import com.hmgcarapp.wakemate.domain.SteeringAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sin

class SignalAnalyzerTest {
    private fun steer(from: Long, to: Long, stepMs: Long = 500, f: (Long) -> Float) = (from..to step stepMs).map { SteerSample(it, f(it)) }

    @Test fun quietThenAbruptCorrection_countsOnce() {
        val s = steer(0, 39_500) { t -> if (t == 20_000L) 8f else 0.2f }
        assertEquals(1, SteeringAnalyzer.corrections(s, 39_500))
    }

    @Test fun twoQuietThenCorrectionCycles_countTwo() {
        val s = steer(0, 59_500) { t -> if (t == 20_000L || t == 40_000L) 8f else 0.2f }
        assertEquals(2, SteeringAnalyzer.corrections(s, 59_500))
    }

    @Test fun normalWeaving_isNotCounted() {
        val s = steer(0, 39_500) { t -> (3.0 * sin(2 * Math.PI * t / 4000.0)).toFloat() }
        assertEquals(0, SteeringAnalyzer.corrections(s, 39_500))
    }

    @Test fun quietPeriodShorterThanThreeSeconds_isNotCounted() {
        // ±3° 로 계속 움직이다 2초(17.5~19.5s)만 조용했다가 8° -> 3초 미만이라 졸음 패턴이 아니다
        val s = steer(0, 39_500) { t ->
            when {
                t in 17_500L..19_500L -> 0.2f
                t == 20_000L -> 8f
                (t / 500) % 2 == 0L -> 3f
                else -> -3f
            }
        }
        assertEquals(0, SteeringAnalyzer.corrections(s, 39_500))
    }

    @Test fun smallCorrectionBelowSixDegrees_isNotCounted() {
        val s = steer(0, 39_500) { t -> if (t == 20_000L) 5.5f else 0f }
        assertEquals(0, SteeringAnalyzer.corrections(s, 39_500))
    }

    @Test fun gradualDrift_isNotCounted() {
        // 조용한 뒤 1°씩 천천히 틀어지는 조향은 '갑작스런 보정'이 아니다
        val s = steer(0, 39_500) { t -> if (t < 20_000L) 0f else minOf(10f, (t - 20_000L) / 500f) }
        assertEquals(0, SteeringAnalyzer.corrections(s, 39_500))
    }

    @Test fun lessThanThirtySecondsOfData_isUnknown() {
        val s = steer(0, 10_000) { 0f }
        assertNull(SteeringAnalyzer.corrections(s, 10_000))
    }

    @Test fun sampleGapBreaksSegments_noCountAcrossGap() {
        // 20초 조용 -> 5초 공백(저속·정차로 표집 중단) -> 8°: 공백 뒤 값은 보정이 아니다
        val s = steer(0, 19_500) { 0.2f } + steer(25_000, 39_500) { 8f }
        assertEquals(0, SteeringAnalyzer.corrections(s, 39_500))
    }

    @Test fun samplesOlderThanWindow_areIgnored() {
        val s = steer(0, 39_500) { t -> if (t == 20_000L) 8f else 0.2f }
        // 창은 90초: 100초 시점에는 옛 표본 모두 창 밖 -> 판단 불가
        assertNull(SteeringAnalyzer.corrections(s, 130_000))
    }

    private fun speeds(n: Int, f: (Int) -> Float) = (0 until n).map { SpeedSample(it * 1000L, f(it)) }

    @Test fun constantSpeed_hasZeroDeviation() {
        assertEquals(0.0, SpeedAnalyzer.detrendedSd(speeds(41) { 90f }, 40_000)!!.toDouble(), 1e-6)
    }

    @Test fun steadyAcceleration_isDetrended() {
        // 60 -> 100km/h 로 일정하게 가속: 추세를 빼므로 변동성은 0
        assertEquals(0.0, SpeedAnalyzer.detrendedSd(speeds(41) { 60f + it }, 40_000)!!.toDouble(), 1e-3)
    }

    @Test fun alternatingFiveKmh_hasAboutFiveDeviation() {
        val sd = SpeedAnalyzer.detrendedSd(speeds(41) { if (it % 2 == 0) 85f else 95f }, 40_000)!!
        assertEquals(5.0, sd.toDouble(), 0.1)
    }

    @Test fun slowSpeedsAreExcluded_andTooFewSamplesIsUnknown() {
        assertNull(SpeedAnalyzer.detrendedSd(speeds(41) { 30f }, 40_000))   // 40km/h 미만 전부 제외
        assertNull(SpeedAnalyzer.detrendedSd(speeds(15) { 90f }, 14_000))   // 20개 미만
    }

    @Test fun shortCoverage_isUnknown() {
        assertNull(SpeedAnalyzer.detrendedSd(speeds(25) { 90f }, 24_000)) // 24초 < 30초
    }
}

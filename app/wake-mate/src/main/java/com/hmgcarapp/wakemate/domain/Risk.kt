package com.hmgcarapp.wakemate.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 깨우기 단계. NONE=정상, S1=음성, S2=경고음+공조, S3=쉼터 안내 제안. */
enum class Stage(val n: Int, val label: String) {
    NONE(0, "정상"), S1(1, "주의"), S2(2, "경고"), S3(3, "위험");

    companion object { fun of(n: Int): Stage = entries.firstOrNull { it.n == n } ?: NONE }
}

/** 단계 경계값(위험지수 이상이면 해당 단계). 개인 보정 offset 만큼 모두 올라간다. */
data class Thresholds(val s1: Int, val s2: Int, val s3: Int) {
    fun stageOf(score: Int): Stage = when {
        score >= s3 -> Stage.S3
        score >= s2 -> Stage.S2
        score >= s1 -> Stage.S1
        else -> Stage.NONE
    }

    companion object {
        const val BASE_S1 = 40
        const val BASE_S2 = 60
        const val BASE_S3 = 80
        fun of(offset: Int) = offset.coerceIn(0, Calibration.MAX_OFFSET).let { Thresholds(BASE_S1 + it, BASE_S2 + it, BASE_S3 + it) }
    }
}

/**
 * 개인 보정: '졸리지 않아요' 피드백이 쌓일수록 경계값이 올라간다(오경보 줄이기). 한 번에 [STEP], 최대 [MAX_OFFSET].
 * 졸림을 줄이는 방향(경계 하향)은 안전상 자동으로 하지 않는다.
 */
data class Calibration(val offset: Int = 0, val feedbackCount: Int = 0) {
    fun afterNotSleepy() = Calibration((offset + STEP).coerceAtMost(MAX_OFFSET), feedbackCount + 1)
    val thresholds: Thresholds get() = Thresholds.of(offset)

    companion object {
        const val STEP = 3
        const val MAX_OFFSET = 15
    }
}

data class SteerSample(val tMs: Long, val deg: Float)
data class SpeedSample(val tMs: Long, val kmh: Float)

/**
 * 조향 패턴: '한동안 거의 움직이지 않다가 갑자기 크게 바로잡는' 보정 조향 횟수(졸음 운전에서 흔한 패턴).
 * 입력은 고정 주기(≈500ms)로 표집된 값이며, 간격이 [GAP_BREAK_MS] 를 넘으면 표본이 끊긴 것으로 본다(저속·정차 구간 제외).
 */
object SteeringAnalyzer {
    const val WINDOW_MS = 90_000L
    const val QUIET_BAND_DEG = 2f
    const val QUIET_MIN_MS = 3_000L
    const val CORRECTION_DEG = 6f
    const val CORRECTION_LOOKAHEAD_MS = 1_500L
    const val GAP_BREAK_MS = 2_000L
    /** 이 이상 표본이 모여야(≈30초) 판단한다. */
    const val MIN_COVERAGE_MS = 30_000L

    /** 창 안의 표본이 [MIN_COVERAGE_MS] 미만이면 null(판단 불가). */
    fun corrections(all: List<SteerSample>, nowMs: Long): Int? {
        val s = all.filter { it.tMs in (nowMs - WINDOW_MS + 1)..nowMs }.sortedBy { it.tMs }
        if (s.size < 2 || coverage(s) < MIN_COVERAGE_MS) return null
        var count = 0
        var start = 0
        var lo = s[0].deg; var hi = s[0].deg
        for (i in 1 until s.size) {
            val a = s[i].deg
            val gap = s[i].tMs - s[i - 1].tMs > GAP_BREAK_MS
            val nlo = min(lo, a); val nhi = max(hi, a)
            if (!gap && nhi - nlo <= QUIET_BAND_DEG) { lo = nlo; hi = nhi; continue }
            // 구간 [start, i-1] 이 끝났다
            val quietMs = s[i - 1].tMs - s[start].tMs
            if (!gap && quietMs >= QUIET_MIN_MS) {
                val mean = s.subList(start, i).map { it.deg }.average().toFloat()
                var j = i
                var corrected = false
                while (j < s.size && s[j].tMs - s[i - 1].tMs <= CORRECTION_LOOKAHEAD_MS && s[j].tMs - s[max(j - 1, i - 1)].tMs <= GAP_BREAK_MS) {
                    if (abs(s[j].deg - mean) >= CORRECTION_DEG) { corrected = true; break }
                    j++
                }
                if (corrected) count++
            }
            start = i; lo = a; hi = a
        }
        return count
    }

    /** 창 안에서 연속 구간(간격 [GAP_BREAK_MS] 이내)으로 이어진 표본의 총 길이. */
    private fun coverage(s: List<SteerSample>): Long {
        var total = 0L
        for (i in 1 until s.size) { val d = s[i].tMs - s[i - 1].tMs; if (d <= GAP_BREAK_MS) total += d }
        return total
    }
}

/** 속도 변동성: 추세(가감속)를 뺀 잔차의 표준편차(km/h). 40km/h 미만 표본은 제외한다. */
object SpeedAnalyzer {
    const val WINDOW_MS = 90_000L
    const val MIN_KMH = 40f
    const val MIN_SAMPLES = 20
    const val MIN_COVERAGE_MS = 30_000L

    /** 표본이 부족하면 null. */
    fun detrendedSd(all: List<SpeedSample>, nowMs: Long): Float? {
        val s = all.filter { it.tMs in (nowMs - WINDOW_MS + 1)..nowMs && it.kmh >= MIN_KMH && it.kmh.isFinite() }.sortedBy { it.tMs }
        if (s.size < MIN_SAMPLES || s.last().tMs - s.first().tMs < MIN_COVERAGE_MS) return null
        val t0 = s.first().tMs
        val xs = s.map { (it.tMs - t0) / 1000.0 }
        val ys = s.map { it.kmh.toDouble() }
        val mx = xs.average(); val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        val slope = if (sxx == 0.0) 0.0 else xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
        val res = xs.indices.map { ys[it] - (my + slope * (xs[it] - mx)) }
        return sqrt(res.sumOf { it * it } / res.size).toFloat()
    }
}

/** 위험지수 구성 요소 하나. [points] 는 0..[max]. [note] 는 화면 표시용(판단 불가면 '수집 중' 등). */
data class Factor(val key: String, val label: String, val points: Int, val max: Int, val note: String)

data class RiskInputs(
    /** 하루 중 분(0..1439). 벽시계(현지 시각)에서 컨트롤러가 계산해 넣는다. */
    val minuteOfDay: Int,
    val drivingMs: Long,
    /** 내기순환이 연속으로 켜져 있던 시간. 꺼져 있거나 알 수 없으면 null. */
    val recircMs: Long?,
    /** null = 표본 부족(판단 불가). */
    val steerCorrections: Int?,
    val speedSdKmh: Float?,
)

data class RiskResult(val score: Int, val stage: Stage, val factors: List<Factor>, val thresholds: Thresholds)

/**
 * 졸음 위험지수(0~100) 순수 계산. 배점: 시간대 20 + 연속주행 30 + 조향 20 + 속도 변동성 15 + 내기순환 15.
 * 이 지수는 운전자의 졸음 상태를 측정한 것이 아니라 차량 신호 기반의 **추정 위험도**다(카메라·생체 신호 없음).
 */
object RiskEngine {
    const val W_TIME = 20
    const val W_DRIVE = 30
    const val W_STEER = 20
    const val W_SPEED = 15
    const val W_RECIRC = 15

    /** 시간대 위험 비율(0..1): 00~02시 0.5, 02~06시 1.0, 06~07시 0.4, 13~15시 0.5, 그 외 0. */
    fun timeFraction(minuteOfDay: Int): Double {
        val m = minuteOfDay.coerceIn(0, 1439)
        return when {
            m < 120 -> 0.5
            m < 360 -> 1.0
            m < 420 -> 0.4
            m in 780 until 900 -> 0.5
            else -> 0.0
        }
    }

    /** 연속주행: 60분 이하 0, 180분 이상 1, 사이는 선형. */
    fun driveFraction(drivingMs: Long): Double = ramp(drivingMs / 60_000.0, 60.0, 180.0)

    /** 속도 변동성(잔차 표준편차 km/h): 2 이하 0, 8 이상 1. */
    fun speedFraction(sd: Float): Double = ramp(sd.toDouble(), 2.0, 8.0)

    /** 내기순환 지속: 10분 이하 0, 30분 이상 1. */
    fun recircFraction(recircMs: Long): Double = ramp(recircMs / 60_000.0, 10.0, 30.0)

    /** 조향 보정 횟수(90초 창): 0회 0, 1회 0.5, 2회 이상 1. */
    fun steerFraction(count: Int): Double = (count / 2.0).coerceIn(0.0, 1.0)

    private fun ramp(v: Double, lo: Double, hi: Double) = ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0)
    private fun pts(f: Double, w: Int) = Math.round(f * w).toInt()

    fun assess(i: RiskInputs, thresholds: Thresholds): RiskResult {
        val t = pts(timeFraction(i.minuteOfDay), W_TIME)
        val d = pts(driveFraction(i.drivingMs), W_DRIVE)
        val st = i.steerCorrections?.let { pts(steerFraction(it), W_STEER) } ?: 0
        val sp = i.speedSdKmh?.let { pts(speedFraction(it), W_SPEED) } ?: 0
        val rc = i.recircMs?.let { pts(recircFraction(it), W_RECIRC) } ?: 0
        val factors = listOf(
            Factor("time", "시간대", t, W_TIME, "%02d:%02d".format(i.minuteOfDay / 60, i.minuteOfDay % 60)),
            Factor("drive", "연속 주행", d, W_DRIVE, "${i.drivingMs / 60_000}분"),
            Factor("steer", "조향 패턴", st, W_STEER, i.steerCorrections?.let { "보정 ${it}회" } ?: "수집 중"),
            Factor("speed", "속도 변동", sp, W_SPEED, i.speedSdKmh?.let { "편차 %.1fkm/h".format(it) } ?: "수집 중"),
            Factor("recirc", "내기순환", rc, W_RECIRC, i.recircMs?.let { "${it / 60_000}분" } ?: "꺼짐/미확인"),
        )
        val score = factors.sumOf { it.points }.coerceIn(0, 100)
        return RiskResult(score, thresholds.stageOf(score), factors, thresholds)
    }
}

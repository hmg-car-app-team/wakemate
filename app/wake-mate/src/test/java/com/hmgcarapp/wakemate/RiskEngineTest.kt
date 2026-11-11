package com.hmgcarapp.wakemate

import com.hmgcarapp.wakemate.domain.Calibration
import com.hmgcarapp.wakemate.domain.RiskEngine
import com.hmgcarapp.wakemate.domain.RiskInputs
import com.hmgcarapp.wakemate.domain.Stage
import com.hmgcarapp.wakemate.domain.Thresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskEngineTest {
    @Test fun timeFraction_boundaries() {
        val cases = listOf(0 to 0.5, 119 to 0.5, 120 to 1.0, 359 to 1.0, 360 to 0.4, 419 to 0.4, 420 to 0.0, 779 to 0.0, 780 to 0.5, 899 to 0.5, 900 to 0.0, 1439 to 0.0)
        cases.forEach { (m, exp) -> assertEquals("minute=$m", exp, RiskEngine.timeFraction(m), 1e-9) }
    }

    @Test fun timeFraction_clampsOutOfRangeMinutes() {
        assertEquals(0.5, RiskEngine.timeFraction(-5), 1e-9)   // 0분으로 보정
        assertEquals(0.0, RiskEngine.timeFraction(5000), 1e-9) // 1439분으로 보정
    }

    @Test fun driveFraction_isLinearBetween60And180Minutes() {
        fun f(min: Long) = RiskEngine.driveFraction(min * 60_000L)
        assertEquals(0.0, f(0), 1e-9); assertEquals(0.0, f(60), 1e-9)
        assertEquals(0.25, f(90), 1e-9); assertEquals(0.5, f(120), 1e-9)
        assertEquals(1.0, f(180), 1e-9); assertEquals(1.0, f(600), 1e-9)
    }

    @Test fun recircFraction_tenToThirtyMinutes() {
        fun f(min: Long) = RiskEngine.recircFraction(min * 60_000L)
        assertEquals(0.0, f(9), 1e-9); assertEquals(0.0, f(10), 1e-9)
        assertEquals(0.5, f(20), 1e-9); assertEquals(1.0, f(30), 1e-9); assertEquals(1.0, f(90), 1e-9)
    }

    @Test fun speedFraction_twoToEightKmh() {
        assertEquals(0.0, RiskEngine.speedFraction(1.9f), 1e-9)
        assertEquals(0.5, RiskEngine.speedFraction(5f), 1e-9)
        assertEquals(1.0, RiskEngine.speedFraction(8f), 1e-9)
        assertEquals(1.0, RiskEngine.speedFraction(20f), 1e-9)
    }

    @Test fun steerFraction_countsCorrections() {
        assertEquals(0.0, RiskEngine.steerFraction(0), 1e-9)
        assertEquals(0.5, RiskEngine.steerFraction(1), 1e-9)
        assertEquals(1.0, RiskEngine.steerFraction(2), 1e-9)
        assertEquals(1.0, RiskEngine.steerFraction(9), 1e-9)
    }

    @Test fun assess_sumsWeightedPoints_literalScore() {
        // 03:00(20) + 연속 120분(15) + 조향 2회(20) + 속도편차 5km/h(7.5->8) + 내기순환 20분(7.5->8) = 71
        val r = RiskEngine.assess(RiskInputs(180, 120 * 60_000L, 20 * 60_000L, 2, 5f), Thresholds.of(0))
        assertEquals(71, r.score)
        assertEquals(Stage.S2, r.stage)
        assertEquals(listOf(20, 15, 20, 8, 8), r.factors.map { it.points })
        assertEquals(listOf("time", "drive", "steer", "speed", "recirc"), r.factors.map { it.key })
    }

    @Test fun assess_maxIs100_andWeightsSum() {
        val r = RiskEngine.assess(RiskInputs(200, 600 * 60_000L, 90 * 60_000L, 5, 20f), Thresholds.of(0))
        assertEquals(100, r.score)
        assertEquals(Stage.S3, r.stage)
        assertEquals(100, RiskEngine.W_TIME + RiskEngine.W_DRIVE + RiskEngine.W_STEER + RiskEngine.W_SPEED + RiskEngine.W_RECIRC)
    }

    @Test fun assess_missingSignalsContributeZero_andShowCollecting() {
        val r = RiskEngine.assess(RiskInputs(600, 0L, null, null, null), Thresholds.of(0))
        assertEquals(0, r.score)
        assertEquals(Stage.NONE, r.stage)
        assertEquals("수집 중", r.factors.first { it.key == "steer" }.note)
        assertEquals("수집 중", r.factors.first { it.key == "speed" }.note)
        assertEquals("꺼짐/미확인", r.factors.first { it.key == "recirc" }.note)
    }

    @Test fun stageBoundaries_baseThresholds() {
        val t = Thresholds.of(0)
        assertEquals(Triple(40, 60, 80), Triple(t.s1, t.s2, t.s3))
        assertEquals(Stage.NONE, t.stageOf(39)); assertEquals(Stage.S1, t.stageOf(40)); assertEquals(Stage.S1, t.stageOf(59))
        assertEquals(Stage.S2, t.stageOf(60)); assertEquals(Stage.S2, t.stageOf(79)); assertEquals(Stage.S3, t.stageOf(80))
    }

    @Test fun calibration_raisesThresholdsByThreePerFeedback_capsAtFifteen() {
        var c = Calibration()
        val seen = ArrayList<Int>()
        repeat(7) { c = c.afterNotSleepy(); seen += c.offset }
        assertEquals(listOf(3, 6, 9, 12, 15, 15, 15), seen)
        assertEquals(7, c.feedbackCount)
        assertEquals(Triple(55, 75, 95), Triple(c.thresholds.s1, c.thresholds.s2, c.thresholds.s3))
    }

    @Test fun calibration_sameScoreFallsToLowerStage() {
        val inputs = RiskInputs(180, 120 * 60_000L, 20 * 60_000L, 2, 5f) // 점수 71
        assertEquals(Stage.S2, RiskEngine.assess(inputs, Calibration(0).thresholds).stage)
        assertEquals(Stage.S2, RiskEngine.assess(inputs, Calibration(6).thresholds).stage)  // s2=66
        assertEquals(Stage.S1, RiskEngine.assess(inputs, Calibration(12).thresholds).stage) // s2=72, s1=52
        assertEquals(Stage.S1, RiskEngine.assess(inputs, Calibration(15).thresholds).stage) // s1=55
    }

    @Test fun thresholds_ofClampsOffset() {
        assertEquals(40, Thresholds.of(-10).s1)
        assertEquals(55, Thresholds.of(99).s1)
    }

    @Test fun nightDrivingAlone_isStageOneAtMost() {
        // 시간대만으로는 위험 단계로 올라가지 않는다(20점)
        val r = RiskEngine.assess(RiskInputs(240, 0L, null, null, null), Thresholds.of(0))
        assertEquals(20, r.score)
        assertTrue(r.stage == Stage.NONE)
    }
}

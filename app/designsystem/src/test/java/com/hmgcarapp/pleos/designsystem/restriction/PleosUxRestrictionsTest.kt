package com.hmgcarapp.pleos.designsystem.restriction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 프리셋이 Pleos "지역별 주행 규제 정책" 표와 맞는지 확인합니다. */
class PleosUxRestrictionsTest {

    @Test
    fun parkedAllowsEverything() {
        val r = PleosUxRestrictions.Parked
        assertFalse(r.noKeyboard || r.noSetup || r.noVideo || r.limitContent)
    }

    @Test
    fun flagsIgnoredWhenDistractionOptimizationNotRequired() {
        val r = PleosUxRestrictions(requiresDistractionOptimization = false, activeRestrictions = PleosUxRestrictions.NO_VIDEO)
        assertFalse(r.noVideo)
    }

    @Test
    fun restOfWorldBlocksSetupVideoAndLongContentButAllowsKeyboard() {
        val r = PleosUxRestrictions.DrivingRestOfWorld
        assertTrue(r.noSetup)
        assertTrue(r.noVideo)
        assertTrue(r.limitContent)
        assertFalse(r.noKeyboard)
        assertFalse(r.noDialpad)
    }

    @Test
    fun northAmericaAlsoBlocksKeyboardDialpadAndSearch() {
        val r = PleosUxRestrictions.DrivingNorthAmerica
        assertTrue(r.noKeyboard)
        assertTrue(r.noDialpad)
        assertTrue(r.noFiltering)
        assertTrue(r.noSetup)
    }
}

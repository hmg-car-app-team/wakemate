package com.hmgcarapp.wakemate

import com.hmgcarapp.wakemate.domain.HvacTargets
import com.hmgcarapp.wakemate.domain.ItemResult
import com.hmgcarapp.wakemate.domain.Stage
import com.hmgcarapp.wakemate.domain.WakeEffect
import com.hmgcarapp.wakemate.domain.WakeEvent
import com.hmgcarapp.wakemate.domain.WakeFlow
import com.hmgcarapp.wakemate.domain.WakeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 깨우기로 바꾼 시트 통풍·무드등은 휴식이나 정상 3분 뒤 원래 값으로 돌아가야 한다. */
class StimulusRestoreTest {
    private val blue = -16776961
    private val okBoth = listOf(ItemResult("seat", true, null), ItemResult("ambient", true, null))
    private fun s2(): WakeState = WakeFlow.reduce(WakeState(), WakeEvent.RiskUpdated(0, 65, Stage.S2)).first
    private fun applied(s: WakeState, results: List<ItemResult>, original: HvacTargets) =
        WakeFlow.reduce(s, WakeEvent.HvacApplied(1, results, HvacTargets(), true, original = original)).first

    @Test fun rest_restoresOnlyChangedItems_keepingFirstOriginal() {
        var s = applied(s2(), listOf(ItemResult("seat", true, null), ItemResult("ambient", false, 2)), HvacTargets(seatVent = 0, ambientColor = blue))
        assertEquals("실패한 무드등은 되돌릴 대상이 아니다", HvacTargets(seatVent = 0), s.restoreStimulus)
        s = applied(s, okBoth, HvacTargets(seatVent = 3, ambientColor = blue))
        assertEquals("이미 기억한 시트 원래 값(0)은 반복 경고 때 3으로 덮이지 않는다", HvacTargets(seatVent = 0, ambientColor = blue), s.restoreStimulus)
        val (after, fx) = WakeFlow.reduce(s, WakeEvent.Rested)
        assertEquals(listOf(WakeEffect.RestoreStimulus(HvacTargets(seatVent = 0, ambientColor = blue))), fx)
        assertNull(after.restoreStimulus)
    }

    @Test fun nothingChanged_restNeedsNoRestore() {
        assertTrue(WakeFlow.reduce(s2(), WakeEvent.Rested).second.isEmpty())
    }

    @Test fun episodeEndAfterThreeNormalMinutes_restoresOnce() {
        var s = applied(s2(), okBoth, HvacTargets(seatVent = 0, ambientColor = blue))
        s = WakeFlow.reduce(s, WakeEvent.RiskUpdated(100_000, 10, Stage.NONE)).first
        val (s1, early) = WakeFlow.reduce(s, WakeEvent.RiskUpdated(279_999, 10, Stage.NONE))
        assertTrue(early.isEmpty())
        val (s2, fx) = WakeFlow.reduce(s1, WakeEvent.RiskUpdated(280_000, 10, Stage.NONE))
        assertEquals(listOf(WakeEffect.RestoreStimulus(HvacTargets(seatVent = 0, ambientColor = blue))), fx)
        assertTrue(WakeFlow.reduce(s2, WakeEvent.RiskUpdated(290_000, 10, Stage.NONE)).second.isEmpty())
    }
}

package com.hmgcarapp.wakemate

import com.hmgcarapp.wakemate.domain.HvacCaps
import com.hmgcarapp.wakemate.domain.HvacNow
import com.hmgcarapp.wakemate.domain.HvacTargets
import com.hmgcarapp.wakemate.domain.ItemResult
import com.hmgcarapp.wakemate.domain.Stage
import com.hmgcarapp.wakemate.domain.StimulusState.ACTIVE
import com.hmgcarapp.wakemate.domain.StimulusState.CHECKING
import com.hmgcarapp.wakemate.domain.StimulusState.FAILED
import com.hmgcarapp.wakemate.domain.StimulusState.IDLE
import com.hmgcarapp.wakemate.domain.StimulusState.UNAVAILABLE
import com.hmgcarapp.wakemate.domain.StimulusState.UNSUPPORTED
import com.hmgcarapp.wakemate.domain.StimulusView
import com.hmgcarapp.wakemate.domain.WakeEvent
import com.hmgcarapp.wakemate.domain.WakeFlow
import com.hmgcarapp.wakemate.domain.WakeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 물리 자극 카드는 차량이 확인해 준 것만 '작동'으로 보여야 한다(단계·설정만으로 작동이라 하지 않는다). */
class StimulusViewTest {
    private val all = HvacCaps(recirc = true, fan = true, temp = true, fanMin = 1, fanMax = 5, seatVent = true, seatVentMax = 3, ambientColor = true, wheelHeat = true, wheelHeatMax = 3)

    private fun input(
        caps: HvacCaps = all, capsKnown: Boolean = true, unavailable: Boolean = false, now: HvacNow = HvacNow(),
        last: List<ItemResult> = emptyList(), alertStage: Stage? = null, auto: Boolean = true, beep: Boolean = true,
    ) = StimulusView.Input(capsKnown, unavailable, caps, now, last, alertStage, auto, beep)

    private fun states(i: StimulusView.Input) = StimulusView.of(i).map { it.state }

    @Test fun dangerStageAlone_neverClaimsHardwareActive() {
        // 위험 깨우기 중이어도 쓰기 결과가 없으면 공조·시트·열선은 대기. 경고음은 실제로 울리므로 작동
        assertEquals(listOf(IDLE, IDLE, IDLE, ACTIVE), states(input(alertStage = Stage.S3)))
        assertEquals(listOf(IDLE, IDLE, IDLE, IDLE), states(input(alertStage = Stage.S3, beep = false)))
        assertEquals("1단계는 경고음이 없다", listOf(IDLE, IDLE, IDLE, IDLE), states(input(alertStage = Stage.S1)))
    }

    @Test fun onlyConfirmedWritesAreActive_failuresShowAsFailed() {
        val mixed = listOf(ItemResult("recirc", true, null), ItemResult("fan", false, 2), ItemResult("seat", true, null), ItemResult("ambient", false, 50001))
        val items = StimulusView.of(input(last = mixed, beep = false))
        assertEquals(listOf(ACTIVE, ACTIVE, IDLE, FAILED), items.map { it.state })
        assertEquals(FAILED, StimulusView.of(input(last = listOf(ItemResult("seat", false, 4))))[1].state)
        assertEquals(FAILED, StimulusView.of(input(last = listOf(ItemResult("wheel", false, 4))))[2].state)
    }

    @Test fun unsupported_checking_unavailable_areDistinct() {
        assertEquals(listOf(IDLE, UNSUPPORTED, UNSUPPORTED, UNSUPPORTED), states(input(caps = HvacCaps(recirc = true, fan = true, temp = true))))
        assertEquals(listOf(CHECKING, CHECKING, CHECKING, CHECKING), states(input(caps = HvacCaps(), capsKnown = false)))
        assertEquals(listOf(UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE), states(input(caps = HvacCaps(), capsKnown = false, unavailable = true)))
    }

    @Test fun beepCountsEvenWhenAmbientUnsupported() {
        val card = StimulusView.of(input(caps = HvacCaps(recirc = true), alertStage = Stage.S2))[3]
        assertEquals(ACTIVE, card.state); assertEquals("경고음", card.label)
        assertEquals("칩 이름도 경고음", "경고음", card.chipLabel)
    }

    @Test fun wheelHeat_activeOnlyAfterVehicleConfirms_andShowsReadback() {
        val wheel = StimulusView.of(input(last = listOf(ItemResult("wheel", true, null)), now = HvacNow(wheelHeat = 3)))[2]
        assertEquals(ACTIVE, wheel.state)
        assertEquals("핸들 열선 3단", wheel.detail)
        assertEquals("열선", wheel.chipLabel)
        assertEquals(UNSUPPORTED, StimulusView.of(input(caps = all.copy(wheelHeat = false)))[2].state)
        assertEquals(listOf("공조", "시트", "열선", "무드등"), StimulusView.of(input()).map { it.chipLabel })
    }

    @Test fun autoControlOff_isIdleWithReason() {
        assertEquals("자동 조절 꺼짐", StimulusView.of(input(auto = false))[0].label)
    }

    @Test fun detailShowsVehicleReadback() {
        val items = StimulusView.of(input(now = HvacNow(true, false, 4, 22f, seatVent = 3)))
        assertEquals("외기 · 풍량 4단 · 22.0℃", items[0].detail)
        assertEquals("운전석 통풍 3단", items[1].detail)
    }

    @Test fun wakeFlow_keepsLatestResultPerKind_forCurrentAlert_andClearsOnRest() {
        var s = WakeFlow.reduce(WakeState(), WakeEvent.RiskUpdated(0, 65, Stage.S2)).first // 알림 id 1
        s = WakeFlow.reduce(s, WakeEvent.HvacApplied(1, listOf(ItemResult("recirc", false, 2), ItemResult("seat", true, null)), HvacTargets(), true)).first
        s = WakeFlow.reduce(s, WakeEvent.HvacApplied(1, listOf(ItemResult("recirc", true, null)), HvacTargets(), true)).first
        assertEquals(listOf(ItemResult("seat", true, null), ItemResult("recirc", true, null)), s.lastHvac)
        assertEquals("교체된 알림의 결과는 무시", s.lastHvac, WakeFlow.reduce(s, WakeEvent.HvacApplied(99, listOf(ItemResult("fan", true, null)), HvacTargets(), true)).first.lastHvac)
        s = WakeFlow.reduce(s, WakeEvent.Rested).first
        assertTrue(s.lastHvac.isEmpty())
    }
}

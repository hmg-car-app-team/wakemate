package com.hmgcarapp.wakemate

import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.core.voice.ListenResult
import com.hmgcarapp.core.voice.Listener
import com.hmgcarapp.core.voice.Speaker
import com.hmgcarapp.wakemate.app.FakeBeeper
import com.hmgcarapp.wakemate.app.Persisted
import com.hmgcarapp.wakemate.app.Settings
import com.hmgcarapp.wakemate.app.UiState
import com.hmgcarapp.wakemate.app.WakeRunner
import com.hmgcarapp.wakemate.domain.HvacCaps
import com.hmgcarapp.wakemate.domain.HvacNow
import com.hmgcarapp.wakemate.domain.HvacTargets
import com.hmgcarapp.wakemate.domain.ItemResult
import com.hmgcarapp.wakemate.domain.LatLon
import com.hmgcarapp.wakemate.domain.NaviAction
import com.hmgcarapp.wakemate.domain.NaviPort
import com.hmgcarapp.wakemate.domain.NaviReducer
import com.hmgcarapp.wakemate.domain.NaviStatus
import com.hmgcarapp.wakemate.domain.NaviText
import com.hmgcarapp.wakemate.domain.Quiz
import com.hmgcarapp.wakemate.domain.RouteAdvisor
import com.hmgcarapp.wakemate.domain.RouteMode
import com.hmgcarapp.wakemate.domain.Shelter
import com.hmgcarapp.wakemate.domain.ShelterDataset
import com.hmgcarapp.wakemate.domain.Stage
import com.hmgcarapp.wakemate.domain.Status
import com.hmgcarapp.wakemate.domain.WakeEffect
import com.hmgcarapp.wakemate.domain.WakeEvent
import com.hmgcarapp.wakemate.domain.WakeFlow
import com.hmgcarapp.wakemate.domain.WakeState
import com.hmgcarapp.wakemate.domain.WakeText
import com.hmgcarapp.wakemate.vehicle.HvacSnapshot
import com.hmgcarapp.wakemate.vehicle.WakeHvac
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 실제 WakeRunner + 가짜 TTS/STT/내비/공조/경고음으로 효과 -> 이벤트 되먹임 배선을 가상 시간에서 검증한다(컨트롤러의 dispatch 와 같은 루프). */
@OptIn(ExperimentalCoroutinesApi::class)
class WakeRunnerTest {
    private class FakeSpeaker(val onSpeak: suspend (String) -> Boolean = { delay(1_000); true }) : Speaker {
        val spoken = mutableListOf<String>(); var stops = 0
        override fun speak(text: String) = true
        override suspend fun speakAndAwait(text: String, timeoutMs: Long): Boolean { spoken += text; return onSpeak(text) }
        override fun stop() { stops++ }
        override fun release() {}
    }

    private class FakeListener(val onListen: suspend (Int) -> ListenResult) : Listener {
        var calls = 0; var cancelled = 0
        override suspend fun listenOnce(timeoutMs: Long): ListenResult {
            calls++
            try { return onListen(calls) } catch (e: CancellationException) { cancelled++; throw e }
        }
        override fun release() {}
    }

    private class FakeNavi : NaviPort {
        val requests = mutableListOf<Pair<String, NaviAction>>(); var status: NaviStatus = NaviStatus.Idle; private var seq = 0L
        override suspend fun routeToShelter(s: Shelter, action: NaviAction): Long {
            requests += s.id to action; val id = ++seq
            status = NaviStatus.Success(if (action == NaviAction.ADD_WAYPOINT) NaviText.WAYPOINT_ADDED else NaviText.ROUTE_STARTED, id); return id
        }
        override suspend fun awaitResult(id: Long, timeoutMs: Long): NaviStatus? = NaviReducer.resultFor(status, id)
        override fun clearFinished() { status = NaviReducer.settle(status) }
        override fun clearFinished(id: Long) { status = NaviReducer.settleFor(status, id) }
    }

    private class FakeHvac(val scope: CoroutineScope, var snap: HvacSnapshot, val replyAfterMs: Long? = 500, val throwOnApply: Boolean = false, val results: (HvacTargets) -> List<ItemResult> = { emptyList() }) : WakeHvac {
        override var onState: (HvacSnapshot) -> Unit = {}
        var applied: HvacTargets? = null; var applyCalls = 0
        override suspend fun run() {}
        override fun retryNow() {}
        override fun snapshot() = snap
        override fun apply(t: HvacTargets, onDone: (List<ItemResult>) -> Unit): Int {
            applyCalls++; applied = t
            if (throwOnApply) throw IllegalStateException("boom")
            if (replyAfterMs != null) scope.launch { delay(replyAfterMs); onDone(results(t)) }
            return t.kinds.size
        }
        override fun close() {}
    }

    private val near = Shelter("S000", "가상 샘플 쉼터 1", "샘플1로", "상행", LatLon(37.5010, 127.0390), 20, true)
    private val far = Shelter("S001", "가상 샘플 쉼터 2", "샘플1로", "하행", LatLon(37.4750, 127.0600), 12, false)
    private val here = LatLon(37.5000, 127.0390)
    private val goodSnap = HvacSnapshot(true, HvacCaps(true, true, true, 1, 5, 16f, 32f), HvacNow(true, true, 2, 24f), null, true)

    private class Rig(
        scope: CoroutineScope, val speaker: FakeSpeaker, val listener: FakeListener, val navi: FakeNavi, val hvac: FakeHvac, val beeper: FakeBeeper,
        mode: RouteMode, shelters: List<Shelter>, origin: LatLon?, beep: Boolean, drive: DriveState, mono: () -> Long, delayPlanMs: Long = 0L,
    ) {
        var ui = UiState(
            originAtMs = 0L,
            p = Persisted(settings = Settings(beep = beep)), drive = drive, dataset = ShelterDataset("", true, shelters), origin = origin,
            wake = WakeState(driving = drive == DriveState.DRIVING),
        )
        var notSleepy = 0
        val quizzes = mutableListOf<Pair<Quiz.Outcome, Long>>()
        val runner: WakeRunner = WakeRunner(
            scope, speaker, listener, navi, RouteAdvisor({ if (delayPlanMs > 0) delay(delayPlanMs); mode }), hvac, beeper, { ui }, mono, false, ::dispatch,
            onNotSleepy = { notSleepy++ }, onQuiz = { o, ms -> quizzes += o to ms },
        )
        fun dispatch(e: WakeEvent) {
            val (w, fx) = WakeFlow.reduce(ui.wake, e); ui = ui.copy(wake = w)
            fx.forEach { runner.run(it) }
        }
        val alert get() = ui.wake.alert
    }

    private fun TestScope.rig(
        speaker: FakeSpeaker = FakeSpeaker(), listener: FakeListener = FakeListener { ListenResult.Text("응") }, hvac: FakeHvac? = null,
        mode: RouteMode = RouteMode.NONE, shelters: List<Shelter> = listOf(far, near), origin: LatLon? = here, beep: Boolean = true,
        drive: DriveState = DriveState.DRIVING, delayPlanMs: Long = 0L,
    ) = Rig(backgroundScope, speaker, listener, FakeNavi(), hvac ?: FakeHvac(backgroundScope, goodSnap), FakeBeeper(), mode, shelters, origin, beep, drive, { testScheduler.currentTime }, delayPlanMs)

    @Test fun stage1_notSleepy_callsCalibrationOnce_speaksAck_andBannerExpiresAt25s() = runTest {
        val r = rig(listener = FakeListener { delay(2_000); ListenResult.Text("졸리지 않아요") })
        r.dispatch(WakeEvent.RiskUpdated(testScheduler.currentTime, 45, Stage.S1))
        advanceTimeBy(1); assertEquals(Status.SPEAKING, r.alert!!.status)
        advanceTimeBy(1_000); assertEquals(Status.LISTENING, r.alert!!.status)
        advanceTimeBy(2_000); assertEquals(1, r.notSleepy); assertEquals(Status.NOT_SLEEPY, r.alert!!.status)
        advanceTimeBy(1_000)
        assertEquals(listOf(WakeText.VENT_ASK, "알겠어요. 기준을 조금 높여 둘게요."), r.speaker.spoken)
        assertTrue(r.alert!!.finished)
        advanceTimeBy(24_999); assertTrue(r.alert != null)
        advanceTimeBy(2); assertNull(r.alert)
    }

    @Test fun stage1_accept_writesOnlyOutsideAir_andDrivingRestrictedIsReportedHonestly() = runTest {
        val hv = FakeHvac(backgroundScope, goodSnap, 500) { listOf(ItemResult("recirc", false, 50001)) }
        val r = rig(hvac = hv)
        r.dispatch(WakeEvent.RiskUpdated(testScheduler.currentTime, 45, Stage.S1))
        advanceTimeBy(1_100); advanceTimeBy(100)
        advanceTimeBy(2_000)
        assertEquals(HvacTargets(recirc = false), hv.applied)
        assertEquals(listOf(WakeText.VENT_ASK, "외기 순환은 주행 중에는 차량 정책상 앱이 공조를 바꿀 수 없어요. 창문을 열어 환기해 주세요"), r.speaker.spoken)
        assertEquals(Status.FAILED, r.alert!!.status)
    }

    @Test fun stage1_decline_doesNotTouchHvac() = runTest {
        val hv = FakeHvac(backgroundScope, goodSnap, 500)
        val r = rig(hvac = hv, listener = FakeListener { ListenResult.Text("아니") })
        r.dispatch(WakeEvent.RiskUpdated(testScheduler.currentTime, 45, Stage.S1))
        advanceTimeBy(3_000)
        assertEquals(0, hv.applyCalls); assertEquals(Status.DECLINED, r.alert!!.status)
    }

    @Test fun stage2_appliesPlanFromSnapshot_andReportsOnlyConfirmedItemsAfterSpeech() = runTest {
        val hv = FakeHvac(backgroundScope, goodSnap, 500) { listOf(ItemResult("recirc", true, null), ItemResult("fan", false, 2), ItemResult("temp", true, null)) }
        val r = rig(hvac = hv)
        r.dispatch(WakeEvent.RiskUpdated(testScheduler.currentTime, 65, Stage.S2))
        advanceTimeBy(1)
        assertEquals(listOf(Stage.S2), r.beeper.calls)
        assertEquals(HvacTargets(false, 4, 22.0f), hv.applied)
        advanceTimeBy(600)  // 공조 응답은 왔지만 안내 문구가 아직 끝나지 않았다 -> 결과는 아직 말하지 않는다
        assertEquals(1, r.speaker.spoken.size)
        advanceTimeBy(1_000)
        assertEquals("외기 순환으로 바꿨어요, 설정 온도를 낮췄어요. 바람 세기는 공조가 꺼져 있어 바꿀 수 없어요", r.speaker.spoken[1])
        advanceTimeBy(1_100)
        assertTrue(r.alert!!.finished); assertEquals(Status.PARTIAL, r.alert!!.status)
    }

    @Test fun stage2_hvacNotConnected_doesNotWrite_andSaysSo() = runTest {
        val hv = FakeHvac(backgroundScope, HvacSnapshot(connected = false, capsKnown = false))
        val r = rig(hvac = hv)
        r.dispatch(WakeEvent.RiskUpdated(testScheduler.currentTime, 65, Stage.S2))
        advanceTimeBy(3_000)
        assertEquals(0, hv.applyCalls)
        assertTrue(r.speaker.spoken[1], r.speaker.spoken[1].contains("연결하지 못했거나") && !r.speaker.spoken[1].contains("바꿨어요"))
    }

    @Test fun stage2_noCapabilityToWrite_doesNotWrite() = runTest {
        val readOnly = goodSnap.copy(caps = HvacCaps(false, false, false))
        val hv = FakeHvac(backgroundScope, readOnly)
        val r = rig(hvac = hv)
        r.dispatch(WakeEvent.RiskUpdated(testScheduler.currentTime, 65, Stage.S2))
        advanceTimeBy(3_000)
        assertEquals(0, hv.applyCalls)
        assertTrue(r.speaker.spoken[1].contains("앱이 바꿀 수 없어요"))
    }

    @Test fun stage2_hvacNeverAnswers_timesOutAndDoesNotClaimSuccess() = runTest {
        val hv = FakeHvac(backgroundScope, goodSnap, replyAfterMs = null)
        val r = rig(hvac = hv)
        r.dispatch(WakeEvent.RiskUpdated(testScheduler.currentTime, 65, Stage.S2))
        advanceTimeBy(1_100)       // 안내 문구 끝
        assertEquals(1, r.speaker.spoken.size)
        advanceTimeBy(9_000)       // 9초 대기 만료
        assertEquals("공조 응답을 받지 못했어요. 창문을 열어 환기해 주세요", r.speaker.spoken[1])
    }

    @Test fun stage2_lateSuccessWithin8s_isReportedAsSuccess() = runTest {
        val hv = FakeHvac(backgroundScope, goodSnap, 8_000) { listOf(ItemResult("recirc", true, null)) }
        val r = rig(hvac = hv)
        r.dispatch(WakeEvent.RiskUpdated(testScheduler.currentTime, 65, Stage.S2))
        advanceTimeBy(12_000)
        assertEquals("외기 순환으로 바꿨어요", r.speaker.spoken[1])
    }

    @Test fun stage2_hvacApplyThrows_releasesInFlight_andNextAlertWorks() = runTest {
        val r = rig(hvac = FakeHvac(backgroundScope, goodSnap, throwOnApply = true))
        r.dispatch(WakeEvent.RiskUpdated(testScheduler.currentTime, 65, Stage.S2))
        advanceTimeBy(3_000)
        assertEquals(Status.FAILED, r.alert!!.status); assertTrue(!r.ui.wake.inFlight)
        advanceTimeBy(300_000)
        r.dispatch(WakeEvent.RiskUpdated(testScheduler.currentTime, 65, Stage.S2)) // 5분 뒤 다음 경고 가능
        assertEquals(2L, r.alert!!.id)
    }

    @Test fun stage3_staleLocation_isTreatedAsUnknown() = runTest {
        val r = rig()
        advanceTimeBy(301_000)
        r.dispatch(WakeEvent.RiskUpdated(testScheduler.currentTime, 85, Stage.S3))
        advanceTimeBy(3_000)
        assertTrue(r.speaker.spoken[0].contains("현재 위치를 확인하지 못했어요")); assertTrue(r.navi.requests.isEmpty())
    }

    @Test fun stage2_alreadyAdequate_writesNothing() = runTest {
        val ok = goodSnap.copy(now = HvacNow(true, false, 5, 16f))
        val hv = FakeHvac(backgroundScope, ok)
        val r = rig(hvac = hv)
        r.dispatch(WakeEvent.RiskUpdated(testScheduler.currentTime, 65, Stage.S2))
        advanceTimeBy(3_000)
        assertEquals(0, hv.applyCalls)
        assertEquals("공조는 이미 환기에 알맞은 상태예요", r.speaker.spoken[1])
    }

    @Test fun beepSettingOff_noBeep() = runTest {
        val r = rig(beep = false)
        r.dispatch(WakeEvent.RiskUpdated(testScheduler.currentTime, 65, Stage.S2))
        advanceTimeBy(3_000)
        assertTrue(r.beeper.calls.isEmpty())
    }

    @Test fun stage3_picksNearestShelter_andActiveRouteAddsWaypoint() = runTest {
        val r = rig(mode = RouteMode.ACTIVE)
        r.dispatch(WakeEvent.RiskUpdated(testScheduler.currentTime, 85, Stage.S3))
        advanceTimeBy(1)
        assertEquals(listOf(Stage.S3), r.beeper.calls)
        advanceTimeBy(1_100)  // 안내 문구 발화 끝 -> 듣는 중
        assertTrue(r.speaker.spoken[0], r.speaker.spoken[0].contains("가상 샘플 쉼터 1") && r.speaker.spoken[0].contains("약 0.1킬로미터") && r.speaker.spoken[0].contains("경유지로 추가할까요?"))
        advanceTimeBy(2_000)
        assertEquals(listOf("S000" to NaviAction.ADD_WAYPOINT), r.navi.requests)
        assertEquals(Status.ACCEPTED, r.alert!!.status)
        assertEquals(NaviText.WAYPOINT_ADDED, r.speaker.spoken.last())
    }

    @Test fun stage3_withoutLocation_saysNoShelterInfo_andNeverRoutes() = runTest {
        val r = rig(origin = null)
        r.dispatch(WakeEvent.RiskUpdated(testScheduler.currentTime, 85, Stage.S3))
        advanceTimeBy(3_000)
        assertTrue(r.speaker.spoken[0].contains("현재 위치를 확인하지 못했어요"))
        assertTrue(r.navi.requests.isEmpty()); assertEquals(Status.NO_LOCATION, r.alert!!.status); assertEquals(0, r.listener.calls)
    }

    @Test fun stage3_unknownRoute_plainYesDoesNotRoute() = runTest {
        val r = rig(mode = RouteMode.UNKNOWN)
        r.dispatch(WakeEvent.RiskUpdated(testScheduler.currentTime, 85, Stage.S3))
        advanceTimeBy(3_000)
        assertTrue(r.navi.requests.isEmpty()); assertEquals(Status.NOT_ACCEPTED, r.alert!!.status)
    }

    @Test fun exceptionInSpeech_endsFailed_notStuck() = runTest {
        val r = rig(speaker = FakeSpeaker { throw IllegalStateException("tts") })
        r.dispatch(WakeEvent.RiskUpdated(testScheduler.currentTime, 45, Stage.S1))
        advanceTimeBy(1)
        assertEquals(Status.FAILED, r.alert!!.status); assertTrue(r.alert!!.finished)
        advanceTimeBy(25_001); assertNull(r.alert)
    }

    @Test fun quizResponseTime_isMeasuredByMonotonicClock() = runTest {
        val r = rig(listener = FakeListener { n -> if (n == 1) { delay(500); ListenResult.Text("퀴즈") } else { delay(4_000); ListenResult.Text("차표") } })
        r.dispatch(WakeEvent.RiskUpdated(testScheduler.currentTime, 45, Stage.S1))
        advanceTimeBy(10_000)
        assertEquals(listOf(Quiz.Outcome.CORRECT_FAST to 4_000L), r.quizzes)
        assertEquals(Status.QUIZ_PASSED, r.alert!!.status)
        assertEquals("끝말잇기 퀴즈예요. 기차, 다음 말은 무엇일까요?", r.speaker.spoken[1])
    }

    @Test fun slowQuizAnswer_escalatesToStage2WithBeepAndHvac() = runTest {
        val hv = FakeHvac(backgroundScope, goodSnap, 300) { listOf(ItemResult("recirc", true, null)) }
        val r = rig(hvac = hv, listener = FakeListener { n -> if (n == 1) { delay(500); ListenResult.Text("퀴즈") } else { delay(7_500); ListenResult.Text("차표") } })
        r.dispatch(WakeEvent.RiskUpdated(testScheduler.currentTime, 45, Stage.S1))
        advanceTimeBy(20_000)
        assertEquals(listOf(Quiz.Outcome.CORRECT_SLOW to 7_500L), r.quizzes)
        assertEquals(2L, r.alert!!.id); assertEquals(Stage.S2, r.alert!!.stage)
        assertEquals(listOf(Stage.S2), r.beeper.calls); assertEquals(1, hv.applyCalls)
    }

    @Test fun simHeard_replacesListening_andCancelsTheListenJob() = runTest {
        val r = rig(listener = FakeListener { delay(8_000); ListenResult.Timeout })
        r.dispatch(WakeEvent.RiskUpdated(testScheduler.currentTime, 85, Stage.S3))
        advanceTimeBy(1_200); assertEquals(Status.LISTENING, r.alert!!.status)
        r.dispatch(WakeEvent.SimHeard("응"))
        advanceTimeBy(100)
        assertEquals(1, r.listener.cancelled)
        assertEquals(listOf("S000" to NaviAction.REQUEST_ROUTE), r.navi.requests)
        advanceTimeBy(9_000)
        assertEquals("취소된 듣기의 시간 초과가 상태를 덮지 않는다", Status.ACCEPTED, r.alert!!.status)
    }

    @Test fun manualGuide_unknownRoute_asksConfirm_activeRoute_addsWaypoint() = runTest {
        val a = rig(mode = RouteMode.UNKNOWN, drive = DriveState.PARKED)
        a.runner.guide("S000"); advanceTimeBy(10)
        assertEquals("S000", a.ui.wake.replaceConfirm!!.shelterId); assertTrue(a.navi.requests.isEmpty())
        a.dispatch(WakeEvent.ReplaceConfirmed); advanceTimeBy(10)
        assertEquals(listOf("S000" to NaviAction.REQUEST_ROUTE), a.navi.requests)
        val b = rig(mode = RouteMode.ACTIVE, drive = DriveState.PARKED)
        b.runner.guide("S001"); advanceTimeBy(10)
        assertEquals(listOf("S001" to NaviAction.ADD_WAYPOINT), b.navi.requests)
    }

    @Test fun manualGuide_abortsIfDrivingStartsWhileCheckingRoute() = runTest {
        val r = rig(mode = RouteMode.NONE, drive = DriveState.PARKED, delayPlanMs = 1_000)
        r.runner.guide("S000")
        advanceTimeBy(500)
        r.ui = r.ui.copy(drive = DriveState.DRIVING)
        advanceTimeBy(1_000)
        assertTrue(r.navi.requests.isEmpty()); assertNull(r.ui.wake.replaceConfirm)
    }

    @Test fun cancelVoice_stopsSpeechAndPendingFlow() = runTest {
        val r = rig()
        r.dispatch(WakeEvent.RiskUpdated(testScheduler.currentTime, 45, Stage.S1))
        advanceTimeBy(500)
        r.dispatch(WakeEvent.AppReleased)
        advanceTimeBy(5_000)
        assertTrue(r.speaker.stops >= 1); assertEquals(0, r.listener.calls)
    }
}

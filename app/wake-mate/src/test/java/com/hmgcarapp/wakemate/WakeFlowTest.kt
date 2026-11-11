package com.hmgcarapp.wakemate

import com.hmgcarapp.wakemate.domain.Heard
import com.hmgcarapp.wakemate.domain.HvacTargets
import com.hmgcarapp.wakemate.domain.ItemResult
import com.hmgcarapp.wakemate.domain.LatLon
import com.hmgcarapp.wakemate.domain.NaviAction
import com.hmgcarapp.wakemate.domain.NaviStatus
import com.hmgcarapp.wakemate.domain.NaviText
import com.hmgcarapp.wakemate.domain.NearShelter
import com.hmgcarapp.wakemate.domain.Purpose
import com.hmgcarapp.wakemate.domain.Quiz
import com.hmgcarapp.wakemate.domain.RouteMode
import com.hmgcarapp.wakemate.domain.RoutePlans
import com.hmgcarapp.wakemate.domain.Shelter
import com.hmgcarapp.wakemate.domain.Stage
import com.hmgcarapp.wakemate.domain.Status
import com.hmgcarapp.wakemate.domain.WakeEffect
import com.hmgcarapp.wakemate.domain.WakeEvent
import com.hmgcarapp.wakemate.domain.WakeFlow
import com.hmgcarapp.wakemate.domain.WakeState
import com.hmgcarapp.wakemate.domain.WakeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeFlowTest {
    private class H(var s: WakeState = WakeState()) {
        fun send(e: WakeEvent): List<WakeEffect> { val (n, f) = WakeFlow.reduce(s, e); s = n; return f }
        val alert get() = s.alert
    }

    private val near = NearShelter(Shelter("S000", "가상 샘플 쉼터 1", "샘플1로", "상행", LatLon(37.5, 127.0), 20, true), 1.2)
    private val commonFx = listOf(WakeEffect.CancelVoice, WakeEffect.ClearNaviFinished(null))

    /** S1 을 now 에 발화 -> 응답 없음으로 끝낸다(상태를 정리하기 위한 도우미). */
    private fun H.fireAndFinishS1(now: Long, score: Int = 45) {
        send(WakeEvent.RiskUpdated(now, score, Stage.S1))
        send(WakeEvent.VoiceSpoken(alert!!.id, true)); send(WakeEvent.VoiceHeard(alert!!.id, Purpose.CHOICE, Heard.Timeout, 8000))
    }

    // ---------------- 1단계: 음성 ----------------
    @Test fun stage1_voiceFlow_notSleepy_calibrates_andExpires() {
        val h = H()
        val fx1 = h.send(WakeEvent.RiskUpdated(100_000, 45, Stage.S1))
        assertEquals(
            commonFx + WakeEffect.Speak(1, "졸음 위험지수 45점이에요. 환기하고 쉬어 가세요. 졸리지 않으면 괜찮아, 퀴즈를 원하면 퀴즈라고 말씀하세요."),
            fx1,
        )
        assertEquals(Status.SPEAKING, h.alert!!.status); assertEquals(Stage.S1, h.s.episode)
        assertEquals(listOf(WakeEffect.Listen(1, Purpose.CHOICE)), h.send(WakeEvent.VoiceSpoken(1, true)))
        assertEquals(Status.LISTENING, h.alert!!.status)
        val fx3 = h.send(WakeEvent.VoiceHeard(1, Purpose.CHOICE, Heard.Text("괜찮아"), 2000))
        assertEquals(listOf(WakeEffect.ApplyNotSleepy, WakeEffect.SpeakResult(1, WakeText.NOT_SLEEPY_ACK)), fx3)
        assertEquals(Status.NOT_SLEEPY, h.alert!!.status)
        assertEquals(listOf(WakeEffect.ScheduleExpiry(1, 25_000)), h.send(WakeEvent.ResultSpoken(1)))
        assertTrue(h.alert!!.finished); assertEquals(Status.NOT_SLEEPY, h.alert!!.status)
        assertEquals(listOf(WakeEffect.ClearNaviFinished(null)), h.send(WakeEvent.ExpiryElapsed(1)))
        assertNull(h.alert)
    }

    @Test fun stage1_voiceReplyOff_speaksOnlyAndNeverListens() {
        val h = H(WakeState(voiceReply = false))
        val fx = h.send(WakeEvent.RiskUpdated(0, 45, Stage.S1))
        assertEquals(WakeEffect.Speak(1, "졸음 위험지수 45점이에요. 환기하고 쉬어 가세요."), fx.last())
        val fx2 = h.send(WakeEvent.VoiceSpoken(1, true))
        assertTrue(fx2.none { it is WakeEffect.Listen })
        assertEquals(Status.VOICE_ONLY, h.alert!!.status); assertTrue(h.alert!!.finished)
    }

    @Test fun stage1_replies_declineNoReplyFailure() {
        fun run(heard: Heard): String {
            val h = H(); h.send(WakeEvent.RiskUpdated(0, 45, Stage.S1)); h.send(WakeEvent.VoiceSpoken(1, true))
            h.send(WakeEvent.VoiceHeard(1, Purpose.CHOICE, heard, 1000)); return h.alert!!.status
        }
        assertEquals(Status.DECLINED, run(Heard.Text("아니")))
        assertEquals(Status.DECLINED, run(Heard.Text("몰라")))
        assertEquals(Status.NO_REPLY, run(Heard.Timeout))
        assertEquals(Status.NO_REPLY, run(Heard.Text("  ")))
        assertEquals(Status.FAILED, run(Heard.Failure))
    }

    // ---------------- 발화 조건 ----------------
    @Test fun sameStage_repeatsOnlyAfterFiveMinutes() {
        val h = H(); h.fireAndFinishS1(100_000)
        assertTrue(h.send(WakeEvent.RiskUpdated(399_999, 45, Stage.S1)).isEmpty())
        val fx = h.send(WakeEvent.RiskUpdated(400_000, 45, Stage.S1))
        assertTrue(fx.any { it is WakeEffect.Speak }); assertEquals(2L, h.alert!!.id)
    }

    @Test fun higherStage_needsOneMinuteGap_thenFires() {
        val h = H(); h.fireAndFinishS1(100_000)
        assertTrue(h.send(WakeEvent.RiskUpdated(159_999, 65, Stage.S2)).isEmpty())
        val fx = h.send(WakeEvent.RiskUpdated(160_000, 65, Stage.S2))
        assertTrue(fx.contains(WakeEffect.Beep(2, Stage.S2))); assertEquals(Stage.S2, h.s.episode)
    }

    @Test fun lowerOrEqualStageWithinEpisode_doesNotFireImmediately() {
        val h = H(); h.fireAndFinishS1(0, 65) // 점수 65 에서 1단계로 시작한 것으로 가정
        h.send(WakeEvent.RiskUpdated(61_000, 65, Stage.S2)); h.send(WakeEvent.VoiceSpoken(2, true)); h.send(WakeEvent.HvacApplied(2, emptyList(), HvacTargets(), true)); h.send(WakeEvent.ResultSpoken(2))
        assertTrue(h.send(WakeEvent.RiskUpdated(130_000, 45, Stage.S1)).isEmpty()) // 이미 2단계를 거쳤으므로 1단계로 되돌아가 울리지 않는다
    }

    @Test fun nothingInFlight_neverInterruptsRunningAlert() {
        val h = H(); h.send(WakeEvent.RiskUpdated(0, 45, Stage.S1))
        assertTrue(h.send(WakeEvent.RiskUpdated(400_000, 85, Stage.S3)).isEmpty()) // 아직 말하는 중
        assertEquals(1L, h.alert!!.id)
    }

    @Test fun parked_neverFires_andFiresAfterDriveResumes() {
        val h = H(); h.send(WakeEvent.DriveChanged(false))
        assertTrue(h.send(WakeEvent.RiskUpdated(0, 90, Stage.S3)).isEmpty()); assertNull(h.alert)
        h.send(WakeEvent.DriveChanged(true))
        assertTrue(h.send(WakeEvent.RiskUpdated(1_000, 90, Stage.S3)).any { it is WakeEffect.PlanShelter })
    }

    @Test fun stageNone_neverFires() {
        assertTrue(H().send(WakeEvent.RiskUpdated(0, 10, Stage.NONE)).isEmpty())
    }

    @Test fun episodeResetsAfterThreeMinutesOfNormal() {
        val h = H(); h.fireAndFinishS1(100_000)
        h.send(WakeEvent.RiskUpdated(200_000, 10, Stage.NONE))
        h.send(WakeEvent.RiskUpdated(379_999, 10, Stage.NONE)); assertEquals(Stage.S1, h.s.episode)
        h.send(WakeEvent.RiskUpdated(380_000, 10, Stage.NONE)); assertEquals(Stage.NONE, h.s.episode)
        // 에피소드가 끝났으므로 반복 간격(5분)을 기다리지 않고 바로 1단계부터 다시 시작한다
        assertTrue(h.send(WakeEvent.RiskUpdated(390_000, 45, Stage.S1)).any { it is WakeEffect.Speak })
    }

    @Test fun normalBlip_resetsNoneTimerWhenRiskReturns() {
        val h = H(); h.fireAndFinishS1(100_000)
        h.send(WakeEvent.RiskUpdated(200_000, 10, Stage.NONE)); h.send(WakeEvent.RiskUpdated(300_000, 45, Stage.S1)) // 정상 구간이 끊김
        h.send(WakeEvent.RiskUpdated(450_000, 10, Stage.NONE))
        h.send(WakeEvent.RiskUpdated(600_000, 10, Stage.NONE)); assertEquals("150초뿐이라 아직", Stage.S1, h.s.episode)
    }

    @Test fun rested_clearsEpisodeAndGap() {
        val h = H(); h.fireAndFinishS1(100_000)
        h.send(WakeEvent.Rested)
        assertTrue(h.send(WakeEvent.RiskUpdated(101_000, 45, Stage.S1)).any { it is WakeEffect.Speak })
    }

    // ---------------- 퀴즈 ----------------
    private fun H.toQuizListening(): H {
        send(WakeEvent.RiskUpdated(100_000, 45, Stage.S1)); send(WakeEvent.VoiceSpoken(1, true))
        send(WakeEvent.VoiceHeard(1, Purpose.CHOICE, Heard.Text("퀴즈"), 1000))
        send(WakeEvent.VoiceSpoken(1, true))
        return this
    }

    @Test fun quiz_askThenListen_thenPass_snoozesFiveMinutes() {
        val h = H()
        h.send(WakeEvent.RiskUpdated(100_000, 45, Stage.S1)); h.send(WakeEvent.VoiceSpoken(1, true))
        val ask = h.send(WakeEvent.VoiceHeard(1, Purpose.CHOICE, Heard.Text("퀴즈"), 1000))
        assertEquals(listOf(WakeEffect.Speak(1, "끝말잇기 퀴즈예요. 기차, 다음 말은 무엇일까요?")), ask)
        assertEquals("기차", h.alert!!.quizWord)
        assertEquals(listOf(WakeEffect.Listen(1, Purpose.QUIZ)), h.send(WakeEvent.VoiceSpoken(1, true)))
        val fx = h.send(WakeEvent.VoiceHeard(1, Purpose.QUIZ, Heard.Text("차표"), 3000))
        assertEquals(listOf(WakeEffect.RecordQuiz(Quiz.Outcome.CORRECT_FAST, 3000), WakeEffect.SpeakResult(1, WakeText.QUIZ_PASS)), fx)
        assertEquals(Status.QUIZ_PASSED, h.alert!!.status); assertEquals("정답 · 응답 3.0초", h.alert!!.detail)
        assertEquals(400_000L, h.s.snoozeUntilMs)
        h.send(WakeEvent.ResultSpoken(1))
        assertTrue("퀴즈 통과는 2단계로 올리지 않는다", h.alert!!.finished && h.s.seq == 1L)
        // 휴지 억제의 의미 있는 검증은 quizSnooze_* 테스트(반복 간격보다 길게 막는 시나리오)
    }

    @Test fun quiz_wrongOrSlowOrSilent_escalatesToStage2() {
        val cases = listOf(Heard.Text("사과") to Quiz.Outcome.WRONG, Heard.Text("차표") to Quiz.Outcome.CORRECT_SLOW, Heard.Timeout to Quiz.Outcome.NO_ANSWER)
        cases.forEach { (heard, outcome) ->
            val h = H().toQuizListening()
            val ms = if (outcome == Quiz.Outcome.CORRECT_SLOW) 7_500L else 2_000L
            val fx = h.send(WakeEvent.VoiceHeard(1, Purpose.QUIZ, heard, ms))
            assertEquals(WakeEffect.RecordQuiz(outcome, ms), fx[0]); assertEquals(Status.QUIZ_FAILED, h.alert!!.status)
            assertNull("실패해도 휴지는 없다", h.s.snoozeUntilMs)
            val up = h.send(WakeEvent.ResultSpoken(1))
            assertEquals(listOf(WakeEffect.ScheduleExpiry(1, 25_000)) + commonFx + listOf(WakeEffect.Beep(2, Stage.S2), WakeEffect.Speak(2, "졸음 위험지수 45점으로 높아요. 외기 순환과 바람 세기를 조절해 볼게요."), WakeEffect.ApplyHvac(2)), up)
            assertEquals(Stage.S2, h.alert!!.stage); assertEquals(Stage.S2, h.s.episode)
        }
    }

    @Test fun quizPractice_parkedOnly_noEscalationNoSnooze() {
        val h = H()
        assertTrue(h.send(WakeEvent.StartQuizPractice).isEmpty()); assertNull(h.alert) // 주행 중 무시
        h.send(WakeEvent.DriveChanged(false))
        assertEquals(listOf(WakeEffect.CancelVoice, WakeEffect.Speak(1, "끝말잇기 퀴즈예요. 기차, 다음 말은 무엇일까요?")), h.send(WakeEvent.StartQuizPractice))
        h.send(WakeEvent.VoiceSpoken(1, true))
        h.send(WakeEvent.VoiceHeard(1, Purpose.QUIZ, Heard.Text("사과"), 1000))
        assertNull(h.s.snoozeUntilMs)
        val fx = h.send(WakeEvent.ResultSpoken(1))
        assertEquals(listOf(WakeEffect.ScheduleExpiry(1, 25_000)), fx)
        assertTrue(h.send(WakeEvent.StartQuizPractice).any { it is WakeEffect.Speak }) // 끝난 뒤 다시 가능
        assertEquals("다음 단어", "사과", h.alert!!.quizWord)
    }

    @Test fun quizPractice_ignoredWhileAnotherAlertRuns() {
        val h = H(); h.send(WakeEvent.RiskUpdated(0, 45, Stage.S1)); h.send(WakeEvent.DriveChanged(false))
        assertTrue(h.send(WakeEvent.StartQuizPractice).isEmpty())
    }

    // ---------------- 2단계: 경고음 + 공조 ----------------
    private val planned = HvacTargets(false, 4, 22f)
    private val partial = listOf(ItemResult("recirc", true, null), ItemResult("fan", false, 2))
    private val partialText = "외기 순환으로 바꿨어요. 바람 세기는 공조가 꺼져 있어 바꿀 수 없어요"

    @Test fun stage2_effectsOrder_andSpeechThenHvacResult() {
        val h = H()
        val fx = h.send(WakeEvent.RiskUpdated(0, 65, Stage.S2))
        assertEquals(commonFx + listOf(WakeEffect.Beep(1, Stage.S2), WakeEffect.Speak(1, "졸음 위험지수 65점으로 높아요. 외기 순환과 바람 세기를 조절해 볼게요."), WakeEffect.ApplyHvac(1)), fx)
        assertTrue(h.send(WakeEvent.VoiceSpoken(1, true)).isEmpty()); assertEquals(Status.WORKING, h.alert!!.status)
        val r = h.send(WakeEvent.HvacApplied(1, partial, planned, true))
        assertEquals(listOf(WakeEffect.SpeakResult(1, partialText)), r)
        assertEquals(partialText, h.alert!!.detail)
        assertEquals(listOf(WakeEffect.ScheduleExpiry(1, 25_000)), h.send(WakeEvent.ResultSpoken(1)))
        assertEquals("일부 실패는 구분한다", Status.PARTIAL, h.alert!!.status)
    }

    @Test fun stage2_hvacResultBeforeSpeechEnds_waitsSoSentencesDoNotOverlap() {
        val h = H(); h.send(WakeEvent.RiskUpdated(0, 65, Stage.S2))
        assertTrue("말이 끝나기 전에는 결과를 말하지 않는다", h.send(WakeEvent.HvacApplied(1, partial, planned, true)).isEmpty())
        assertEquals(listOf(WakeEffect.SpeakResult(1, partialText)), h.send(WakeEvent.VoiceSpoken(1, true)))
    }

    @Test fun stage2_hvacNotConnected_saysSoHonestly() {
        val h = H(); h.send(WakeEvent.RiskUpdated(0, 65, Stage.S2)); h.send(WakeEvent.VoiceSpoken(1, true))
        val fx = h.send(WakeEvent.HvacApplied(1, emptyList(), HvacTargets(), false))
        val text = (fx.single() as WakeEffect.SpeakResult).text
        assertTrue(text, text.contains("연결하지 못했거나") && !text.contains("바꿨어요"))
    }

    @Test fun stage2_hvacControlOff_noHvacEffect_endsAfterSpeech() {
        val h = H(WakeState(hvacControl = false))
        val fx = h.send(WakeEvent.RiskUpdated(0, 65, Stage.S2))
        assertTrue(fx.none { it is WakeEffect.ApplyHvac })
        assertEquals("졸음 위험지수 65점으로 높아요. 창문을 열어 환기하고 가까운 쉼터에서 쉬어 가세요.", (fx.single { it is WakeEffect.Speak } as WakeEffect.Speak).text)
        h.send(WakeEvent.VoiceSpoken(1, true))
        assertEquals(Status.VOICE_ONLY, h.alert!!.status); assertTrue(h.alert!!.finished)
    }

    @Test fun staleIds_areIgnored() {
        val h = H(); h.send(WakeEvent.RiskUpdated(0, 65, Stage.S2))
        val before = h.s
        assertTrue(h.send(WakeEvent.HvacApplied(99, partial, planned, true)).isEmpty())
        assertTrue(h.send(WakeEvent.VoiceSpoken(99, true)).isEmpty())
        assertTrue(h.send(WakeEvent.ResultSpoken(99)).isEmpty())
        assertTrue(h.send(WakeEvent.ExpiryElapsed(99)).isEmpty())
        assertEquals(before, h.s)
    }

    // ---------------- 3단계: 쉼터 ----------------
    private fun H.toS3Listening(plan: RouteMode = RouteMode.NONE): H {
        send(WakeEvent.RiskUpdated(0, 85, Stage.S3))
        send(WakeEvent.ShelterPlanned(1, near, RoutePlans.decide(plan), sampleData = true))
        send(WakeEvent.VoiceSpoken(1, true))
        return this
    }

    @Test fun stage3_planThenSpeakWithSampleNotice_thenVoiceAcceptStartsNavi() {
        val h = H()
        val fx0 = h.send(WakeEvent.RiskUpdated(0, 85, Stage.S3))
        assertEquals(commonFx + listOf(WakeEffect.Beep(1, Stage.S3), WakeEffect.PlanShelter(1)), fx0)
        val fx1 = h.send(WakeEvent.ShelterPlanned(1, near, RoutePlans.decide(RouteMode.NONE), true))
        assertEquals(
            listOf(WakeEffect.Speak(1, "졸음 위험지수 85점으로 매우 높아요. 가까운 졸음쉼터, 가상 샘플 쉼터 1, 약 1.2킬로미터예요. 시연용 샘플 데이터예요. 안내를 시작할까요?")),
            fx1,
        )
        assertEquals(listOf(WakeEffect.Listen(1, Purpose.CONFIRM)), h.send(WakeEvent.VoiceSpoken(1, true)))
        assertEquals(listOf(WakeEffect.StartNavi(1, "S000", NaviAction.REQUEST_ROUTE)), h.send(WakeEvent.VoiceHeard(1, Purpose.CONFIRM, Heard.Text("응"), 1500)))
        assertEquals(Status.NAVIGATING, h.alert!!.status)
        val ok = h.send(WakeEvent.NaviResult(1, NaviStatus.Success(NaviText.ROUTE_STARTED, 5)))
        assertEquals(listOf(WakeEffect.SpeakResult(1, "쉼터 안내를 시작했어요")), ok)
        h.send(WakeEvent.ResultSpoken(1))
        assertEquals(Status.ACCEPTED, h.alert!!.status); assertTrue(h.alert!!.finished)
    }

    @Test fun stage3_activeRoute_addsWaypoint() {
        val h = H().toS3Listening(RouteMode.ACTIVE)
        assertTrue((h.alert!!.plan!!.ask) == "경유지로 추가할까요?")
        assertEquals(listOf(WakeEffect.StartNavi(1, "S000", NaviAction.ADD_WAYPOINT)), h.send(WakeEvent.VoiceHeard(1, Purpose.CONFIRM, Heard.Text("네"), 1000)))
    }

    @Test fun stage3_unknownRoute_plainYesDoesNotOverwrite_explicitDoes() {
        val a = H().toS3Listening(RouteMode.UNKNOWN)
        assertTrue(a.alert!!.plan!!.canOverwrite)
        assertTrue(a.send(WakeEvent.VoiceHeard(1, Purpose.CONFIRM, Heard.Text("응"), 500)).none { it is WakeEffect.StartNavi })
        assertEquals(Status.NOT_ACCEPTED, a.alert!!.status)
        val b = H().toS3Listening(RouteMode.UNKNOWN)
        assertEquals(listOf(WakeEffect.StartNavi(1, "S000", NaviAction.REQUEST_ROUTE)), b.send(WakeEvent.VoiceHeard(1, Purpose.CONFIRM, Heard.Text("바꿔줘"), 500)))
    }

    @Test fun stage3_declineTimeoutFailure() {
        fun run(heard: Heard) = H().toS3Listening().also { it.send(WakeEvent.VoiceHeard(1, Purpose.CONFIRM, heard, 100)) }.alert!!.status
        assertEquals(Status.DECLINED, run(Heard.Text("아니요")))
        assertEquals(Status.NO_REPLY, run(Heard.Timeout))
        assertEquals(Status.FAILED, run(Heard.Failure))
    }

    @Test fun stage3_noShelterAvailable_saysSoAndEnds() {
        val h = H(); h.send(WakeEvent.RiskUpdated(0, 85, Stage.S3))
        val fx = h.send(WakeEvent.ShelterPlanned(1, null, null, true))
        assertEquals(listOf(WakeEffect.Speak(1, "졸음 위험지수 85점으로 매우 높아요. 근처 졸음쉼터 정보를 찾지 못했어요. 가까운 휴게소나 안전한 곳에서 쉬어 가세요.")), fx)
        assertTrue(h.send(WakeEvent.VoiceSpoken(1, true)).none { it is WakeEffect.Listen })
        assertEquals(Status.NO_SHELTER, h.alert!!.status)
    }

    @Test fun stage3_voiceReplyOff_noQuestion() {
        val h = H(WakeState(voiceReply = false)); h.send(WakeEvent.RiskUpdated(0, 85, Stage.S3))
        val fx = h.send(WakeEvent.ShelterPlanned(1, near, RoutePlans.decide(RouteMode.NONE), true))
        assertTrue((fx.single() as WakeEffect.Speak).text.endsWith("안전한 곳에서 쉬어 가세요."))
        assertTrue(h.send(WakeEvent.VoiceSpoken(1, true)).none { it is WakeEffect.Listen })
    }

    @Test fun stage3_naviFailure_isReportedNotHidden() {
        val h = H().toS3Listening(); h.send(WakeEvent.VoiceHeard(1, Purpose.CONFIRM, Heard.Text("응"), 1))
        val fx = h.send(WakeEvent.NaviResult(1, NaviStatus.Failed("경로를 찾지 못했어요", 3)))
        assertEquals(listOf(WakeEffect.SpeakResult(1, "경로를 찾지 못했어요")), fx)
        assertEquals(Status.FAILED, h.alert!!.status)
        h.send(WakeEvent.NaviResult(1, null)) // 이미 결과를 말하는 중 -> 상태는 계속 FAILED
    }

    @Test fun naviNoResultText_isNeutral() {
        assertEquals("내비에서 안내 시작을 확인하지 못했어요. 내비 화면에서 경로를 확인해 주세요.", NaviText.NO_RESPONSE)
    }

    @Test fun naviNoResult_saysNoResponse() {
        val h = H().toS3Listening(); h.send(WakeEvent.VoiceHeard(1, Purpose.CONFIRM, Heard.Text("응"), 1))
        assertEquals(listOf(WakeEffect.SpeakResult(1, NaviText.NO_RESPONSE)), h.send(WakeEvent.NaviResult(1, null)))
    }

    // ---------------- 시연 주입 / 기타 ----------------
    @Test fun simHeard_actsLikeVoiceWhileListening_andWarnsOtherwise() {
        val h = H().toS3Listening()
        assertEquals(listOf(WakeEffect.StartNavi(1, "S000", NaviAction.REQUEST_ROUTE)), h.send(WakeEvent.SimHeard("응")))
        assertTrue(h.send(WakeEvent.SimHeard("응")).single() is WakeEffect.Warn) // 이미 듣는 중이 아님
    }

    @Test fun forceStage_startsEvenIfParked() {
        val h = H(WakeState(driving = false, score = 77))
        val fx = h.send(WakeEvent.ForceStage(Stage.S2))
        assertTrue(fx.contains(WakeEffect.Beep(1, Stage.S2))); assertTrue(h.alert!!.line.contains("77"))
        assertTrue(H().send(WakeEvent.ForceStage(Stage.NONE)).isEmpty())
    }

    @Test fun failedAlert_closesBanner() {
        val h = H(); h.send(WakeEvent.RiskUpdated(0, 45, Stage.S1))
        assertEquals(listOf(WakeEffect.ScheduleExpiry(1, 25_000)), h.send(WakeEvent.AlertFailed(1)))
        assertEquals(Status.FAILED, h.alert!!.status)
        assertTrue(h.send(WakeEvent.AlertFailed(1)).isEmpty()) // 이미 끝남
    }

    // ---------------- 주행 중 사용자 조작 차단(parkedOnly) ----------------
    @Test fun userEvents_areIgnoredWhileDriving_andWorkWhenParked() {
        val h = H(WakeState(replaceConfirm = null))
        val events = listOf(WakeEvent.StartQuizPractice, WakeEvent.Dismiss, WakeEvent.ReplaceAsked("S000"), WakeEvent.ReplaceConfirmed, WakeEvent.ReplaceCancelled, WakeEvent.ManualNavi("S000", NaviAction.REQUEST_ROUTE))
        events.forEach { e -> val before = h.s; assertTrue("$e", h.send(e).isEmpty()); assertEquals("$e", before, h.s) }
        h.send(WakeEvent.DriveChanged(false))
        assertEquals(listOf(WakeEffect.StartNavi(null, "S000", NaviAction.ADD_WAYPOINT)), h.send(WakeEvent.ManualNavi("S000", NaviAction.ADD_WAYPOINT)))
        h.send(WakeEvent.ReplaceAsked("S000")); assertEquals("S000", h.s.replaceConfirm!!.shelterId)
        assertEquals(listOf(WakeEffect.StartNavi(null, "S000", NaviAction.REQUEST_ROUTE)), h.send(WakeEvent.ReplaceConfirmed))
        assertNull(h.s.replaceConfirm)
    }

    @Test fun replacePopup_isDiscardedWhenDrivingStarts() {
        val h = H(WakeState(driving = false)); h.send(WakeEvent.ReplaceAsked("S000"))
        h.send(WakeEvent.DriveChanged(true)); assertNull(h.s.replaceConfirm)
    }

    @Test fun dismiss_clearsOnlyFinishedAlert() {
        val h = H(WakeState(driving = false)); h.send(WakeEvent.ForceStage(Stage.S1))
        h.send(WakeEvent.Dismiss); assertNotNull("진행 중에는 닫히지 않는다", h.alert)
        h.send(WakeEvent.VoiceSpoken(1, true)); h.send(WakeEvent.VoiceHeard(1, Purpose.CHOICE, Heard.Timeout, 1))
        h.send(WakeEvent.Dismiss); assertNull(h.alert)
    }

    @Test fun messages_onlyWhenParked() {
        val h = H(); h.send(WakeEvent.Notify("a")); assertNull(h.s.message)
        h.send(WakeEvent.DriveChanged(false)); h.send(WakeEvent.Notify("b")); assertEquals("b", h.s.message)
        h.send(WakeEvent.MessageConsumed); assertNull(h.s.message)
        h.send(WakeEvent.Notify("c")); h.send(WakeEvent.DriveChanged(true)); assertNull("주행 진입 시 토스트 폐기", h.s.message)
    }

    // ---------------- 리뷰 1라운드 ----------------
    /** t=0 에 1단계, t=250_000 에 퀴즈 통과 -> snooze=550_000, snoozeStage=S1. */
    private fun H.passedQuizAt250s(): H {
        send(WakeEvent.RiskUpdated(0, 45, Stage.S1)); send(WakeEvent.VoiceSpoken(1, true))
        send(WakeEvent.VoiceHeard(1, Purpose.CHOICE, Heard.Text("퀴즈"), 1000)); send(WakeEvent.VoiceSpoken(1, true))
        send(WakeEvent.RiskUpdated(250_000, 45, Stage.S1)) // 진행 중이라 발화 없음, 시각만 갱신
        send(WakeEvent.VoiceHeard(1, Purpose.QUIZ, Heard.Text("차표"), 3000)); send(WakeEvent.ResultSpoken(1))
        assertEquals(550_000L, s.snoozeUntilMs); assertEquals(Stage.S1, s.snoozeStage)
        return this
    }

    @Test fun quizSnooze_outlastsRepeatInterval_forSameStage_butAllowsHigherStages() {
        // 반복 간격(5분)만으로는 t=300_000 에 1단계가 다시 울린다 -> 휴지가 있어야만 억제된다
        assertTrue(H().passedQuizAt250s().send(WakeEvent.RiskUpdated(300_000, 45, Stage.S1)).isEmpty())
        assertTrue(H().passedQuizAt250s().send(WakeEvent.RiskUpdated(300_000, 65, Stage.S2)).any { it is WakeEffect.ApplyHvac })
        assertTrue(H().passedQuizAt250s().send(WakeEvent.RiskUpdated(300_000, 85, Stage.S3)).any { it is WakeEffect.PlanShelter })
        val h = H().passedQuizAt250s()
        assertTrue(h.send(WakeEvent.RiskUpdated(549_999, 45, Stage.S1)).isEmpty())
        assertTrue(h.send(WakeEvent.RiskUpdated(550_000, 45, Stage.S1)).any { it is WakeEffect.Speak })
    }

    @Test fun quizSnooze_suppressesLowerStageWhenEpisodeReachedS3() {
        val st = WakeState(episode = Stage.S3, lastAlertAtMs = 0, nowMs = 250_000, snoozeUntilMs = 550_000, snoozeStage = Stage.S3)
        assertTrue(H(st).send(WakeEvent.RiskUpdated(300_000, 65, Stage.S2)).isEmpty())
        assertTrue(H(st).send(WakeEvent.RiskUpdated(550_000, 65, Stage.S2)).any { it is WakeEffect.ApplyHvac })
    }

    @Test fun quizSnooze_survivesEpisodeResetAfterThreeNormalMinutes() {
        val h = H().passedQuizAt250s()
        h.send(WakeEvent.RiskUpdated(260_000, 10, Stage.NONE))
        h.send(WakeEvent.RiskUpdated(440_000, 10, Stage.NONE)); assertEquals(Stage.NONE, h.s.episode) // 에피소드는 초기화됨
        assertTrue("휴지가 남아 있어 1단계는 억제", h.send(WakeEvent.RiskUpdated(450_000, 45, Stage.S1)).isEmpty())
        assertTrue(h.send(WakeEvent.RiskUpdated(450_000, 65, Stage.S2)).any { it is WakeEffect.ApplyHvac })
    }

    @Test fun rested_clearsSnooze() {
        val h = H().passedQuizAt250s(); h.send(WakeEvent.Rested)
        assertNull(h.s.snoozeUntilMs); assertEquals(Stage.NONE, h.s.snoozeStage)
    }

    @Test fun quizQuestionTtsFailure_doesNotListen_noEscalation() {
        val h = H()
        h.send(WakeEvent.RiskUpdated(100_000, 45, Stage.S1)); h.send(WakeEvent.VoiceSpoken(1, true))
        h.send(WakeEvent.VoiceHeard(1, Purpose.CHOICE, Heard.Text("퀴즈"), 1000))
        val fx = h.send(WakeEvent.VoiceSpoken(1, false)) // 질문 TTS 실패
        assertEquals(listOf(WakeEffect.SpeakResult(1, "잘 듣지 못했어요.")), fx)
        assertEquals(Status.FAILED, h.alert!!.status)
        val end = h.send(WakeEvent.ResultSpoken(1))
        assertEquals(listOf(WakeEffect.ScheduleExpiry(1, 25_000)), end)
        assertEquals(Stage.S1, h.s.episode); assertNull(h.s.snoozeUntilMs)
    }

    @Test fun hvacBaseTemp_remembered_evenForReplacedAlert() {
        val h = H(); h.send(WakeEvent.RiskUpdated(0, 65, Stage.S2))
        h.send(WakeEvent.HvacApplied(99, emptyList(), HvacTargets(), true, baseTempC = 24f)) // id 불일치
        assertEquals(24f, h.s.baseTempC)
    }

    @Test fun stage3_locationUnknown_isDistinctFromNoShelterData() {
        val a = H(); a.send(WakeEvent.RiskUpdated(0, 85, Stage.S3))
        val fx = a.send(WakeEvent.ShelterPlanned(1, null, null, true, locationKnown = false))
        assertEquals(listOf(WakeEffect.Speak(1, "졸음 위험지수 85점으로 매우 높아요. 현재 위치를 확인하지 못했어요. 가까운 휴게소나 안전한 곳에서 쉬어 가세요.")), fx)
        a.send(WakeEvent.VoiceSpoken(1, true)); assertEquals(Status.NO_LOCATION, a.alert!!.status)
        val b = H(); b.send(WakeEvent.RiskUpdated(0, 85, Stage.S3)); b.send(WakeEvent.ShelterPlanned(1, null, null, true)); b.send(WakeEvent.VoiceSpoken(1, true))
        assertEquals(Status.NO_SHELTER, b.alert!!.status)
    }

    @Test fun repeat_firesCurrentStageEvenBelowEpisodeStage() {
        val h = H(); h.send(WakeEvent.RiskUpdated(0, 85, Stage.S3))
        h.send(WakeEvent.ShelterPlanned(1, null, null, true)); h.send(WakeEvent.VoiceSpoken(1, true)) // 쉼터 없음으로 종료
        assertTrue(h.send(WakeEvent.RiskUpdated(299_999, 65, Stage.S2)).isEmpty())
        val fx = h.send(WakeEvent.RiskUpdated(300_000, 65, Stage.S2))
        assertTrue(fx.contains(WakeEffect.Beep(2, Stage.S2))); assertEquals(Stage.S3, h.s.episode)
        h.send(WakeEvent.VoiceSpoken(2, true)); h.send(WakeEvent.HvacApplied(2, emptyList(), HvacTargets(), true)); h.send(WakeEvent.ResultSpoken(2))
        assertTrue(h.send(WakeEvent.RiskUpdated(599_999, 65, Stage.S2)).isEmpty())
        assertTrue(h.send(WakeEvent.RiskUpdated(600_000, 65, Stage.S2)).any { it is WakeEffect.ApplyHvac })
    }

    @Test fun quizSttFailure_isNotDrowsiness_noEscalationNoHvac() {
        val h = H().toQuizListening()
        val fx = h.send(WakeEvent.VoiceHeard(1, Purpose.QUIZ, Heard.Failure, 100))
        assertEquals(listOf(WakeEffect.SpeakResult(1, "잘 듣지 못했어요.")), fx)
        assertEquals(Status.FAILED, h.alert!!.status); assertFalse(h.alert!!.escalateAfter)
        val end = h.send(WakeEvent.ResultSpoken(1))
        assertEquals(listOf(WakeEffect.ScheduleExpiry(1, 25_000)), end)
        assertEquals(Stage.S1, h.s.episode); assertNull(h.s.snoozeUntilMs)
    }

    @Test fun quizSilenceTimeout_stillEscalates() {
        val h = H().toQuizListening()
        h.send(WakeEvent.VoiceHeard(1, Purpose.QUIZ, Heard.Timeout, 8000))
        assertTrue(h.send(WakeEvent.ResultSpoken(1)).any { it is WakeEffect.ApplyHvac })
    }

    @Test fun shelterLine_showsNameAndSampleNotice() {
        val h = H().toS3Listening()
        assertEquals("가상 샘플 쉼터 1 · 가상 샘플", h.alert!!.shelterLine)
        assertEquals("가상 샘플 쉼터 1", h.alert!!.copy(sampleData = false).shelterLine)
        assertNull(h.alert!!.copy(shelter = null).shelterLine)
    }

    @Test fun hvacAllFailed_isFailedStatus_andNotOverwrittenByDone() {
        val h = H(); h.send(WakeEvent.RiskUpdated(0, 65, Stage.S2)); h.send(WakeEvent.VoiceSpoken(1, true))
        h.send(WakeEvent.HvacApplied(1, listOf(ItemResult("fan", false, 2), ItemResult("recirc", false, 2)), planned, true))
        h.send(WakeEvent.ResultSpoken(1))
        assertEquals(Status.FAILED, h.alert!!.status)
    }

    @Test fun hvacBaseTemp_rememberedOnceUntilEpisodeEnds() {
        val h = H(); h.send(WakeEvent.RiskUpdated(0, 65, Stage.S2))
        h.send(WakeEvent.HvacApplied(1, emptyList(), HvacTargets(), true, baseTempC = 24f))
        h.send(WakeEvent.HvacApplied(1, emptyList(), HvacTargets(), true, baseTempC = 22f))
        assertEquals(24f, h.s.baseTempC)
        h.send(WakeEvent.Rested); assertNull(h.s.baseTempC)
    }

    @Test fun release_cancelsVoice() {
        assertEquals(listOf(WakeEffect.CancelVoice), H().send(WakeEvent.AppReleased))
    }

    @Test fun defaults_failClosed() {
        assertTrue(WakeState().driving)
        assertFalse(WakeState().inFlight)
    }
}

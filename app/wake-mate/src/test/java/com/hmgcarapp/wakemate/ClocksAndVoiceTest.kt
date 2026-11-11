package com.hmgcarapp.wakemate

import com.hmgcarapp.wakemate.domain.DriveClock
import com.hmgcarapp.wakemate.domain.DriveRestore
import com.hmgcarapp.wakemate.domain.Heard
import com.hmgcarapp.wakemate.domain.Quiz
import com.hmgcarapp.wakemate.domain.RecircClock
import com.hmgcarapp.wakemate.domain.RestAdvice
import com.hmgcarapp.wakemate.domain.RestEvent
import com.hmgcarapp.wakemate.domain.Stage
import com.hmgcarapp.wakemate.domain.VoiceIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClocksAndVoiceTest {
    private val MIN = 60_000L

    // ---- DriveClock ----
    @Test fun firstObservationDoesNotAccumulate() {
        val c = DriveClock()
        assertNull(c.tick(1_000_000, true))
        assertEquals(0L, c.drivingMs)
    }

    @Test fun drivingAccumulatesOnlyWhileDriving() {
        val c = DriveClock()
        c.tick(0, true)
        (1..10).forEach { c.tick(it * MIN, true) }
        assertEquals(10 * MIN, c.drivingMs)
        c.tick(11 * MIN, false)               // 직전 구간(10~11분)은 주행 중이었으므로 포함
        assertEquals(11 * MIN, c.drivingMs)
        c.tick(12 * MIN, false); c.tick(13 * MIN, false)
        assertEquals("정차 구간은 세지 않는다", 11 * MIN, c.drivingMs)
    }

    @Test fun shortStopKeepsAccumulation_longStopResetsAndReportsOnce() {
        val c = DriveClock()
        c.tick(0, true)
        (1..30).forEach { c.tick(it * MIN, true) }
        c.tick(31 * MIN, false)                       // 누적 31분
        (32..44).forEach { assertNull(c.tick(it * MIN, false)) } // 13분 정차: 아직
        c.tick(45 * MIN, true)                        // 14분째 재출발 -> 정차 카운트 초기화, 누적 유지
        assertEquals(31 * MIN, c.drivingMs)
        c.tick(46 * MIN, false)
        var ev: RestEvent? = null
        for (m in 47..70) c.tick(m * MIN, false).let { if (it != null) { assertNull("한 번만 보고", ev); ev = it } }
        assertEquals(RestEvent(drivingMsBefore = 32 * MIN, restMs = 15 * MIN), ev)
        assertEquals(0L, c.drivingMs)
    }

    @Test fun restBelowMinimumDrive_resetsSilently() {
        val c = DriveClock()
        c.tick(0, true); (1..5).forEach { c.tick(it * MIN, true) }
        c.tick(6 * MIN, false)
        var events = 0
        for (m in 7..30) if (c.tick(m * MIN, false) != null) events++
        assertEquals(0, events)           // 6분 주행은 휴식 기록 대상이 아님
        assertEquals(0L, c.drivingMs)     // 하지만 누적은 초기화
    }

    @Test fun simulatedDriveAndReset() {
        val c = DriveClock()
        c.addSimulated(90 * MIN); assertEquals(90 * MIN, c.drivingMs)
        c.addSimulated(-200 * MIN); assertEquals(0L, c.drivingMs)
        c.addSimulated(5 * MIN); c.reset(); assertEquals(0L, c.drivingMs)
    }

    @Test fun restoreSetsAccumulationOnly() {
        val c = DriveClock(); c.restore(42 * MIN); assertEquals(42 * MIN, c.drivingMs)
        c.restore(-5); assertEquals(0L, c.drivingMs)
    }

    @Test fun driveRestore_wallClockExpiry() {
        val saved = 1_000_000_000L
        assertEquals(30 * MIN, DriveRestore.restoredDrivingMs(30 * MIN, saved, saved + 14 * MIN + 59_000))
        assertEquals(0L, DriveRestore.restoredDrivingMs(30 * MIN, saved, saved + 15 * MIN))
        assertEquals(0L, DriveRestore.restoredDrivingMs(30 * MIN, saved, saved - 1)) // 벽시계가 뒤로 감김
        assertEquals(0L, DriveRestore.restoredDrivingMs(30 * MIN, 0L, saved))        // 저장 시각 없음
    }

    // ---- RecircClock ----
    @Test fun recirc_countsFromFirstObservedOn_resetsOnlyOnObservedOff() {
        val r = RecircClock()
        assertNull(r.durationMs(0))
        r.onState(true, 1_000); assertEquals(0L, r.durationMs(1_000))
        r.onState(true, 5_000); assertEquals(4_000L, r.durationMs(5_000)) // 시작 시각은 유지
        r.onState(false, 6_000); assertNull(r.durationMs(6_000))
        r.onState(true, 7_000); r.onState(null, 8_000); assertEquals("미확인(null)은 초기화하지 않는다", 1_000L, r.durationMs(8_000))
        r.onState(false, 9_000); assertNull(r.durationMs(9_000))
    }

    // ---- RestAdvice ----
    @Test fun restAdvice_text() {
        assertEquals("30분 안에 한 번 쉬어 가세요(연속 2시간 기준)", RestAdvice.next(90, Stage.S1))
        assertEquals("연속 130분 주행했어요. 지금 쉬는 것을 권해요", RestAdvice.next(130, Stage.NONE))
        assertEquals("졸음 위험이 높아요. 가까운 쉼터나 안전한 곳에서 바로 쉬세요", RestAdvice.next(5, Stage.S2))
    }

    // ---- VoiceIntent ----
    @Test fun accept_phrases() {
        listOf("응", "네", "네 안내해줘", "좋아요", "그래").forEach { assertTrue(it, VoiceIntent.isAccept(it)) }
        listOf("", "네?", "아니", "안 할래", "글쎄요", "응 아니 싫어").forEach { assertFalse(it, VoiceIntent.isAccept(it)) }
    }

    @Test fun overwriteRoute_requiresExplicitMultiSyllable() {
        assertFalse(VoiceIntent.isAccept("응", overwriteRoute = true))
        assertFalse(VoiceIntent.isAccept("네", overwriteRoute = true))
        assertTrue(VoiceIntent.isAccept("바꿔줘", overwriteRoute = true))
        assertTrue(VoiceIntent.isPlainYesOnly("네"))
        assertFalse(VoiceIntent.isPlainYesOnly("바꿔줘"))
    }

    @Test fun notSleepy_phrases() {
        listOf("졸리지 않아요", "안 졸려", "괜찮아", "괜찮아요", "멀쩡해").forEach { assertTrue(it, VoiceIntent.isNotSleepy(it)) }
        listOf("졸려요", "응", "퀴즈", "").forEach { assertFalse(it, VoiceIntent.isNotSleepy(it)) }
    }

    @Test fun quizIntent() {
        assertTrue(VoiceIntent.isQuiz("퀴즈 할래")); assertTrue(VoiceIntent.isQuiz("끝말잇기"))
        assertFalse(VoiceIntent.isQuiz("괜찮아"))
    }

    // ---- Quiz ----
    @Test fun quizCorrectness() {
        assertTrue(Quiz.isCorrect("기차", "차표"))
        assertTrue(Quiz.isCorrect("기차", "차 표"))
        assertFalse(Quiz.isCorrect("기차", "기차"))   // 같은 단어
        assertFalse(Quiz.isCorrect("기차", "차"))     // 한 글자
        assertFalse(Quiz.isCorrect("기차", "사과"))
        assertFalse(Quiz.isCorrect("고구마", ""))
    }

    @Test fun quizOutcomes_boundaryAtSevenSeconds() {
        assertEquals(Quiz.Outcome.CORRECT_FAST, Quiz.outcome("기차", Heard.Text("차표"), 3_000))
        assertEquals(Quiz.Outcome.CORRECT_FAST, Quiz.outcome("기차", Heard.Text("차표"), 7_000))
        assertEquals(Quiz.Outcome.CORRECT_SLOW, Quiz.outcome("기차", Heard.Text("차표"), 7_001))
        assertEquals(Quiz.Outcome.WRONG, Quiz.outcome("기차", Heard.Text("사과"), 1_000))
        assertEquals(Quiz.Outcome.NO_ANSWER, Quiz.outcome("기차", Heard.Text("  "), 1_000))
        assertEquals(Quiz.Outcome.NO_ANSWER, Quiz.outcome("기차", Heard.Timeout, 8_000))
        assertEquals(Quiz.Outcome.NO_ANSWER, Quiz.outcome("기차", Heard.Failure, 10))
        assertTrue(Quiz.alert(Quiz.Outcome.CORRECT_FAST)); assertFalse(Quiz.alert(Quiz.Outcome.CORRECT_SLOW))
    }

    @Test fun quizPick_cyclesAndHandlesNegative() {
        assertEquals("기차", Quiz.pick(0)); assertEquals("사과", Quiz.pick(1)); assertEquals("기차", Quiz.pick(Quiz.WORDS.size))
        assertEquals(Quiz.WORDS.last(), Quiz.pick(-1))
    }

    @Test fun quizWords_endWithConnectableSyllable() {
        // 두음법칙이 필요한 ㄹ/ㄴ/ㅇ 끝말은 정답 판정이 어려우므로 단어 목록에서 제외돼 있어야 한다
        Quiz.WORDS.forEach { assertFalse(it, it.last() in "리라려료류르니녀뇨누") }
    }
}

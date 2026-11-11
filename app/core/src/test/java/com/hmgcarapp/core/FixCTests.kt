package com.hmgcarapp.core

import ai.pleos.playground.stt.listener.ResultListener
import com.hmgcarapp.core.drive.DriveInputs
import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.core.drive.DriveStateCalculator
import com.hmgcarapp.core.drive.Gear
import com.hmgcarapp.core.sim.SimulationHub
import com.hmgcarapp.core.store.LocalJsonStore
import com.hmgcarapp.core.voice.AUTO_TIMEOUT
import com.hmgcarapp.core.voice.ListenResult
import com.hmgcarapp.core.voice.SpeechCoordinator
import com.hmgcarapp.core.voice.SttPort
import com.hmgcarapp.core.voice.TtsPort
import com.hmgcarapp.core.voice.listenOnceOn
import com.hmgcarapp.core.voice.speechTimeoutMs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

private class FakeTts(var accept: Boolean = true) : TtsPort {
    val spoken = mutableListOf<String>()
    var stops = 0
    var released = 0
    override fun speak(text: String): Boolean { spoken += text; return accept }
    override fun stop() { stops++ }
    override fun release() { released++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FixCTests {
    @get:Rule val tmp = TemporaryFolder()

    // ---------- K4 Voice ----------
    private fun ready(p: FakeTts) = SpeechCoordinator(p).also { it.onInitSuccess() }

    @Test fun ttsNormalCompletion() = runTest {
        val p = FakeTts(); val c = ready(p)
        val r = async { c.speakAndAwait("안녕", 5000) }
        runCurrent()
        assertEquals(listOf("안녕"), p.spoken)
        c.onDone()
        assertTrue(r.await())
    }

    @Test fun ttsTimeoutCallsStop() = runTest {
        val p = FakeTts(); val c = ready(p)
        val r = async { c.speakAndAwait("안녕", 100) }
        runCurrent(); advanceTimeBy(101); runCurrent()
        assertFalse(r.await())
        assertTrue("타임아웃 시 stop 호출", p.stops >= 1)
    }

    @Test fun ttsCancelCallsStop() = runTest {
        val p = FakeTts(); val c = ready(p)
        val job = launch { c.speakAndAwait("안녕", 100_000) }
        runCurrent()
        job.cancel(); job.join()
        assertTrue(p.stops >= 1)
        // 취소 후 새 발화는 정상 동작
        val r = async { c.speakAndAwait("다음", 5000) }
        runCurrent(); c.onStart(); c.onDone()
        assertTrue(r.await())
    }

    @Test fun staleStopFromOldUtteranceDoesNotCompleteNew() = runTest {
        val p = FakeTts(); val c = ready(p)
        val a = async { c.speakAndAwait("A", 100_000) }
        runCurrent()
        val b = async { c.speakAndAwait("B", 100_000) } // A 를 교체(끊음)
        runCurrent()
        assertFalse("교체된 A 는 false", a.await())
        assertEquals(listOf("A", "B"), p.spoken)
        c.onStop() // A 의 늦은 onStop
        runCurrent()
        assertFalse("B 가 늦은 onStop 으로 완료되면 안 됨", b.isCompleted)
        c.onStart(); c.onDone()
        assertTrue(b.await())
    }

    @Test fun stopCompletesWaiterImmediately() = runTest {
        val p = FakeTts(); val c = ready(p)
        val r = async { c.speakAndAwait("A", 100_000) }
        runCurrent()
        c.stop()
        runCurrent()
        assertFalse(r.await())
        assertEquals(0L, testScheduler.currentTime)
        // stop 후 도착한 이전 발화의 onStop 이 다음 발화를 완료시키지 못함
        val n = async { c.speakAndAwait("N", 100_000) }
        runCurrent()
        c.onStop()
        runCurrent()
        assertFalse(n.isCompleted)
        c.onStart(); c.onDone()
        assertTrue(n.await())
    }

    @Test fun initFailureReturnsFalseImmediately() = runTest {
        val p = FakeTts(); val c = SpeechCoordinator(p)
        val waiting = async { c.speakAndAwait("A", 9000) } // 초기화 전 대기
        runCurrent()
        c.onInitFailure()
        assertFalse(waiting.await())
        assertEquals(0L, testScheduler.currentTime)
        assertFalse(c.speakAndAwait("B", 9000))
        assertFalse(c.speak("C"))
        assertEquals(0L, testScheduler.currentTime)
        assertFalse(SpeechCoordinator(null).speakAndAwait("x", 9000))
    }

    @Test fun releaseCompletesWaiterAndBlocksFurtherSpeech() = runTest {
        val p = FakeTts(); val c = ready(p)
        val r = async { c.speakAndAwait("A", 100_000) }
        runCurrent()
        c.release()
        assertFalse(r.await())
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(1, p.released)
        assertFalse(c.speakAndAwait("B", 9000))
        c.release(); assertEquals(1, p.released)
    }

    @Test fun pendingSpeechBeforeInitIsSpokenAfterInit() = runTest {
        val p = FakeTts(); val c = SpeechCoordinator(p)
        val r = async { c.speakAndAwait("A", 100_000) }
        runCurrent()
        assertTrue(p.spoken.isEmpty())
        c.onInitSuccess()
        assertEquals(listOf("A"), p.spoken)
        c.onDone()
        assertTrue(r.await())
    }

    @Test fun defaultTimeoutIsLengthProportional() = runTest {
        assertEquals(6000L, speechTimeoutMs(""))
        assertEquals(6000L + 250L * 4, speechTimeoutMs("안녕하세요".dropLast(1)))
        assertEquals(25_000L, speechTimeoutMs("가".repeat(1000)))
        val p = FakeTts(); val c = ready(p)
        val r = async { c.speakAndAwait("가", AUTO_TIMEOUT) }
        runCurrent(); advanceTimeBy(6200); runCurrent()
        assertTrue("6250ms 전엔 아직 대기", !r.isCompleted)
        advanceTimeBy(100); runCurrent()
        assertFalse(r.await())
    }

    @Test fun listenOnceCleansUpOnErrorAndRequestException() = runTest {
        class Port(val onRequest: (ResultListener) -> Unit) : SttPort {
            val ls = mutableListOf<ResultListener>()
            var removed = 0; var stops = 0
            override fun addListener(l: ResultListener) { ls += l }
            override fun removeListener(l: ResultListener) { removed++ }
            override fun setLocale(locale: Locale) = Unit
            override fun request() { onRequest(ls.last()) }
            override fun stop() { stops++ }
        }
        val thrower = Port { throw IllegalStateException("boom") }
        val r1 = listenOnceOn(thrower, Locale.KOREA, 1000)
        assertTrue(r1 is ListenResult.Failure)
        assertEquals(1, thrower.removed); assertEquals(1, thrower.stops)
        val err = Port { it.onError(ai.pleos.playground.stt.constant.ErrorType.values().first()) }
        val r2 = listenOnceOn(err, Locale.KOREA, 1000)
        assertTrue(r2 is ListenResult.Failure)
        assertEquals(1, err.removed); assertEquals(1, err.stops)
    }

    // ---------- K3 LocalJsonStore ----------
    @Test fun concurrentSavesKeepValidJson() {
        val dir = tmp.newFolder("c")
        val st = LocalJsonStore(dir)
        val threads = 8; val per = 150
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        repeat(threads) { t ->
            pool.execute {
                start.await()
                try {
                    repeat(per) { i ->
                        st.save("same", JSONObject().put("t", t).put("i", i).put("pad", "x".repeat(2000)))
                        st.loadObject("same")?.let { o -> if (!o.has("t")) errors += AssertionError("partial") }
                    }
                } catch (e: Throwable) { errors += e }
            }
        }
        start.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue("오류: ${errors.firstOrNull()}", errors.isEmpty())
        val o = st.loadObject("same")
        assertNotNull("최종 파일이 유효 JSON", o)
        assertEquals(per - 1, o!!.getInt("i")) // 각 스레드의 마지막 쓰기 중 하나
        assertEquals(listOf("same.json"), dir.list()!!.toList()) // tmp 잔재 없음
    }

    @Test fun corruptFileIsQuarantined() {
        val dir = tmp.newFolder("q")
        val st = LocalJsonStore(dir)
        assertFalse(st.quarantineCorrupt("state")) // 없음
        st.saveText("state", "{broken")
        assertNull(st.loadObject("state")) // 기존 계약
        assertTrue(st.quarantineCorrupt("state"))
        assertFalse(st.exists("state"))
        assertEquals("{broken", File(dir, "state.json.corrupt").readText())
        // 덮어쓰기 허용
        st.saveText("state", "{again"); assertTrue(st.quarantineCorrupt("state"))
        assertEquals("{again", File(dir, "state.json.corrupt").readText())
        for (bad in listOf("../x", "a/b", "a.b", "")) {
            try { st.quarantineCorrupt(bad); throw AssertionError("should fail: $bad") } catch (_: IllegalArgumentException) {}
            try { st.saveText(bad, "{}"); throw AssertionError("should fail: $bad") } catch (_: IllegalArgumentException) {}
        }
    }

    // ---------- K8 DriveState ----------
    @Test fun driveFailSafeAndBoundaries() {
        fun s(g: Gear, v: Float? = null, ux: Boolean? = null, o: DriveState? = null) =
            DriveStateCalculator.compute(DriveInputs(g, v, ux), o).state
        assertEquals(DriveState.DRIVING, DriveStateCalculator.initial.state)
        assertEquals(DriveState.DRIVING, s(Gear.UNKNOWN))
        assertEquals(DriveState.DRIVING, s(Gear.PARK, 3.0f))
        assertEquals(DriveState.PARKED, s(Gear.PARK, 2.9f))
        assertEquals(DriveState.PARKED, s(Gear.PARK, 2.9f, false))
        assertEquals(DriveState.DRIVING, s(Gear.DRIVE, 0f, false))
        assertEquals(DriveState.DRIVING, s(Gear.PARK, 0f, true))
        assertEquals(DriveState.PARKED, s(Gear.PARK, 0f, false, DriveState.PARKED))
        assertEquals(DriveState.DRIVING, s(Gear.PARK, 0f, false, DriveState.DRIVING))
        assertEquals(DriveState.DRIVING, s(Gear.PARK, 50f, false, DriveState.PARKED))
        assertEquals(DriveState.DRIVING, s(Gear.UNKNOWN, null, null, DriveState.PARKED))
        // 속도만 0 으로 확인, 기어 미확인, ux=false -> 정차 신호가 있으므로 PARKED 유지
        assertEquals(DriveState.PARKED, s(Gear.UNKNOWN, 0f, false))
    }

    // ---------- K10 debounce ----------
    @Test fun simDebounce() {
        assertFalse(SimulationHub.shouldIgnore(null, 0, "A", 10))
        assertTrue(SimulationHub.shouldIgnore("A", 1000, "A", 1100))
        assertFalse(SimulationHub.shouldIgnore("A", 1000, "A", 1300))
        assertFalse(SimulationHub.shouldIgnore("A", 1000, "B", 1100))
    }
}

package com.hmgcarapp.core

import ai.pleos.playground.stt.listener.ResultListener
import com.hmgcarapp.core.drive.DriveInputs
import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.core.drive.DriveStateCalculator
import com.hmgcarapp.core.drive.Gear
import com.hmgcarapp.core.drive.GearVehicle
import com.hmgcarapp.core.drive.gearFlowOf
import com.hmgcarapp.core.store.LocalJsonStore
import com.hmgcarapp.core.store.StoreLoad
import com.hmgcarapp.core.voice.ListenResult
import com.hmgcarapp.core.voice.SpeechCoordinator
import com.hmgcarapp.core.voice.SttPort
import com.hmgcarapp.core.voice.TtsPort
import com.hmgcarapp.core.voice.listenOnceOn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

private class E1Stt : SttPort {
    var requests = 0
    var ls: ResultListener? = null
    override fun addListener(l: ResultListener) { ls = l }
    override fun removeListener(l: ResultListener) = Unit
    override fun setLocale(locale: Locale) = Unit
    override fun request() { requests++ }
    override fun stop() = Unit
}

private class E1Tts : TtsPort {
    var stops = 0
    override fun speak(text: String) = true
    override fun stop() { stops++ }
    override fun release() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class FixE1Tests {
    @get:Rule val tmp = TemporaryFolder()

    // ---------- M5 ----------
    @Test fun purgeRemovesAllThreeKinds() {
        val dir = tmp.newFolder("p"); val st = LocalJsonStore(dir)
        st.saveText("a", "{}")
        File(dir, "a.json.corrupt").writeText("x")
        File(dir, "a.json.123.tmp").writeText("x")
        File(dir, "b.json").writeText("{}")
        assertTrue(st.purge("a"))
        assertEquals(listOf("b.json"), dir.list()!!.toList())
        assertTrue("없던 것도 '남은 것 없음' 이므로 true", st.purge("a"))
    }

    @Test fun purgeAndCleanupRejectPathEscape() {
        val st = LocalJsonStore(tmp.newFolder("e"))
        for (f in listOf<() -> Any?>({ st.purge("../x") }, { st.cleanupStale("../x") }, { st.pruneCorrupt("a/b") }, { st.loadResult("..") })) {
            try { f(); org.junit.Assert.fail("should reject") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun cleanupStaleRemovesOnlyOldTmp() {
        val dir = tmp.newFolder("c"); val st = LocalJsonStore(dir)
        val old = File(dir, "a.json.1.tmp").apply { writeText("x"); setLastModified(1_000L) }
        val fresh = File(dir, "a.json.2.tmp").apply { writeText("x"); setLastModified(1_000_000L) }
        val other = File(dir, "b.json.1.tmp").apply { writeText("x"); setLastModified(1_000L) }
        assertEquals(1, st.cleanupStale("a", nowMs = 1_000_000L, maxAgeMs = 60_000L))
        assertFalse(old.exists()); assertTrue(fresh.exists()); assertTrue(other.exists())
    }

    @Test fun corruptReplacedExpiredAndRemovedAfterGoodSave() {
        val dir = tmp.newFolder("k"); var now = 10_000_000L
        val st = LocalJsonStore(dir) { now }
        val c = File(dir, "a.json.corrupt")
        File(dir, "a.json").writeText("{bad1"); assertTrue(st.quarantineCorrupt("a"))
        File(dir, "a.json").writeText("{bad2"); assertTrue(st.quarantineCorrupt("a"))
        assertEquals("{bad2", c.readText()) // 최대 1개, 새 손상으로 교체
        assertEquals(1, dir.list()!!.count { it.endsWith(".corrupt") })
        // 6일: 유지, 7일: 삭제
        now += 6 * 24 * 3600_000L; assertFalse(st.pruneCorrupt("a")); assertTrue(c.exists())
        now += 24 * 3600_000L; assertTrue(st.pruneCorrupt("a")); assertFalse(c.exists())
        // 정상 저장 성공 시 삭제
        File(dir, "a.json").writeText("{bad3"); st.quarantineCorrupt("a"); assertTrue(c.exists())
        st.saveText("a", "{}"); assertFalse(c.exists())
    }

    @Test fun loadResultDistinguishesMissingOkAndReadError() {
        val dir = tmp.newFolder("r"); val st = LocalJsonStore(dir)
        assertEquals(StoreLoad.Missing, st.loadResult("a"))
        st.saveText("a", "{\"x\":1}")
        assertEquals(StoreLoad.Ok("{\"x\":1}"), st.loadResult("a"))
        // 읽기 불가 시뮬레이션: name.json 이 디렉터리 -> 존재하지만 읽기 오류
        File(dir, "d.json").mkdirs()
        assertTrue(st.loadResult("d") is StoreLoad.ReadError)
        assertEquals(null, st.loadText("d")) // 기존 null 계약 유지
    }

    // ---------- M6 ----------
    private class GV(val id: Int, val log: MutableList<String>, val failInit: Boolean) : GearVehicle {
        var onErr: ((Throwable) -> Unit)? = null
        override fun initialize() { log += "init$id"; if (failInit) throw IllegalStateException("init fail") }
        override fun queryCurrent(onGear: (Gear) -> Unit, onError: (Throwable) -> Unit) { onErr = onError; onGear(Gear.PARK) }
        override fun register(onGear: (Gear) -> Unit, onError: (Throwable) -> Unit): () -> Unit { log += "reg$id"; return { log += "unreg$id" } }
        override fun release() { log += "release$id" }
    }

    @Test fun gearInitFailureRetriesWithBackoffThenSucceeds() = runTest {
        val log = mutableListOf<String>(); val sleeps = mutableListOf<Long>(); var n = 0
        val flow = gearFlowOf({ GV(++n, log, failInit = n < 3) }, { sleeps += it })
        val got = flow.take(3).toList()
        assertEquals(listOf(Gear.UNKNOWN, Gear.UNKNOWN, Gear.PARK), got)
        assertEquals(listOf(2000L, 4000L), sleeps)
        assertEquals(listOf("init1", "release1", "init2", "release2", "init3", "reg3"), log.take(6))
    }

    @Test fun gearConnectionFailureRecreatesVehicleReleasingFirst() = runTest {
        val log = mutableListOf<String>(); val vs = mutableListOf<GV>(); val sleeps = mutableListOf<Long>()
        val job = launch {
            gearFlowOf({ GV(vs.size + 1, log, false).also { vs += it } }, { sleeps += it; delay(it) })
                .collect { }
        }
        runCurrent()
        assertEquals(1, vs.size)
        vs[0].onErr!!(RuntimeException("Service connection lost"))
        runCurrent(); advanceTimeBy(2001); runCurrent()
        assertEquals(2, vs.size)
        val i = log.indexOf("release1"); assertTrue(i >= 0 && i < log.indexOf("init2"))
        assertTrue(log.indexOf("unreg1") < i) // unregister -> release 순서
        vs[1].onErr!!(IllegalArgumentException("other error")) // 연결 실패 계열 아님 -> 재생성 없음
        runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertEquals(2, vs.size)
        job.cancel(); runCurrent()
        assertTrue(log.contains("release2"))
    }

    // ---------- C5 ----------
    @Test fun driveSpeedNanInfNegative() {
        fun st(g: Gear, s: Float?) = DriveStateCalculator.compute(DriveInputs(gear = g, speedKmh = s, uxRequiresDistractionOptimization = false)).state
        assertEquals(DriveState.DRIVING, st(Gear.UNKNOWN, Float.NaN))
        assertEquals(DriveState.DRIVING, st(Gear.UNKNOWN, Float.POSITIVE_INFINITY))
        assertEquals(DriveState.DRIVING, st(Gear.UNKNOWN, -10f))
        assertEquals(DriveState.PARKED, st(Gear.UNKNOWN, -1f))
        assertEquals(DriveState.PARKED, st(Gear.PARK, Float.NaN))
        assertEquals(DriveState.DRIVING, st(Gear.UNKNOWN, 3.0f))
        assertEquals(DriveState.PARKED, st(Gear.UNKNOWN, 2.9f))
    }

    // ---------- C13 ----------
    @Test fun listenWaitsForInitThenRequests() = runTest {
        val p = E1Stt()
        val r = async { listenOnceOn(p, Locale.KOREA, 10_000, awaitReady = { delay(1000); true }) }
        runCurrent(); assertEquals(0, p.requests)
        advanceTimeBy(1001); runCurrent(); assertEquals(1, p.requests)
        p.ls!!.onUpdated("안녕", true)
        assertEquals(ListenResult.Text("안녕"), r.await())
    }

    @Test fun listenInitFailureOrTimeoutFailsWithoutRequest() = runTest {
        val p = E1Stt()
        val r1 = listenOnceOn(p, Locale.KOREA, 10_000, awaitReady = { false })
        assertEquals(ListenResult.Failure("STT 초기화 실패"), r1)
        val r2 = async { listenOnceOn(p, Locale.KOREA, 10_000, awaitReady = { delay(60_000); true }) }
        advanceTimeBy(5001); runCurrent()
        assertEquals(ListenResult.Failure("STT 초기화 실패"), r2.await())
        assertEquals(0, p.requests)
    }

    @Test fun idleStopDoesNotCallPortStopButBusyStopDoes() = runTest {
        val p = E1Tts(); val c = SpeechCoordinator(p).also { it.onInitSuccess() }
        c.stop(); assertEquals(0, p.stops)
        assertTrue(c.speak("hi")); c.stop(); assertEquals(1, p.stops)
    }
}

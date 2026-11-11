package com.hmgcarapp.core

import com.hmgcarapp.core.drive.DriveInputs
import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.core.drive.DriveStateCalculator
import com.hmgcarapp.core.drive.Gear
import com.hmgcarapp.core.drive.GearHold
import com.hmgcarapp.core.drive.GearVehicle
import com.hmgcarapp.core.drive.gearFlowOf
import com.hmgcarapp.core.store.LocalJsonStore
import com.hmgcarapp.core.voice.ListenResult
import com.hmgcarapp.core.voice.SttPort
import com.hmgcarapp.core.voice.listenOnceOn
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.listener.ResultListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

/** Fix-I: 비대칭 GearHold, 오류 기록+판정 원자화, 이전 세대 콜백 무시, 기어 연결 실패 경로, DriveState 조합, purge 잔존 감지, STT 경합. */
@OptIn(ExperimentalCoroutinesApi::class)
class FixITests {
    @get:Rule val tmp = TemporaryFolder()

    // ---- U2: PARK 은 짧게(5s), D/R/N 은 30s ----
    @Test fun gearHold_parkIsShort_driveReverseNeutralAreLong_boundaries() {
        fun resolveAt(g: Gear, dt: Long): Gear { val h = GearHold(30_000, 5_000); h.onGear(g); h.onError(1_000); return h.resolve(1_000 + dt) }
        assertEquals(Gear.PARK, resolveAt(Gear.PARK, 4_999))
        assertEquals(Gear.UNKNOWN, resolveAt(Gear.PARK, 5_000))
        for (g in listOf(Gear.DRIVE, Gear.REVERSE, Gear.NEUTRAL)) {
            assertEquals(g, resolveAt(g, 29_999)); assertEquals(Gear.UNKNOWN, resolveAt(g, 30_000))
        }
        val h = GearHold(30_000, 5_000); h.onGear(Gear.PARK)
        assertEquals(5_000L, h.onError(0).windowMs)
        val h2 = GearHold(30_000, 5_000); h2.onGear(Gear.DRIVE)
        assertEquals(30_000L, h2.onError(0).windowMs)
    }

    @Test fun gearHold_newEpisodeIdDiffersEvenAtSameTimestamp() {
        val h = GearHold(); h.onGear(Gear.DRIVE)
        val a = h.onError(100).newEpisode!!
        h.onGear(Gear.DRIVE)
        val b = h.onError(100).newEpisode!!
        assertTrue(a != b); assertFalse(h.isErrorEpisode(a)); assertTrue(h.isErrorEpisode(b))
        assertNull(h.onError(101).newEpisode)
    }

    private class V(val errs: MutableList<(Throwable) -> Unit>, val gears: MutableList<(Gear) -> Unit>, val failInit: Boolean = false, val reg: MutableList<Int>? = null) : GearVehicle {
        override fun initialize() { if (failInit) throw IllegalStateException("init fail") }
        override fun queryCurrent(onGear: (Gear) -> Unit, onError: (Throwable) -> Unit) { errs += onError; gears += onGear }
        override fun register(onGear: (Gear) -> Unit, onError: (Throwable) -> Unit): () -> Unit = { }
        override fun release() = Unit
    }

    @Test fun gearFlow_parkHeldOnly5s_thenUnknown_driveHeld30s() = runTest {
        val errs = mutableListOf<(Throwable) -> Unit>(); val gears = mutableListOf<(Gear) -> Unit>()
        val got = mutableListOf<Gear>()
        val job = launch { gearFlowOf({ V(errs, gears) }, { delay(it) }, { testScheduler.currentTime }).collect { got += it } }
        runCurrent()
        gears.first()(Gear.PARK); runCurrent()
        errs.first()(RuntimeException("glitch")); runCurrent()
        assertEquals(listOf(Gear.PARK), got)
        advanceTimeBy(4_999); runCurrent(); assertEquals(listOf(Gear.PARK), got)
        advanceTimeBy(2); runCurrent(); assertEquals(listOf(Gear.PARK, Gear.UNKNOWN), got) // 5초 후 UNKNOWN
        job.cancel()
    }

    // ---- r7R5-6: 이전 세대 콜백은 무시 ----
    @Test fun gearFlow_staleGenerationCallbacksAreIgnored() = runTest {
        val vs = mutableListOf<V>(); val e1 = mutableListOf<(Throwable) -> Unit>(); val g1 = mutableListOf<(Gear) -> Unit>()
        val e2 = mutableListOf<(Throwable) -> Unit>(); val g2 = mutableListOf<(Gear) -> Unit>()
        val got = mutableListOf<Gear>(); var n = 0
        val job = launch {
            gearFlowOf({ (if (++n == 1) V(e1, g1) else V(e2, g2)).also { vs += it } }, { delay(it) }, { testScheduler.currentTime }).collect { got += it }
        }
        runCurrent()
        e1.first()(RuntimeException("Service connection lost")) // 연결 실패 -> 재생성
        runCurrent(); advanceTimeBy(2_001); runCurrent()
        assertEquals(2, vs.size)
        g2.first()(Gear.PARK); runCurrent()
        val before = got.toList()
        g1.first()(Gear.DRIVE); runCurrent()                      // 폐기된 세대의 늦은 기어 -> 무시
        e1.first()(RuntimeException("Service connection lost")); runCurrent() // 늦은 연결 오류 -> 새 세대를 재생성시키지 않는다
        advanceTimeBy(60_000); runCurrent()
        assertEquals(before, got)
        assertEquals(2, vs.size)
        job.cancel()
    }

    // ---- gear 연결 실패 경로: init 예외가 계속되면 30초(D) 뒤 UNKNOWN ----
    @Test fun gearFlow_initFailureAfterDrive_holdsDrive30s_thenUnknown() = runTest {
        val errs = mutableListOf<(Throwable) -> Unit>(); val gears = mutableListOf<(Gear) -> Unit>()
        val got = mutableListOf<Gear>(); var n = 0
        val job = launch {
            gearFlowOf({ if (++n == 1) V(errs, gears) else V(mutableListOf(), mutableListOf(), failInit = true) },
                { delay(it) }, { testScheduler.currentTime }).collect { got += it }
        }
        runCurrent()
        gears.first()(Gear.DRIVE); runCurrent()
        errs.first()(RuntimeException("Service connection lost")); runCurrent() // 연결 끊김 -> 보류 시작
        assertEquals(listOf(Gear.DRIVE), got)
        advanceTimeBy(29_000); runCurrent()
        assertEquals(listOf(Gear.DRIVE), got)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(Gear.UNKNOWN, got.last())
        job.cancel()
    }

    @Test fun gearFlow_initFailureFromStart_isUnknownImmediately_andKeepsRetrying() = runTest {
        val got = mutableListOf<Gear>(); var n = 0
        val job = launch {
            gearFlowOf({ n++; V(mutableListOf(), mutableListOf(), failInit = true) }, { delay(it) }, { testScheduler.currentTime }).collect { got += it }
        }
        runCurrent(); assertEquals(Gear.UNKNOWN, got.first())
        advanceTimeBy(2_001); runCurrent(); assertEquals(2, n)
        job.cancel()
    }

    // ---- DriveState 조합: speed>=3 + UNKNOWN + UX null ----
    @Test fun driveState_speedAtLeast3_unknownGear_uxNull_isDriving_regardlessOfOverride() {
        fun s(g: Gear, v: Float?, ux: Boolean?, o: DriveState? = null) = DriveStateCalculator.compute(DriveInputs(g, v, ux), o).state
        assertEquals(DriveState.DRIVING, s(Gear.UNKNOWN, 3f, null))
        assertEquals(DriveState.DRIVING, s(Gear.UNKNOWN, 3f, false))
        assertEquals(DriveState.DRIVING, s(Gear.UNKNOWN, 2.9f, null))
        assertEquals(DriveState.DRIVING, s(Gear.UNKNOWN, null, null))
        assertEquals(DriveState.DRIVING, s(Gear.UNKNOWN, 0f, null, DriveState.PARKED)) // PARKED override 는 무시
        // P 보류가 끝난 뒤(UNKNOWN) 정차 확인이 되는 경우만 PARKED
        assertEquals(DriveState.PARKED, s(Gear.UNKNOWN, 0f, false))
    }

    // ---- purge: 잔존 감지 ----
    @Test fun purge_returnsFalse_whenCorruptOrTmpRemains_trueWhenAllGone() {
        val dir = tmp.newFolder("pg"); val st = LocalJsonStore(dir)
        st.saveText("a", "{}")
        File(dir, "a.json.corrupt").writeText("x"); File(dir, "a.json.1.tmp").writeText("x")
        assertTrue(st.purge("a")); assertTrue(dir.list()!!.isEmpty())
        // 삭제할 수 없는 .corrupt (비어 있지 않은 디렉터리)
        val stuck = File(dir, "a.json.corrupt"); stuck.mkdirs(); File(stuck, "child").writeText("x")
        assertFalse(st.purge("a"))
        stuck.deleteRecursively()
        // 삭제할 수 없는 tmp
        val stuckTmp = File(dir, "a.json.2.tmp"); stuckTmp.mkdirs(); File(stuckTmp, "child").writeText("x")
        assertFalse(st.purge("a"))
        stuckTmp.deleteRecursively()
        assertTrue(st.purge("a"))
    }

    // ---- STT: 콜백 겹침 ----
    private class RacySttPort : SttPort {
        var ls: ResultListener? = null
        override fun addListener(l: ResultListener) { ls = l }
        override fun removeListener(l: ResultListener) { ls = null }
        override fun setLocale(locale: Locale) = Unit
        override fun request() = Unit
        override fun stop() = Unit
    }

    @Test fun listenOnce_secondCallbackAfterResult_isIgnored_withoutException() = runTest {
        val p = RacySttPort()
        val r = async { listenOnceOn(p, Locale.KOREA, 10_000) }
        runCurrent()
        val l = p.ls!!
        l.onUpdated("안녕", true)
        l.onError(SttError.values().first()) // 두 번째 콜백(다른 스레드 가정): 예외 없이 무시
        l.onUpdated("다시", true)
        assertEquals(ListenResult.Text("안녕"), r.await())
    }
}

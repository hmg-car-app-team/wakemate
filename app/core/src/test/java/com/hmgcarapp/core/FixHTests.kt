package com.hmgcarapp.core

import com.hmgcarapp.core.drive.DriveInputs
import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.core.drive.DriveStateCalculator
import com.hmgcarapp.core.drive.Gear
import com.hmgcarapp.core.drive.GearHold
import com.hmgcarapp.core.drive.GearVehicle
import com.hmgcarapp.core.drive.gearFlowOf
import com.hmgcarapp.core.connection.isCarServiceFailure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class DeadObjectException : Exception("dead")
class RemoteException : Exception("remote")

/** Fix-H: 서비스 사망 판별, 기어 오류 유지(30초)·1회 재질의, UNKNOWN+UX null -> DRIVING. */
@OptIn(ExperimentalCoroutinesApi::class)
class FixHTests {
    @Test fun serviceFailure_detectsDeadObjectAndWrapped() {
        assertTrue(isCarServiceFailure(DeadObjectException()))
        assertTrue(isCarServiceFailure(InvocationTargetException(RemoteException())))
        assertTrue(isCarServiceFailure(InvocationTargetException(RuntimeException("x", IllegalStateException("not connected")))))
        assertTrue(isCarServiceFailure(IllegalStateException("anything")))
        assertFalse(isCarServiceFailure(IllegalArgumentException("bad property")))
        assertFalse(isCarServiceFailure(InvocationTargetException(SecurityException("perm"))))
    }

    @Test fun gearHold_keepsLastConfirmedFor30s_boundary() {
        val h = GearHold(30_000, 5_000)
        assertEquals(Gear.UNKNOWN, h.resolve(0)) // 확인된 기어 없음
        h.onGear(Gear.DRIVE)
        val r = h.onError(1_000)
        assertEquals(Gear.DRIVE, r.gear); assertEquals(30_000L, r.windowMs)
        val id = r.newEpisode!!
        assertNull(h.onError(2_000).newEpisode) // 같은 오류 구간
        assertEquals(Gear.DRIVE, h.resolve(30_999))
        assertEquals(Gear.UNKNOWN, h.resolve(31_000))
        h.onGear(Gear.PARK) // 복구되면 오류 구간 종료
        assertFalse(h.isErrorEpisode(id))
        assertEquals(Gear.UNKNOWN, GearHold().apply { onGear(Gear.UNKNOWN); onError(0) }.resolve(1))
    }

    @Test fun gearHold_retryOncePerEpisode() {
        val h = GearHold()
        assertTrue(h.tryRetry()); assertFalse(h.tryRetry())
        h.onGear(Gear.DRIVE)
        assertTrue(h.tryRetry())
    }

    private class V(val errs: MutableList<(Throwable) -> Unit>, val gears: MutableList<(Gear) -> Unit>, val queries: IntArray) : GearVehicle {
        override fun initialize() = Unit
        override fun queryCurrent(onGear: (Gear) -> Unit, onError: (Throwable) -> Unit) { queries[0]++; errs += onError; gears += onGear }
        override fun register(onGear: (Gear) -> Unit, onError: (Throwable) -> Unit): () -> Unit = { }
        override fun release() = Unit
    }

    @Test fun gearFlow_errorHoldsLastGear_thenUnknownAfter30s_andRetriesQueryOnce() = runTest {
        val errs = mutableListOf<(Throwable) -> Unit>(); val gears = mutableListOf<(Gear) -> Unit>(); val q = intArrayOf(0)
        val got = mutableListOf<Gear>()
        val job = launch {
            gearFlowOf({ V(errs, gears, q) }, { delay(it) }, { testScheduler.currentTime }).collect { got += it }
        }
        runCurrent()
        gears.first()(Gear.DRIVE); runCurrent()
        assertEquals(1, q[0])
        errs.first()(RuntimeException("temporary glitch")); runCurrent() // 비연결 오류
        assertEquals(listOf(Gear.DRIVE), got)        // UNKNOWN 을 바로 내보내지 않는다
        assertEquals(2, q[0])                        // queryCurrent 1회 재시도
        errs.last()(RuntimeException("again")); runCurrent()
        assertEquals(2, q[0])                        // 구간당 1회만
        advanceTimeBy(29_999); runCurrent()
        assertEquals(listOf(Gear.DRIVE), got)
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf(Gear.DRIVE, Gear.UNKNOWN), got) // 30초 지나도 복구 안 되면 UNKNOWN
        job.cancel()
    }

    @Test fun gearFlow_recoveryWithin30s_cancelsUnknown() = runTest {
        val errs = mutableListOf<(Throwable) -> Unit>(); val gears = mutableListOf<(Gear) -> Unit>(); val q = intArrayOf(0)
        val got = mutableListOf<Gear>()
        val job = launch {
            gearFlowOf({ V(errs, gears, q) }, { delay(it) }, { testScheduler.currentTime }).collect { got += it }
        }
        runCurrent()
        gears.first()(Gear.DRIVE); runCurrent()
        errs.first()(RuntimeException("glitch")); runCurrent()
        advanceTimeBy(10_000); gears.last()(Gear.DRIVE); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertTrue(Gear.UNKNOWN !in got)
        job.cancel()
    }

    @Test fun gearFlow_errorWithoutConfirmedGear_isUnknownImmediately() = runTest {
        val errs = mutableListOf<(Throwable) -> Unit>(); val gears = mutableListOf<(Gear) -> Unit>(); val q = intArrayOf(0)
        val got = mutableListOf<Gear>()
        val job = launch {
            gearFlowOf({ V(errs, gears, q) }, { delay(it) }, { testScheduler.currentTime }).collect { got += it }
        }
        runCurrent()
        errs.first()(RuntimeException("glitch")); runCurrent()
        assertEquals(listOf(Gear.UNKNOWN), got)
        job.cancel()
    }

    @Test fun unknownGear_uxNull_isDriving_evenWhenStopped() {
        fun s(g: Gear, v: Float?, ux: Boolean?) = DriveStateCalculator.compute(DriveInputs(g, v, ux)).state
        assertEquals(DriveState.DRIVING, s(Gear.UNKNOWN, 0f, null))
        assertEquals(DriveState.PARKED, s(Gear.UNKNOWN, 0f, false))
        assertEquals(DriveState.PARKED, s(Gear.PARK, 0f, null))
    }
}

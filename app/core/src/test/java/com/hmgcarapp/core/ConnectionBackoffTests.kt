package com.hmgcarapp.core

import com.hmgcarapp.core.drive.Gear
import com.hmgcarapp.core.drive.GearVehicle
import com.hmgcarapp.core.drive.gearFlowOf
import com.hmgcarapp.core.location.FusedPort
import com.hmgcarapp.core.location.GeoPoint
import com.hmgcarapp.core.location.locationFlowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 기어/위치 백오프(안정 연결 후에만 리셋), 권한 대기, 포트 재생성. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionBackoffTests {
    private class GV(val failAfterMs: Long?, val errs: MutableList<((Throwable) -> Unit)>) : GearVehicle {
        override fun initialize() = Unit
        override fun queryCurrent(onGear: (Gear) -> Unit, onError: (Throwable) -> Unit) { errs += onError }
        override fun register(onGear: (Gear) -> Unit, onError: (Throwable) -> Unit): () -> Unit = { }
        override fun release() = Unit
    }

    @Test fun gearBackoffNotResetByQuickDisconnects() = runTest {
        val sleeps = mutableListOf<Long>(); val errs = mutableListOf<(Throwable) -> Unit>()
        val job = launch {
            gearFlowOf({ GV(null, errs) }, { sleeps += it; delay(it) }, { testScheduler.currentTime }).collect { }
        }
        runCurrent()
        repeat(3) { // 연결 직후(0ms) 끊김 3회 -> 2,4,8초 (리셋 없이 증가)
            errs.last()(RuntimeException("service disconnected")); runCurrent()
            advanceTimeBy(sleeps.last() + 1); runCurrent()
        }
        assertEquals(listOf(2000L, 4000L, 8000L), sleeps)
        job.cancel()
    }

    @Test fun gearBackoffResetsAfterStableConnection() = runTest {
        val sleeps = mutableListOf<Long>(); val errs = mutableListOf<(Throwable) -> Unit>()
        val job = launch {
            gearFlowOf({ GV(null, errs) }, { sleeps += it; delay(it) }, { testScheduler.currentTime }).collect { }
        }
        runCurrent()
        errs.last()(RuntimeException("service disconnected")); runCurrent()   // 즉시 끊김 -> 2s
        advanceTimeBy(2001); runCurrent()
        errs.last()(RuntimeException("service disconnected")); runCurrent()   // 즉시 끊김 -> 4s
        advanceTimeBy(4001); runCurrent()
        advanceTimeBy(10_000); runCurrent()                                   // 10초 안정
        errs.last()(RuntimeException("service disconnected")); runCurrent()   // 안정 후 끊김 -> 2s 로 리셋
        assertEquals(listOf(2000L, 4000L, 2000L), sleeps)
        job.cancel()
    }

    private class FP(val log: MutableList<String>, val id: Int, val failInit: Boolean) : FusedPort {
        var onErr: ((Throwable) -> Unit)? = null
        var onLoc: ((GeoPoint) -> Unit)? = null
        override fun initialize() { log += "init$id"; if (failInit) throw IllegalStateException("init fail") }
        override fun register(onLocation: (GeoPoint) -> Unit, onError: (Throwable) -> Unit): () -> Unit {
            onLoc = onLocation; onErr = onError; log += "reg$id"; return { log += "unreg$id" }
        }
        override fun release() { log += "release$id" }
    }

    @Test fun locationWaitsForPermissionAndInitFailureBacksOff() = runTest {
        val log = mutableListOf<String>(); val sleeps = mutableListOf<Long>(); var n = 0; var checks = 0
        val ports = mutableListOf<FP>()
        val flow = locationFlowOf(
            { FP(log, ++n, n < 3).also { ports += it } },
            { if (++checks <= 3) listOf("perm") else emptyList() },
            { sleeps += it; delay(it) }, { testScheduler.currentTime },
        )
        val got = mutableListOf<GeoPoint>()
        val job = launch { flow.collect { got += it } }
        runCurrent(); advanceTimeBy(20_000); runCurrent()
        // 권한 대기 1초x3, 이후 init 실패 2회 -> 2s,4s 백오프, 3번째 성공
        assertEquals(listOf(1000L, 1000L, 1000L, 2000L, 4000L), sleeps)
        assertEquals(listOf("init1", "release1", "init2", "release2", "init3", "reg3"), log)
        ports.last().onLoc!!(GeoPoint(1.0, 2.0)); runCurrent()
        assertEquals(1, got.size)
        job.cancel(); runCurrent()
        assertTrue(log.containsAll(listOf("unreg3", "release3")))
    }

    @Test fun locationConnectionErrorRecreatesPort() = runTest {
        val log = mutableListOf<String>(); val ports = mutableListOf<FP>()
        val job = launch {
            locationFlowOf({ FP(log, ports.size + 1, false).also { ports += it } }, { emptyList() },
                { delay(it) }, { testScheduler.currentTime }).collect { }
        }
        runCurrent(); assertEquals(1, ports.size)
        ports[0].onErr!!(IllegalArgumentException("other")); runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertEquals(1, ports.size) // 연결 실패 계열이 아니면 재생성 없음
        ports[0].onErr!!(RuntimeException("service disconnected")); runCurrent(); advanceTimeBy(2001); runCurrent()
        assertEquals(2, ports.size)
        assertTrue(log.indexOf("release1") in 0 until log.indexOf("init2"))
        job.cancel()
    }
}

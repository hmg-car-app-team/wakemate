package com.hmgcarapp.core

import com.hmgcarapp.core.drive.Gear
import com.hmgcarapp.core.drive.GearVehicle
import com.hmgcarapp.core.drive.gearFlowOf
import com.hmgcarapp.core.location.FusedPort
import com.hmgcarapp.core.location.GeoPoint
import com.hmgcarapp.core.location.locationFlowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Refactor-A 특성화 테스트: 리팩터링 전 구현에 대해 먼저 통과시킨 뒤 새 구조로 옮겨도 그대로 통과해야 하는 계약.
 * 기어/위치 연결 루프의 백오프 상한(30초)·안정 경계(9,999/10,000ms)·한 세대의 중복 연결 오류.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionCharTests {
    private class GV(val failInit: Boolean, val errs: MutableList<(Throwable) -> Unit>) : GearVehicle {
        override fun initialize() { if (failInit) throw IllegalStateException("init fail") }
        override fun queryCurrent(onGear: (Gear) -> Unit, onError: (Throwable) -> Unit) { errs += onError }
        override fun register(onGear: (Gear) -> Unit, onError: (Throwable) -> Unit): () -> Unit = { }
        override fun release() = Unit
    }

    private fun TestScope.gearFlow(factory: () -> GearVehicle, sleeps: MutableList<Long>): Flow<Gear> =
        gearFlowOf(factory, { sleeps += it; delay(it) }, { testScheduler.currentTime })

    @Test fun gearBackoffDoublesAndCapsAt30s() = runTest {
        val sleeps = mutableListOf<Long>()
        val job = launch { gearFlow({ GV(true, mutableListOf()) }, sleeps).collect { } }
        runCurrent(); advanceTimeBy(200_000); runCurrent()
        assertEquals(listOf(2000L, 4000L, 8000L, 16_000L, 30_000L, 30_000L), sleeps.take(6))
        job.cancel()
    }

    @Test fun gearStableBoundary_9999NoReset_10000Reset() = runTest {
        // 재연결 시각(2000ms) 기준으로 정확히 aliveMs 뒤에 끊긴다 (재연결은 sleep 종료 직후)
        suspend fun run(aliveMs: Long): List<Long> {
            val sleeps = mutableListOf<Long>(); val errs = mutableListOf<(Throwable) -> Unit>()
            val job = launch { gearFlow({ GV(false, errs) }, sleeps).collect { } }
            runCurrent()
            errs.last()(RuntimeException("service disconnected")); runCurrent()
            advanceTimeBy(2000); runCurrent()                       // 재연결 시각
            advanceTimeBy(aliveMs); runCurrent()
            errs.last()(RuntimeException("service disconnected")); runCurrent()
            job.cancel(); runCurrent()
            return sleeps
        }
        assertEquals(listOf(2000L, 4000L), run(9_999))
        assertEquals(listOf(2000L, 2000L), run(10_000))
    }

    @Test fun gearDuplicateConnectionErrorsOfOneConnection_recreateOnce() = runTest {
        val sleeps = mutableListOf<Long>(); val errs = mutableListOf<(Throwable) -> Unit>(); var n = 0
        val job = launch { gearFlow({ n++; GV(false, errs) }, sleeps).collect { } }
        runCurrent()
        errs[0](RuntimeException("service disconnected")); errs[0](RuntimeException("binder died")); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals("같은 연결의 연결 오류가 겹쳐도 한 번만 교체", 2, n)
        assertEquals(listOf(2000L), sleeps)
        job.cancel()
    }

    private class FP(val failInit: Boolean, val errs: MutableList<(Throwable) -> Unit>) : FusedPort {
        override fun initialize() { if (failInit) throw IllegalStateException("init fail") }
        override fun register(onLocation: (GeoPoint) -> Unit, onError: (Throwable) -> Unit): () -> Unit { errs += onError; return { } }
        override fun release() = Unit
    }

    @Test fun locationBackoffCapsAt30s_andStableBoundary() = runTest {
        val sleeps = mutableListOf<Long>()
        val job = launch {
            locationFlowOf({ FP(true, mutableListOf()) }, { emptyList() }, { sleeps += it; delay(it) }, { testScheduler.currentTime }).collect { }
        }
        runCurrent(); advanceTimeBy(200_000); runCurrent()
        assertEquals(listOf(2000L, 4000L, 8000L, 16_000L, 30_000L, 30_000L), sleeps.take(6))
        job.cancel(); runCurrent()
        suspend fun run(aliveMs: Long): List<Long> {
            val s = mutableListOf<Long>(); val errs = mutableListOf<(Throwable) -> Unit>()
            val j = launch {
                locationFlowOf({ FP(false, errs) }, { emptyList() }, { s += it; delay(it) }, { testScheduler.currentTime }).collect { }
            }
            runCurrent()
            errs.last()(RuntimeException("service disconnected")); runCurrent()
            advanceTimeBy(2000); runCurrent()
            advanceTimeBy(aliveMs); runCurrent()
            errs.last()(RuntimeException("service disconnected")); runCurrent()
            j.cancel(); runCurrent()
            return s
        }
        assertEquals(listOf(2000L, 4000L), run(9_999))
        assertEquals(listOf(2000L, 2000L), run(10_000))
    }
}

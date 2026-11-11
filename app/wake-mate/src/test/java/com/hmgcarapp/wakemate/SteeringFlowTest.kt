package com.hmgcarapp.wakemate

import com.hmgcarapp.wakemate.vehicle.SteerVehicle
import com.hmgcarapp.wakemate.vehicle.steeringFlowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SteeringFlowTest {
    private class FakeVehicle(var angle: Float = 1.5f, val failFirstQuery: Boolean = false) : SteerVehicle {
        var initialized = 0; var released = 0; var unregistered = 0; var queries = 0
        var push: ((Float) -> Unit)? = null
        override fun initialize() { initialized++ }
        override fun queryCurrent(onAngle: (Float) -> Unit, onError: (Throwable) -> Unit) {
            queries++
            if (failFirstQuery && queries == 1) onError(IllegalStateException("service died")) else onAngle(angle)
        }
        override fun register(onAngle: (Float) -> Unit, onError: (Throwable) -> Unit): () -> Unit { push = onAngle; return { unregistered++ } }
        override fun release() { released++ }
    }

    @Test fun emitsInitialPushAndPolledValues_andReleasesOnCancel() = runTest {
        val v = FakeVehicle()
        val out = mutableListOf<Float>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            steeringFlowOf({ v }, pollMs = 500, nowMs = { testScheduler.currentTime }).collect { out += it }
        }
        advanceTimeBy(1_100)
        assertEquals("연결 직후 질의 1회 + 500ms 주기 질의 2회", listOf(1.5f, 1.5f, 1.5f), out)
        v.push!!(7f)
        assertEquals(7f, out.last(), 0f)
        v.angle = 2f; advanceTimeBy(500)
        assertEquals(2f, out.last(), 0f)
        assertEquals(1, v.initialized)
        job.cancel(); advanceTimeBy(1)
        assertEquals(1, v.unregistered); assertEquals(1, v.released)
    }

    @Test fun connectionFailure_replacesConnection_andContinues() = runTest {
        val vs = mutableListOf<FakeVehicle>()
        val out = mutableListOf<Float>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            steeringFlowOf({ FakeVehicle(angle = 3f, failFirstQuery = vs.isEmpty()).also { vs += it } }, pollMs = 500, nowMs = { testScheduler.currentTime }).collect { out += it }
        }
        advanceTimeBy(10_000)
        assertTrue("연결이 교체돼야 한다: ${vs.size}", vs.size >= 2)
        assertEquals("끊긴 연결은 정리된다", 1, vs[0].released)
        assertTrue(out.isNotEmpty() && out.all { it == 3f })
    }
}

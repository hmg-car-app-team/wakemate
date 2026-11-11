package com.hmgcarapp.core

import com.hmgcarapp.core.data.DataSourceKind
import com.hmgcarapp.core.data.DataSources
import com.hmgcarapp.core.drive.DriveInputs
import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.core.drive.DriveStateCalculator
import com.hmgcarapp.core.drive.Gear
import com.hmgcarapp.core.drive.GearSource
import com.hmgcarapp.core.drive.UxRestrictionSource
import com.hmgcarapp.core.location.GeoPoint
import com.hmgcarapp.core.location.LocationSource
import com.hmgcarapp.core.sim.SimulationHub
import com.hmgcarapp.core.store.LocalJsonStore
import com.hmgcarapp.core.vehicle.CarPropertyReader
import com.hmgcarapp.core.vehicle.CarPropertyVehicleSignals
import com.hmgcarapp.core.vehicle.Units
import com.hmgcarapp.core.voice.ListenResult
import com.hmgcarapp.core.voice.SttPort
import com.hmgcarapp.core.voice.listenOnceOn
import com.hmgcarapp.core.vehicle.VehiclePropertyIds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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

private class FakeReader(var speedMs: Float = 0f, var temp: Float = 25f, var fail: Boolean = false) : CarPropertyReader {
    var refs = 0
    var maxRefs = 0
    override fun acquire() { refs++; maxRefs = maxOf(maxRefs, refs) }
    override fun release() { refs-- }
    override fun getFloat(propertyId: Int, areaId: Int): Float {
        if (fail) throw IllegalArgumentException("property ID is not in carPropertyConfig list")
        return when (propertyId) {
            VehiclePropertyIds.PERF_VEHICLE_SPEED -> speedMs
            VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE -> temp
            else -> throw IllegalArgumentException("unknown")
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CoreTests {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun unitConversion() {
        assertEquals(60f, Units.msToKmh(16.666668f), 0.01f)
        assertEquals(100f, Units.msToKmh(27.777779f), 0.01f)
        assertEquals(10f, Units.kmhToMs(36f), 0.001f)
    }

    @Test fun speedNowAndTemp() = runTest {
        val r = FakeReader(speedMs = 16.666668f, temp = 21.5f)
        val s = CarPropertyVehicleSignals(r)
        assertEquals(60f, s.speedKmhNow().value!!, 0.01f)
        assertEquals(21.5f, s.outsideTempCNow().value!!, 0.001f)
        assertEquals(0, r.refs)
    }

    @Test fun failureReturnsNullWithReason() = runTest {
        val s = CarPropertyVehicleSignals(FakeReader(fail = true))
        val a = s.speedKmhNow()
        assertNull(a.value)
        assertTrue(a.reason!!.contains("IllegalArgumentException"))
        assertFalse(s.outsideTempCNow().isOk)
    }

    @Test fun speedFlowPollsAndCleansUp() = runTest {
        val r = FakeReader(speedMs = 0f)
        val s = CarPropertyVehicleSignals(r)
        val got = mutableListOf<Float?>()
        val job = launch { s.speedKmh(1000).collect { got += it.value } }
        runCurrent()
        assertEquals(1, r.refs)
        r.speedMs = 10f
        advanceTimeBy(1001)
        r.speedMs = 20f
        advanceTimeBy(1000)
        job.cancel(); job.join()
        assertEquals(0, r.refs)
        assertEquals(listOf(0f, 36f, 72f), got.take(3).map { it })
    }

    @Test fun driveStateRules() {
        fun s(g: Gear, v: Float? = null, ux: Boolean? = null, o: DriveState? = null) =
            DriveStateCalculator.compute(DriveInputs(g, v, ux), o).state
        assertEquals(DriveState.PARKED, s(Gear.PARK, 0f))
        assertEquals(DriveState.DRIVING, s(Gear.DRIVE, 0f))
        assertEquals(DriveState.DRIVING, s(Gear.PARK, 30f))
        assertEquals(DriveState.DRIVING, s(Gear.UNKNOWN, null, true))
        assertEquals(DriveState.DRIVING, s(Gear.UNKNOWN, null, null)) // K8 fail-safe
        // 실신호 DRIVING 이면 PARKED override 무시
        assertEquals(DriveState.DRIVING, s(Gear.DRIVE, 100f, null, DriveState.PARKED))
        assertEquals(DriveState.DRIVING, s(Gear.DRIVE, 0f, null, DriveState.PARKED))
        // 실신호 PARKED + override DRIVING -> DRIVING
        assertEquals(DriveState.DRIVING, s(Gear.PARK, 0f, false, DriveState.DRIVING))
        // 실신호 PARKED + override PARKED -> PARKED
        assertEquals(DriveState.PARKED, s(Gear.PARK, 0f, false, DriveState.PARKED))
        // UX 제한 on + 기어 P -> DRIVING (기어 무관)
        assertEquals(DriveState.DRIVING, s(Gear.PARK, 0f, true))
        assertEquals(DriveState.DRIVING, s(Gear.PARK, 0f, true, DriveState.PARKED))
        // 실신호 불명 + override
        assertEquals(DriveState.DRIVING, s(Gear.UNKNOWN, null, null, DriveState.DRIVING))
        assertEquals(DriveState.DRIVING, s(Gear.UNKNOWN, null, null, DriveState.PARKED))
    }

    @Test fun simValidation() {
        assertEquals(null, SimulationHub.validLatLon(91.0, 0.0))
        assertEquals(null, SimulationHub.validLatLon(0.0, 181.0))
        assertEquals(null, SimulationHub.validLatLon(Double.NaN, 0.0))
        assertEquals(37.5, SimulationHub.validLatLon(37.5, 127.0)!!.latitude, 0.0)
        assertEquals(null, SimulationHub.validSpeed(-1f))
        assertEquals(null, SimulationHub.validSpeed(Float.POSITIVE_INFINITY))
        assertEquals(null, SimulationHub.validSpeed(1000f))
        assertEquals(80f, SimulationHub.validSpeed(80f)!!, 0f)
        assertEquals(null, SimulationHub.parseGear("X"))
        assertEquals(null, SimulationHub.parseDrive("x"))
        assertEquals(DriveState.DRIVING, SimulationHub.parseDrive("driving"))
    }

    @Test fun listenOnceCleansUpOnAllPaths() = runTest {
        class Port(val onRequest: (ai.pleos.playground.stt.listener.ResultListener) -> Unit = {}) : SttPort {
            val ls = mutableListOf<ai.pleos.playground.stt.listener.ResultListener>()
            var removed = 0; var stops = 0
            override fun addListener(l: ai.pleos.playground.stt.listener.ResultListener) { ls += l }
            override fun removeListener(l: ai.pleos.playground.stt.listener.ResultListener) { removed++ }
            override fun setLocale(locale: java.util.Locale) = Unit
            override fun request() { onRequest(ls.last()) }
            override fun stop() { stops++ }
        }
        // 정상 종료
        val ok = Port { it.onUpdated("안녕", true) }
        val r = listenOnceOn(ok, java.util.Locale.KOREA, 1000)
        assertEquals(ListenResult.Text("안녕"), r)
        assertEquals(1, ok.removed); assertEquals(1, ok.stops)
        // 타임아웃
        val to = Port()
        assertEquals(ListenResult.Timeout, listenOnceOn(to, java.util.Locale.KOREA, 100))
        assertEquals(1, to.removed); assertEquals(1, to.stops)
        // 취소
        val cn = Port()
        val job = launch { listenOnceOn(cn, java.util.Locale.KOREA, 100_000) }
        testScheduler.runCurrent()
        job.cancel(); job.join()
        assertEquals(1, cn.removed); assertEquals(1, cn.stops)
    }

    @Test fun simulationSwitchesSignals() = runTest {
        val hub = SimulationHub()
        val sig = CarSignals(
            vehicle = CarPropertyVehicleSignals(FakeReader(speedMs = 0f)),
            gearSource = object : GearSource { override fun gear() = flowOf(Gear.PARK) },
            uxSource = object : UxRestrictionSource { override fun requiresDistractionOptimization(intervalMs: Long) = flowOf<Boolean?>(false) },
            locationSource = object : LocationSource { override fun locations() = flowOf(GeoPoint(1.0, 2.0)) },
            hub = hub,
        )
        assertEquals(0f, sig.speedKmh.first().value!!, 0.01f)
        assertEquals(DriveState.PARKED, sig.driveState.first().state)
        hub.setEnabled(true); hub.setSpeedKmh(80f); hub.setGear(Gear.DRIVE); hub.setLocation(GeoPoint(37.5, 127.0))
        assertEquals(80f, sig.speedKmh.first().value!!, 0.01f)
        assertEquals(Gear.DRIVE, sig.gear.first())
        assertEquals(DriveState.DRIVING, sig.driveState.first().state)
        assertEquals(37.5, sig.location.first().latitude, 0.0001)
        hub.setEnabled(false)
        assertEquals(0f, sig.speedKmh.first().value!!, 0.01f)
        assertEquals(1.0, sig.location.first().latitude, 0.0001)
    }

    @Test fun dataSourceSelectsFakeWithoutKey() {
        val a = DataSources.select("t", "", "fake") { "remote:$it" }
        assertEquals(DataSourceKind.FAKE, a.info.kind); assertEquals("fake", a.repo)
        val b = DataSources.select("t", "  ", "fake") { "remote:$it" }
        assertEquals("fake", b.repo)
        val c = DataSources.select("t", "k1", "fake") { "remote:$it" }
        assertEquals(DataSourceKind.REMOTE, c.info.kind); assertEquals("remote:k1", c.repo)
        val d = DataSources.select("t", "k1", "fake") { error("boom") }
        assertEquals("fake", d.repo)
    }

    @Test fun jsonStoreRoundTrip() {
        val st = LocalJsonStore(tmp.newFolder("s"))
        assertNull(st.loadObject("state"))
        st.save("state", JSONObject().put("n", 3).put("name", "한글"))
        val o = st.loadObject("state")!!
        assertEquals(3, o.getInt("n")); assertEquals("한글", o.getString("name"))
        st.saveText("state", "{broken")
        assertNull(st.loadObject("state"))
        assertTrue(st.delete("state")); assertFalse(st.exists("state"))
        try { st.saveText("../x", "{}"); throw AssertionError("should fail") } catch (_: IllegalArgumentException) {}
    }
}

package com.hmgcarapp.core

import android.content.Context
import com.hmgcarapp.core.drive.DriveInputs
import com.hmgcarapp.core.drive.DriveSnapshot
import com.hmgcarapp.core.drive.DriveStateCalculator
import com.hmgcarapp.core.drive.Gear
import com.hmgcarapp.core.drive.GearSource
import com.hmgcarapp.core.drive.PleosGearSource
import com.hmgcarapp.core.drive.ReflectiveUxRestrictionSource
import com.hmgcarapp.core.drive.UxRestrictionSource
import com.hmgcarapp.core.location.GeoPoint
import com.hmgcarapp.core.location.LocationSource
import com.hmgcarapp.core.location.PleosLocationSource
import com.hmgcarapp.core.sim.SimulationHub
import com.hmgcarapp.core.vehicle.CarPropertyVehicleSignals
import com.hmgcarapp.core.vehicle.ReflectiveCarPropertyReader
import com.hmgcarapp.core.vehicle.SignalReading
import com.hmgcarapp.core.vehicle.VehicleSignals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * 앱이 구독하는 단일 창구. 실신호([VehicleSignals] 등)와 시뮬레이션([SimulationHub])을 같은 Flow 로 합쳐준다.
 *  - hub.enabled=false : 실신호 그대로
 *  - hub.enabled=true  : hub 에 값이 있는 항목만 대체(null 이면 실신호)
 */
class CarSignals(
    private val vehicle: VehicleSignals,
    private val gearSource: GearSource,
    private val uxSource: UxRestrictionSource,
    private val locationSource: LocationSource,
    val hub: SimulationHub = SimulationHub.shared,
    private val pollMs: Long = 1000L,
) {
    /** 차속 km/h (SignalReading: 실패 시 value=null + reason). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val speedKmh: Flow<SignalReading<Float>> =
        hub.state.map { it.enabled && it.speedKmh != null }.distinctUntilChanged().flatMapLatest { simulated ->
            if (simulated) hub.state.map { SignalReading.ok(it.speedKmh!!) } else vehicle.speedKmh(pollMs).flowOn(Dispatchers.IO) // 리플렉션 바인더 호출을 Main 밖에서
        }

    val gear: Flow<Gear> = combine(gearSource.gear().onStart { emit(Gear.UNKNOWN) }, hub.state) { real, sim ->
        if (sim.enabled && sim.gear != null) sim.gear else real
    }.distinctUntilChanged()

    private val ux: Flow<Boolean?> = uxSource.requiresDistractionOptimization(pollMs).flowOn(Dispatchers.IO).onStart { emit(null) }.distinctUntilChanged()

    val driveState: Flow<DriveSnapshot> =
        combine(speedKmh, gear, ux, hub.state) { speed, g, u, sim ->
            val inputs = DriveInputs(g, speed.value, u)
            DriveStateCalculator.compute(inputs, if (sim.enabled) sim.driveOverride else null)
        }.distinctUntilChanged()

    /** 위치. 시뮬레이션이 켜져 있고 위치가 주입돼 있으면 그 값. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val location: Flow<GeoPoint> =
        hub.state.map { it.enabled && it.location != null }.distinctUntilChanged().flatMapLatest { simulated ->
            if (simulated) hub.state.map { it.location!! }.distinctUntilChanged() else locationSource.locations()
        }

    companion object {
        fun create(context: Context, hub: SimulationHub = SimulationHub.shared): CarSignals {
            val c = context.applicationContext
            return CarSignals(
                vehicle = CarPropertyVehicleSignals(ReflectiveCarPropertyReader(c)),
                gearSource = PleosGearSource(c),
                uxSource = ReflectiveUxRestrictionSource(c),
                locationSource = PleosLocationSource(c),
                hub = hub,
            )
        }
    }
}

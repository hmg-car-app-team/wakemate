package com.hmgcarapp.pleos.designsystem.restriction

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log

/**
 * android.car 클래스를 직접 참조하는 유일한 파일입니다.
 * 일반 기기에서는 이 클래스를 만들지 않으므로 클래스 로딩 오류가 나지 않습니다.
 */
internal class CarUxRestrictionsSource(
    private val context: Context,
    private val onChange: (PleosUxRestrictions) -> Unit,
) {
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    fun start() {
        try {
            val connected = Car.createCar(context)
            car = connected
            val uxManager = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager ?: return
            manager = uxManager
            uxManager.currentCarUxRestrictions?.let { onChange(it.toPleos()) }
            uxManager.registerListener { restrictions -> onChange(restrictions.toPleos()) }
        } catch (error: Throwable) {
            Log.w("PleosUxRestrictions", "CarUxRestrictions를 사용할 수 없습니다", error)
        }
    }

    fun stop() {
        runCatching { manager?.unregisterListener() }
        runCatching { car?.disconnect() }
        manager = null
        car = null
    }

    private fun CarUxRestrictions.toPleos() = PleosUxRestrictions(isRequiresDistractionOptimization, activeRestrictions)
}

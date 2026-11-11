package com.hmgcarapp.core.drive

import com.hmgcarapp.core.vehicle.SignalReading

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

enum class DriveState { PARKED, DRIVING }

/** 입력 신호 스냅샷. null = 알 수 없음. */
data class DriveInputs(
    val gear: Gear = Gear.UNKNOWN,
    val speedKmh: Float? = null,
    /** CarUxRestrictions.requiresDistractionOptimization (null = 알 수 없음) */
    val uxRequiresDistractionOptimization: Boolean? = null,
)

data class DriveSnapshot(
    val state: DriveState,
    val inputs: DriveInputs,
    /** 판정 근거(디버그/화면 표시용) */
    val reason: String,
)

/**
 * PARKED/DRIVING 판정 (순수 함수, 단위 테스트 대상).
 * 실신호: 속도 >= 임계 OR 플랫폼 UX 제한(requiresDistractionOptimization) OR 기어(P 아님) -> DRIVING (기어와 무관하게 UX 제한이 우선).
 * 시연 override 는 **더 제한적인 방향(DRIVING)만** 허용한다. 실신호가 DRIVING 이면 PARKED override 는 무시한다.
 */
object DriveStateCalculator {
    /** 신호 도착 전 초기 스냅샷(제한 우선 DRIVING). */
    val initial: DriveSnapshot = DriveSnapshot(DriveState.DRIVING, DriveInputs(), "초기화 중(fail-safe)")

    const val MOVING_THRESHOLD_KMH = 3f

    fun compute(inputs: DriveInputs, override: DriveState? = null): DriveSnapshot {
        val real = real(inputs)
        if (override == DriveState.DRIVING && real.state != DriveState.DRIVING) return DriveSnapshot(DriveState.DRIVING, inputs, "시연 override(DRIVING)")
        return real
    }

    private fun real(inputs: DriveInputs): DriveSnapshot {
        // NaN/Infinity 는 미확인(null), 음수는 절댓값으로 비교
        val speed = inputs.speedKmh?.takeIf { it.isFinite() }?.let { kotlin.math.abs(it) }
        if (speed != null && speed >= MOVING_THRESHOLD_KMH) return DriveSnapshot(DriveState.DRIVING, inputs, "차속 ${"%.0f".format(speed)}km/h")
        if (inputs.uxRequiresDistractionOptimization == true) return DriveSnapshot(DriveState.DRIVING, inputs, "UX 제한 활성")
        return when (inputs.gear) {
            Gear.PARK -> DriveSnapshot(DriveState.PARKED, inputs, "기어 P")
            Gear.DRIVE, Gear.REVERSE, Gear.NEUTRAL -> DriveSnapshot(DriveState.DRIVING, inputs, "기어 ${inputs.gear}")
            Gear.UNKNOWN ->
                // UX 제한 상태도 모르면(null) 정차를 확인할 근거가 없다 -> DRIVING
                if (inputs.uxRequiresDistractionOptimization == null)
                    DriveSnapshot(DriveState.DRIVING, inputs, "기어·UX 미확인 -> 주행으로 간주(fail-safe)")
                else
                // fail-safe: 기어 미확인이면 '속도가 확인되어 정지(<임계)'일 때만 PARKED.
                // 속도가 미확인이면 UX 제한이 false 여도(제한 없음 != 정차 확인) 제한 우선(DRIVING)
                if (speed == null)
                    DriveSnapshot(DriveState.DRIVING, inputs, "기어·속도 미확인 -> 주행으로 간주(fail-safe)")
                else DriveSnapshot(DriveState.PARKED, inputs, "기어 미확인, 속도 정지 확인")
        }
    }
}

fun SignalReading<Float>.speedOrNull(): Float? = value

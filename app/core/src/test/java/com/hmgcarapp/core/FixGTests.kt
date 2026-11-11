package com.hmgcarapp.core

import com.hmgcarapp.core.drive.DriveInputs
import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.core.drive.DriveStateCalculator
import com.hmgcarapp.core.drive.Gear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fix-G: 기어·속도 미확인 시 PARKED 금지. (재연결 간격 제한은 ManagedConnectionTests.minAttemptGap 로 이전) */
class FixGTests {
    @Test fun unknownGear_noSpeed_uxFalse_isNotParked() {
        fun s(g: Gear, v: Float?, ux: Boolean?) = DriveStateCalculator.compute(DriveInputs(g, v, ux)).state
        assertEquals(DriveState.DRIVING, s(Gear.UNKNOWN, null, false)) // r5R2-7
        assertEquals(DriveState.DRIVING, s(Gear.UNKNOWN, null, null))
        assertEquals(DriveState.DRIVING, s(Gear.UNKNOWN, Float.NaN, false))
        assertEquals(DriveState.PARKED, s(Gear.UNKNOWN, 0f, false)) // 속도 정지 확인 시에는 PARKED 유지
        assertEquals(DriveState.PARKED, s(Gear.PARK, null, false))
    }
}

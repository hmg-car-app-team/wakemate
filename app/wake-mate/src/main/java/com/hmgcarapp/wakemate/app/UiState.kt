package com.hmgcarapp.wakemate.app

import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.wakemate.domain.LatLon
import com.hmgcarapp.wakemate.domain.NearShelter
import com.hmgcarapp.wakemate.domain.RiskResult
import com.hmgcarapp.wakemate.domain.ShelterDataset
import com.hmgcarapp.wakemate.domain.SimPattern
import com.hmgcarapp.wakemate.domain.WakeState
import com.hmgcarapp.wakemate.vehicle.HvacSnapshot

enum class Page(val label: String) { STATUS("현황"), SHELTER("쉼터"), REPORT("휴식 리포트"), SETTINGS("설정") }

/** 시연(시뮬레이션) 주입 상태. null 이 아니면 화면에 '시연' 표시. */
data class SimInfo(val pattern: SimPattern? = null, val minuteOfDay: Int? = null, val extraDriveMin: Int = 0, val extraRecircMin: Int = 0, val hubOn: Boolean = false,
    /** stage/heard/rest/steer 직접 주입 여부. */ val injected: Boolean = false) {
    val active: Boolean get() = pattern != null || minuteOfDay != null || extraDriveMin != 0 || extraRecircMin != 0 || hubOn || injected
}

/** 화면에 보이는 모든 상태(컨트롤러가 단일 소유). 깨우기 소속 상태는 [WakeState]. */
data class UiState(
    val p: Persisted = Persisted(),
    val loading: Boolean = true,
    val drive: DriveState = DriveState.DRIVING,
    val page: Page = Page.STATUS,
    val wake: WakeState = WakeState(),
    val risk: RiskResult? = null,
    val hvac: HvacSnapshot = HvacSnapshot(),
    val origin: LatLon? = null,
    val originAtMs: Long? = null,
    val nearby: List<NearShelter> = emptyList(),
    val dataset: ShelterDataset? = null,
    val drivingMin: Int = 0,
    val recircMin: Int? = null,
    val steerReady: Boolean = false,
    val sim: SimInfo = SimInfo(),
    val debuggable: Boolean = false,
    val saveHeld: Boolean = false,
    val corruptKept: Boolean = false,
)

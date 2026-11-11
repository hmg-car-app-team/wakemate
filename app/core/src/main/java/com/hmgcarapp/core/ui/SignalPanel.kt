package com.hmgcarapp.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hmgcarapp.core.CarSignals
import com.hmgcarapp.core.drive.DriveSnapshot
import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.core.drive.DriveStateCalculator
import com.hmgcarapp.core.drive.Gear
import com.hmgcarapp.core.location.GeoPoint
import com.hmgcarapp.core.vehicle.SignalReading
import com.hmgcarapp.pleos.designsystem.component.PleosStatus
import com.hmgcarapp.pleos.designsystem.component.PleosStatusBadge
import com.hmgcarapp.pleos.designsystem.restriction.LocalPleosUxRestrictions
import com.hmgcarapp.pleos.designsystem.restriction.PleosUxRestrictions
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme
import com.hmgcarapp.pleos.designsystem.theme.PleosWindowMode

/** Activity 수명 동안 쓰는 [CarSignals]. */
@Composable
fun rememberCarSignals(): CarSignals {
    val ctx = LocalContext.current
    val signals = remember { CarSignals.create(ctx) }
    DisposableEffect(signals) {
        signals.hub.registerAdbReceiver(ctx) // debuggable 앱에서만 실제 등록됨
        onDispose { signals.hub.unregisterAdbReceiver() }
    }
    return signals
}

/**
 * 디자인시스템의 UX 제한 override(PleosUxRestrictionsProvider(override=...))를 DriveState 에도 반영하기 위한 변환:
 * 디자인시스템 쪽에서 시연으로 강제한 requiresDistractionOptimization 값이 있으면 그것을 우선 (실제 제한 상태는 provider 가 이미 반영).
 */
@Composable
fun rememberDriveSnapshot(signals: CarSignals): DriveSnapshot {
    val ux = LocalPleosUxRestrictions.current
    val snap by signals.driveState.collectAsState(initial = DriveStateCalculator.initial)
    // 화면 UX 제한(디자인시스템 provider; 시연 override 포함)이 켜져 있으면 DRIVING 으로 본다
    return if (ux.requiresDistractionOptimization && snap.state == DriveState.PARKED)
        snap.copy(state = DriveState.DRIVING, reason = "UX 제한(provider)")
    else snap
}

/** 스켈레톤 화면용: 실시간 차속 + DriveState + 기어/위치/UX 상태. core 배선을 눈으로 확인한다. */
@Composable
fun SignalPanel(signals: CarSignals, mode: PleosWindowMode, modifier: Modifier = Modifier) {
    val reading by signals.speedKmh.collectAsState(initial = SignalReading.unavailable<Float>("대기 중"))
    val snap = rememberDriveSnapshot(signals)
    val gear by signals.gear.collectAsState(initial = Gear.UNKNOWN)
    val loc by signals.location.collectAsState(initial = null as GeoPoint?)
    val sim by signals.hub.state.collectAsState()
    val ux = LocalPleosUxRestrictions.current
    val colors = PleosTheme.colors

    val speedText = reading.value?.let { "%.0f".format(it) } ?: "--"
    val parked = snap.state == DriveState.PARKED

    val big: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("차속", style = PleosTheme.typography.labelMedium, color = colors.textDarkSecondary)
            Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(speedText, modifier = Modifier.testTag("speed"), style = PleosTheme.typography.headlineLarge, color = colors.textDarkPrimary)
                Text("km/h", style = PleosTheme.typography.titleSmall, color = colors.textDarkSecondary)
            }
            reading.reason?.let { Text(it, style = PleosTheme.typography.bodySmall, color = colors.textDarkTertiary) }
            PleosStatusBadge(
                label = "DriveState: ${snap.state}",
                status = if (parked) PleosStatus.Positive else PleosStatus.Active,
                modifier = Modifier.testTag("drivestate"),
            )
        }
    }
    val detail: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("판정 근거: ${snap.reason}", style = PleosTheme.typography.bodyMedium, color = colors.textDarkPrimary)
            Text("기어: $gear   UX 제한: ${if (ux.requiresDistractionOptimization) "있음" else "없음"}", style = PleosTheme.typography.bodyMedium, color = colors.textDarkPrimary)
            Text("위치: " + (loc?.let { "%.5f, %.5f".format(it.latitude, it.longitude) } ?: "수신 대기"), style = PleosTheme.typography.bodyMedium, color = colors.textDarkPrimary)
            Text("시뮬레이션: " + if (sim.enabled) "ON" else "OFF (실신호)", style = PleosTheme.typography.bodyMedium, color = colors.textDarkSecondary)
        }
    }
    if (mode == PleosWindowMode.Landscape) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(48.dp)) { big(); detail() }
    } else {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) { big(); detail() }
    }
}

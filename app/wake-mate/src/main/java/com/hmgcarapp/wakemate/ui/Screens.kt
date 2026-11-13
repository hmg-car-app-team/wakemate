package com.hmgcarapp.wakemate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.core.ui.rememberDriveSnapshot
import com.hmgcarapp.pleos.designsystem.component.PleosButton
import com.hmgcarapp.pleos.designsystem.component.PleosButtonHierarchy
import com.hmgcarapp.pleos.designsystem.component.PleosListItem
import com.hmgcarapp.pleos.designsystem.component.PleosPanel
import com.hmgcarapp.pleos.designsystem.component.PleosPopup
import com.hmgcarapp.pleos.designsystem.component.PleosScaffold
import com.hmgcarapp.pleos.designsystem.component.PleosSegmentedTabs
import com.hmgcarapp.pleos.designsystem.component.PleosStatus
import com.hmgcarapp.pleos.designsystem.component.PleosStatusBadge
import com.hmgcarapp.pleos.designsystem.component.PleosSwitch
import com.hmgcarapp.pleos.designsystem.component.rememberPleosToastState
import com.hmgcarapp.pleos.designsystem.theme.PleosSpacing
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme
import com.hmgcarapp.pleos.designsystem.theme.PleosWindowMode
import com.hmgcarapp.wakemate.app.AppController
import com.hmgcarapp.wakemate.app.BackgroundWatch
import com.hmgcarapp.wakemate.app.WakeRuntime
import com.hmgcarapp.wakemate.app.Page
import com.hmgcarapp.wakemate.app.UiState
import com.hmgcarapp.wakemate.domain.Alert
import com.hmgcarapp.wakemate.domain.NaviStatus
import com.hmgcarapp.wakemate.domain.NaviText
import com.hmgcarapp.wakemate.domain.RestAdvice
import com.hmgcarapp.wakemate.domain.RiskResult
import com.hmgcarapp.wakemate.domain.Stage
import com.hmgcarapp.wakemate.domain.Status
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun Txt(t: String, style: TextStyle = PleosTheme.typography.bodyMedium, secondary: Boolean = false, modifier: Modifier = Modifier) =
    Text(t, style = style, color = if (secondary) PleosTheme.colors.textDarkSecondary else PleosTheme.colors.textDarkPrimary, modifier = modifier)

private fun stageStatus(s: Stage) = when (s) {
    Stage.NONE -> PleosStatus.Positive
    Stage.S1 -> PleosStatus.Active
    Stage.S2, Stage.S3 -> PleosStatus.Negative
}

@Composable
fun WakeMateApp(c: AppController) {
    val s by c.state.collectAsState()
    val snap = rememberDriveSnapshot(c.signals)
    LaunchedEffect(snap.state) { c.onUiDriveState(snap.state) }
    // 첫 프레임에 정차 UI 가 잘못 보이지 않게 화면 쪽 스냅샷(초기값 DRIVING)과 컨트롤러 판정 중 하나라도 주행이면 주행 화면
    val driving = snap.state == DriveState.DRIVING || s.drive == DriveState.DRIVING
    val toast = rememberPleosToastState()
    // 주행 중에는 토스트를 보류하고 정차 시 표시한다
    LaunchedEffect(s.wake.message, driving) { if (!driving) s.wake.message?.let { toast.show(it); c.clearMessage() } }
    LaunchedEffect(driving) { if (driving) toast.dismiss() }
    val paneWidth = PleosTheme.window.appLandscapeWidth * 0.4f

    if (!driving) s.wake.replaceConfirm?.let {
        PleosPopup(
            title = "현재 경로를 쉼터로 바꿀까요?",
            description = "내비의 기존 경로가 사라지고 이 쉼터로 새로 안내돼요.",
            confirmLabel = "바꾸기", onConfirm = { c.confirmReplace() },
            onDismissRequest = { c.cancelReplace() }, cancelLabel = "취소",
        )
    }
    PleosScaffold(
        title = "깨워드림",
        subtitle = (s.risk?.stage?.let { "졸음 ${it.label}" } ?: "준비 중") + " · " + (if (driving) "주행 중" else "정차 중"),
        toastState = toast,
        supportingPane = if (driving || s.loading) null else { m -> Column(m.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(PleosSpacing.m)) { Summary(s) } },
        supportingPaneWidth = paneWidth,
    ) { mode ->
        when {
            driving -> DrivingView(c, s)
            s.loading -> PleosPanel(Modifier.fillMaxWidth()) { Txt("불러오는 중…", secondary = true, modifier = Modifier.testTag("loading")) }
            else -> Parked(c, s, mode)
        }
    }
}

// ---------------- 주행 중: 읽기 전용 한 화면(조작 요소 없음) ----------------
@Composable
private fun DrivingView(c: AppController, s: UiState) {
    Column(Modifier.fillMaxSize().testTag("driving_view"), verticalArrangement = Arrangement.spacedBy(PleosSpacing.m)) {
        PleosPanel(Modifier.fillMaxWidth()) {
            val r = s.risk
            Txt(r?.score?.toString() ?: "–", PleosTheme.typography.headlineLarge, modifier = Modifier.testTag("risk_score"))
            Txt(r?.stage?.label ?: "계산 중", PleosTheme.typography.titleMedium, modifier = Modifier.testTag("risk_stage"))
            val a = s.wake.alert
            if (a != null) {
                a.shelterLine?.let { Txt(it, PleosTheme.typography.bodyMedium, modifier = Modifier.testTag("banner_shelter")) }
                if (!Status.isProgress(a.status)) Txt(a.status, PleosTheme.typography.bodyMedium, modifier = Modifier.testTag("banner_status"))
                a.detail?.let { Txt(it, PleosTheme.typography.bodyMedium, secondary = true, modifier = Modifier.testTag("banner_detail")) }
            } else {
                Txt("쉬지 않은 주행 ${s.drivingMin}분", PleosTheme.typography.bodyLarge, secondary = true, modifier = Modifier.testTag("driving_idle"))
            }
        }
    }
}

// ---------------- 정차 ----------------
@Composable
private fun Summary(s: UiState) {
    PleosPanel(Modifier.fillMaxWidth(), title = "졸음 위험지수") {
        val r = s.risk
        Txt(r?.score?.toString() ?: "–", PleosTheme.typography.headlineMedium, modifier = Modifier.testTag("risk_score"))
        if (r != null) PleosStatusBadge(r.stage.label, stageStatus(r.stage))
    }
}

@Composable
private fun Parked(c: AppController, s: UiState, mode: PleosWindowMode) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(PleosSpacing.m)) {
        if (mode == PleosWindowMode.Portrait) Summary(s)
        s.wake.alert?.let { AlertCard(c, it) }
        PleosSegmentedTabs(
            items = Page.entries.map { it.label }, selectedIndex = s.page.ordinal,
            onSelect = { c.setPage(Page.entries[it]) }, modifier = Modifier.fillMaxWidth().testTag("page_tabs"),
        )
        when (s.page) {
            Page.STATUS -> StatusPage(c, s)
            Page.SHELTER -> ShelterPage(c, s)
            Page.REPORT -> ReportPage(c, s)
            Page.SETTINGS -> SettingsPage(c, s)
        }
    }
}

@Composable
private fun AlertCard(c: AppController, a: Alert) {
    PleosPanel(Modifier.fillMaxWidth(), title = if (a.practice) "퀴즈 연습" else "깨우기 ${a.stage.n}단계") {
        a.shelterLine?.let { Txt(it, modifier = Modifier.testTag("banner_shelter")) }
        if (!Status.isProgress(a.status)) Txt(a.status, PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("banner_status"))
        a.detail?.let { Txt(it, PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("banner_detail")) }
        if (a.ventOffer && a.status == Status.LISTENING && !a.finished) Row(horizontalArrangement = Arrangement.spacedBy(PleosSpacing.m)) {
            PleosButton("외기로 바꾸기", onClick = { c.answerVent(true) }, modifier = Modifier.testTag("btn_vent_yes"))
            PleosButton("괜찮아요", onClick = { c.answerVent(false) }, hierarchy = PleosButtonHierarchy.Secondary, modifier = Modifier.testTag("btn_vent_no"))
        }
        if (a.finished) PleosButton("닫기", onClick = { c.dismissAlert() }, hierarchy = PleosButtonHierarchy.Secondary)
    }
}

@Composable
private fun StatusPage(c: AppController, s: UiState) {
    val r: RiskResult? = s.risk
    PleosPanel(Modifier.fillMaxWidth(), title = "위험지수 구성") {
        if (r == null) Txt("계산 중…", secondary = true)
        else r.factors.forEach { f ->
            PleosListItem(
                title = f.label, description = f.note,
                trailing = { Txt("${f.points} / ${f.max}", modifier = Modifier.testTag("factor_${f.key}")) },
            )
        }
    }
    PleosPanel(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(PleosSpacing.m)) {
            PleosButton("졸리지 않아요", onClick = { c.notSleepy() }, hierarchy = PleosButtonHierarchy.Secondary, modifier = Modifier.testTag("btn_not_sleepy"))
            PleosButton("퀴즈 연습", onClick = { c.startQuizPractice() }, hierarchy = PleosButtonHierarchy.Secondary, modifier = Modifier.testTag("btn_quiz"))
        }
    }
}

@Composable
private fun NaviLine(c: AppController) {
    val st by c.navi.status.collectAsState()
    when (val v = st) {
        NaviStatus.Idle -> Unit
        is NaviStatus.Pending -> PleosStatusBadge("${v.what}…", PleosStatus.Active)
        is NaviStatus.Success -> PleosStatusBadge(v.what, PleosStatus.Positive)
        is NaviStatus.Failed -> {
            PleosStatusBadge(NaviText.shortWord(v) ?: "요청 실패", if (v.reason.isBlank()) PleosStatus.Neutral else PleosStatus.Negative)
        }
    }
}

@Composable
private fun ShelterPage(c: AppController, s: UiState) {
    PleosPanel(Modifier.fillMaxWidth(), title = "가까운 졸음쉼터") {
        NaviLine(c)
        s.nearby.forEach { n ->
            PleosListItem(
                title = n.shelter.name,
                description = "${String.format(Locale.KOREA, "%.1f", n.distanceKm)}km · ${n.shelter.kindLabel} · ${n.shelter.roadName} ${n.shelter.direction}",
                trailing = { PleosButton("안내", onClick = { c.guide(n.shelter.id) }, modifier = Modifier.testTag("guide_${n.shelter.id}")) },
            )
        }
    }
}

@Composable
private fun ReportPage(c: AppController, s: UiState) {
    val fmt = remember { SimpleDateFormat("M/d HH:mm", Locale.KOREA) }
    PleosPanel(Modifier.fillMaxWidth(), title = "다음 휴식") {
        Txt(RestAdvice.next(s.drivingMin, s.risk?.stage ?: Stage.NONE), modifier = Modifier.testTag("rest_advice"))
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "휴식 기록") {
        s.p.rests.asReversed().take(5).forEach { r ->
            PleosListItem(
                title = fmt.format(Date(r.atWallMs)),
                description = "연속 ${r.drivingMin}분 주행 후 ${r.restMin}분 휴식",
            )
        }
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "각성 퀴즈") {
        val q = s.p.quiz
        Txt("${q.total}회 중 ${q.passed}회 통과" + (q.lastResponseMs?.let { " · 마지막 응답 ${String.format(Locale.KOREA, "%.1f", it / 1000.0)}초" } ?: ""), secondary = true)
    }
}

@Composable
private fun SettingsPage(c: AppController, s: UiState) {
    val st = s.p.settings
    PleosPanel(Modifier.fillMaxWidth(), title = "깨우기") {
        PleosSwitch(checked = st.voiceReply, onCheckedChange = { c.setVoiceReply(it) }, label = "음성 응답 사용", modifier = Modifier.testTag("set_voice_reply"))
        PleosSwitch(checked = st.hvacControl, onCheckedChange = { c.setHvacControl(it) }, label = "공조 자동 조절(2단계)", modifier = Modifier.testTag("set_hvac"))
        PleosSwitch(checked = st.beep, onCheckedChange = { c.setBeep(it) }, label = "경고음", modifier = Modifier.testTag("set_beep"))
        PleosButton("개인 보정 초기화", onClick = { c.resetCalibration() }, hierarchy = PleosButtonHierarchy.Secondary, enabled = s.p.calibration.offset > 0, modifier = Modifier.testTag("btn_reset_calibration"))
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "백그라운드 감시") {
        var askBg by remember { mutableStateOf(false) }
        var notifHint by remember { mutableStateOf(false) }
        val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
        // 안내가 떠 있는 동안 권한을 다시 확인해, 시스템 설정에서 허용하고 돌아오면 안내를 지운다
        LaunchedEffect(notifHint) {
            while (notifHint) {
                kotlinx.coroutines.delay(BackgroundWatch.HINT_RECHECK_MS)
                if (WakeRuntime.notificationsGranted(ctx)) notifHint = false
            }
        }
        PleosSwitch(
            checked = st.backgroundWatch,
            onCheckedChange = { on -> if (!on) { notifHint = false; c.disableBackgroundWatch() } else if (WakeRuntime.notificationsGranted(ctx)) { notifHint = false; askBg = true } else notifHint = true },
            label = BackgroundWatch.SWITCH_LABEL, modifier = Modifier.testTag("set_background_watch"),
        )
        if (notifHint) Txt(BackgroundWatch.NOTIF_DENIED_HINT, PleosTheme.typography.labelMedium, modifier = Modifier.testTag("background_notif_hint"))
        if (askBg) PleosPopup(
            title = BackgroundWatch.POPUP_TITLE, description = BackgroundWatch.NOTICE,
            confirmLabel = BackgroundWatch.CONFIRM_LABEL, onConfirm = { askBg = false; c.enableBackgroundWatch() },
            onDismissRequest = { askBg = false }, cancelLabel = "취소",
        )
    }
    if (c.debuggable) DebugPanel(s)
}

@Composable
private fun DebugPanel(s: UiState) {
    PleosPanel(Modifier.fillMaxWidth(), title = "시연 상태 (debug)") {
        val sim = s.sim
        Txt("패턴 ${sim.pattern ?: "없음"} · 시각 ${sim.minuteOfDay?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "실시간"} · 주행 +${sim.extraDriveMin}분 · 내기 ${sim.extraRecircMin}분 · 허브 ${if (sim.hubOn) "켬" else "끔"}", PleosTheme.typography.labelMedium, secondary = true)
        Txt("adb: am broadcast -a com.hmgcarapp.wakemate.SIM --es pattern drowsy --ei hour 3 --ei drivemin 90", PleosTheme.typography.labelSmall, secondary = true)
    }
}

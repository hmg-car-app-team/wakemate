package com.hmgcarapp.wakemate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.hmgcarapp.core.drive.Gear
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
import com.hmgcarapp.wakemate.app.Page
import com.hmgcarapp.wakemate.app.UiState
import com.hmgcarapp.wakemate.domain.Alert
import com.hmgcarapp.wakemate.domain.NaviStatus
import com.hmgcarapp.wakemate.domain.NaviText
import com.hmgcarapp.wakemate.domain.RestAdvice
import com.hmgcarapp.wakemate.domain.RiskResult
import com.hmgcarapp.wakemate.domain.Stage
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
    val notice = if (snap.inputs.gear == Gear.UNKNOWN && snap.inputs.uxRequiresDistractionOptimization == null) "차량 상태를 확인하지 못하면 주행 중으로 간주해요" else null
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
        actions = { if (s.sim.active) PleosStatusBadge("시연", PleosStatus.Autonomous, Modifier.testTag("sim_badge")) },
        supportingPane = if (driving || s.loading) null else { m -> Column(m.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(PleosSpacing.m)) { Summary(s) } },
        supportingPaneWidth = paneWidth,
    ) { mode ->
        when {
            driving -> DrivingView(c, s, notice)
            s.loading -> PleosPanel(Modifier.fillMaxWidth()) { Txt("불러오는 중…", secondary = true, modifier = Modifier.testTag("loading")) }
            else -> Parked(c, s, mode)
        }
    }
}

// ---------------- 주행 중: 읽기 전용 한 화면(조작 요소 없음) ----------------
@Composable
private fun DrivingView(c: AppController, s: UiState, notice: String?) {
    Column(Modifier.fillMaxSize().testTag("driving_view"), verticalArrangement = Arrangement.spacedBy(PleosSpacing.m)) {
        PleosPanel(Modifier.fillMaxWidth()) {
            val r = s.risk
            Txt("졸음 위험지수", PleosTheme.typography.bodyMedium, secondary = true)
            Txt(r?.score?.toString() ?: "–", PleosTheme.typography.headlineLarge, modifier = Modifier.testTag("risk_score"))
            Txt(r?.stage?.label ?: "계산 중", PleosTheme.typography.titleMedium, modifier = Modifier.testTag("risk_stage"))
            val a = s.wake.alert
            if (a != null) {
                Txt(a.line, PleosTheme.typography.bodyLarge, modifier = Modifier.testTag("banner_msg"))
                a.shelterLine?.let { Txt(it, PleosTheme.typography.bodyMedium, modifier = Modifier.testTag("banner_shelter")) }
                Txt(a.status, PleosTheme.typography.bodyMedium, modifier = Modifier.testTag("banner_status"))
                a.detail?.let { Txt(it, PleosTheme.typography.bodyMedium, secondary = true, modifier = Modifier.testTag("banner_detail")) }
            } else {
                Txt("연속 주행 ${s.drivingMin}분", PleosTheme.typography.bodyLarge, secondary = true, modifier = Modifier.testTag("driving_idle"))
                notice?.let { Txt(it, PleosTheme.typography.bodyMedium, secondary = true, modifier = Modifier.testTag("driving_notice")) }
            }
            if (s.sim.active) Txt("시연 신호가 섞여 있어요", PleosTheme.typography.labelMedium, secondary = true)
        }
    }
}

// ---------------- 정차 ----------------
@Composable
private fun Summary(s: UiState) {
    PleosPanel(Modifier.fillMaxWidth(), title = "졸음 위험지수") {
        val r = s.risk
        Txt(r?.score?.toString() ?: "–", PleosTheme.typography.headlineMedium, modifier = Modifier.testTag("risk_score"))
        if (r != null) PleosStatusBadge("${r.stage.label} (주의 ${r.thresholds.s1} · 경고 ${r.thresholds.s2} · 위험 ${r.thresholds.s3})", stageStatus(r.stage))
        Txt("차량 신호 기반 추정이며 졸음을 직접 측정한 값이 아니에요", PleosTheme.typography.labelMedium, secondary = true)
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
        Txt(a.line, modifier = Modifier.testTag("banner_msg"))
        a.shelterLine?.let { Txt(it, modifier = Modifier.testTag("banner_shelter")) }
        Txt(a.status, PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("banner_status"))
        a.detail?.let { Txt(it, PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("banner_detail")) }
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
        Txt("개인 보정 +${s.p.calibration.offset} (졸리지 않다고 답한 횟수 ${s.p.calibration.feedbackCount}회)", PleosTheme.typography.labelMedium, secondary = true)
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "지금") {
        Txt("연속 주행 ${s.drivingMin}분 · 내기순환 ${s.recircMin?.let { "${it}분" } ?: "꺼짐/미확인"}", secondary = true)
        Txt(if (s.steerReady) "조향 패턴 수집 중(정상)" else "조향 패턴은 40km/h 이상으로 30초 이상 달려야 판단해요", PleosTheme.typography.labelMedium, secondary = true)
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
            PleosStatusBadge(NaviText.shortWord(v) ?: "요청 실패", PleosStatus.Negative)
            Txt(v.reason, PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("navi_reason"))
        }
    }
}

/** 쉼터 화면 안내 문장: 직선거리 안내는 항상, '가상 데이터' 문장은 샘플 데이터일 때만(배지와 같은 조건). */
internal fun shelterNotice(sample: Boolean): String =
    "직선거리 기준이며 실제 도로 거리·진행 방향은 반영하지 않아요." + if (sample) " 아래 항목은 형식 확인용 가상 데이터이며 실제 쉼터가 아니에요." else ""

@Composable
private fun ShelterPage(c: AppController, s: UiState) {
    PleosPanel(Modifier.fillMaxWidth(), title = "가까운 졸음쉼터") {
        val ds = s.dataset
        if (ds == null) Txt("쉼터 데이터를 불러오지 못했어요", secondary = true)
        else {
            if (ds.sample) PleosStatusBadge("시연용 가상 샘플 데이터", PleosStatus.Autonomous)
            Txt(shelterNotice(ds.sample), PleosTheme.typography.labelMedium, secondary = true)
        }
        NaviLine(c)
        when {
            s.origin == null -> Txt("현재 위치를 확인하지 못했어요", secondary = true, modifier = Modifier.testTag("no_location"))
            s.nearby.isEmpty() && ds != null -> Txt("100km 안에 쉼터 데이터가 없어요", secondary = true, modifier = Modifier.testTag("no_shelter"))
        }
        s.nearby.forEach { n ->
            PleosListItem(
                title = n.shelter.name,
                description = "${String.format(Locale.KOREA, "%.1f", n.distanceKm)}km · ${n.shelter.roadName} ${n.shelter.direction}",
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
        Txt("권장 시간은 일반적인 기준이며 개인차가 있어요", PleosTheme.typography.labelMedium, secondary = true)
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "휴식 기록") {
        if (s.p.rests.isEmpty()) Txt("아직 기록이 없어요. 정차 15분 이상이면 휴식으로 기록해요", secondary = true, modifier = Modifier.testTag("no_rests"))
        s.p.rests.asReversed().take(5).forEach { r ->
            PleosListItem(
                title = fmt.format(Date(r.atWallMs)) + if (r.sim) " [시연]" else "",
                description = "연속 ${r.drivingMin}분 주행 후 ${r.restMin}분 휴식",
            )
        }
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "각성 퀴즈") {
        val q = s.p.quiz
        Txt("${q.total}회 중 ${q.passed}회 통과" + (q.lastResponseMs?.let { " · 마지막 응답 ${String.format(Locale.KOREA, "%.1f", it / 1000.0)}초" } ?: ""), secondary = true)
        Txt("응답 시간은 질문이 끝난 뒤부터 인식 결과가 나올 때까지의 근사치예요", PleosTheme.typography.labelMedium, secondary = true)
    }
}

@Composable
private fun SettingsPage(c: AppController, s: UiState) {
    val st = s.p.settings
    PleosPanel(Modifier.fillMaxWidth(), title = "깨우기") {
        PleosSwitch(checked = st.voiceReply, onCheckedChange = { c.setVoiceReply(it) }, label = "음성 응답 사용", modifier = Modifier.testTag("set_voice_reply"))
        Txt(if (st.voiceReply) "질문 뒤 최대 8초 마이크로 한 번 듣고 수락 표현이면 진행해요. 음성은 저장하지 않아요." else "안내만 하고 듣지 않아요.", PleosTheme.typography.labelMedium, secondary = true)
        PleosSwitch(checked = st.hvacControl, onCheckedChange = { c.setHvacControl(it) }, label = "공조 자동 조절(2단계)", modifier = Modifier.testTag("set_hvac"))
        Txt("2단계에서 외기 순환·바람 세기·설정 온도를 바꿔요. 결과는 차량 응답을 받은 뒤에만 알려요.", PleosTheme.typography.labelMedium, secondary = true)
        PleosSwitch(checked = st.beep, onCheckedChange = { c.setBeep(it) }, label = "경고음", modifier = Modifier.testTag("set_beep"))
        PleosButton("개인 보정 초기화", onClick = { c.resetCalibration() }, hierarchy = PleosButtonHierarchy.Secondary, enabled = s.p.calibration.offset > 0)
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "공조 연결") {
        val h = s.hvac
        Txt(
            when {
                !h.capsKnown -> "확인 중"
                !h.connected -> "연결 안 됨 또는 지원 항목 없음" + (h.error?.let { " ($it)" } ?: "")
                else -> "연결됨 · 변경 가능: " + listOfNotNull("외기 순환".takeIf { h.caps.recirc }, "풍량".takeIf { h.caps.fan }, "온도".takeIf { h.caps.temp }).joinToString(", ").ifEmpty { "없음(읽기만)" }
            },
            modifier = Modifier.testTag("hvac_status"),
        )
        Txt("공조 전원: " + (h.now.power?.let { if (it) "켜짐" else "꺼짐(풍량 변경이 거부될 수 있어요)" } ?: "알 수 없음"), PleosTheme.typography.labelMedium, secondary = true)
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "저장된 기록과 개인정보") {
        Txt("이 기기 안에만 저장해요: 개인 보정값, 휴식 시각·시간, 퀴즈 횟수, 설정, 누적 주행 시간(평문). 위치·좌표·음성은 저장하지 않아요.", PleosTheme.typography.labelMedium, secondary = true)
        if (s.corruptKept) Txt("저장 파일이 손상돼 처음 상태로 시작했어요. 아래 삭제로 지울 수 있어요.", PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("corrupt_kept"))
        if (s.saveHeld) Txt("저장하지 못해 이번 실행에서는 저장하지 않아요. 앱을 다시 시작하거나 삭제하면 다시 저장해요.", PleosTheme.typography.labelMedium, secondary = true, modifier = Modifier.testTag("save_held"))
        var ask by remember { mutableStateOf(false) }
        PleosButton("저장된 기록 모두 삭제", onClick = { ask = true }, hierarchy = PleosButtonHierarchy.Secondary, modifier = Modifier.testTag("clear_all"))
        if (ask) PleosPopup(
            title = "저장된 기록을 모두 삭제할까요?",
            description = "개인 보정값·휴식 기록·퀴즈 기록·누적 주행 시간이 삭제돼요(설정은 유지).\n되돌릴 수 없어요.",
            confirmLabel = "삭제", onConfirm = { ask = false; c.clearAllData() },
            onDismissRequest = { ask = false }, cancelLabel = "취소",
        )
    }
    PleosPanel(Modifier.fillMaxWidth(), title = "알아두세요") {
        Txt("앱이 다른 화면에 가려지면 음성 안내·음성 인식이 멈춰요. 이 앱은 운전자 보조 도구이며 졸음 운전을 막을 수 없어요. 졸리면 즉시 안전한 곳에 정차해 쉬세요.", PleosTheme.typography.labelMedium, secondary = true)
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

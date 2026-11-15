package com.hmgcarapp.wakemate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.text.style.LineBreak
import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.core.ui.rememberDriveSnapshot
import com.hmgcarapp.pleos.designsystem.component.PleosButton
import com.hmgcarapp.pleos.designsystem.component.PleosButtonHierarchy
import com.hmgcarapp.pleos.designsystem.component.PleosFactorBarRow
import com.hmgcarapp.pleos.designsystem.component.PleosHeroSize
import com.hmgcarapp.pleos.designsystem.component.PleosListItem
import com.hmgcarapp.pleos.designsystem.component.PleosPanel
import com.hmgcarapp.pleos.designsystem.component.PleosPopup
import com.hmgcarapp.pleos.designsystem.component.PleosScaffold
import com.hmgcarapp.pleos.designsystem.component.PleosSegmentedTabs
import com.hmgcarapp.pleos.designsystem.component.PleosStageHeroBanner
import com.hmgcarapp.pleos.designsystem.component.PleosStatus
import com.hmgcarapp.pleos.designsystem.component.PleosStatusBadge
import com.hmgcarapp.pleos.designsystem.component.PleosStimulusSpec
import com.hmgcarapp.pleos.designsystem.component.PleosStimulusStrip
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
import com.hmgcarapp.wakemate.domain.StimulusView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 한국어 문장이 단어 중간에서 끊기지 않게(어절 단위 줄바꿈, API 33+). */
private val PhraseBreak = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Normal, LineBreak.WordBreak.Phrase)

@Composable
private fun Txt(t: String, style: TextStyle = PleosTheme.typography.bodyMedium, secondary: Boolean = false, modifier: Modifier = Modifier) =
    Text(t, style = style, color = if (secondary) PleosTheme.colors.textDarkSecondary else PleosTheme.colors.textDarkPrimary, modifier = modifier)

/** 현재 표시할 강조 단계: 진행 중인 깨우기가 있으면 그 단계와 현재 위험 단계 중 높은 쪽을 반영한다. */
private fun activeStageOf(s: UiState): Stage {
    val rStage = s.risk?.stage ?: Stage.NONE
    val aStage = s.wake.alert?.stage ?: Stage.NONE
    return if (aStage.n > rStage.n) aStage else rStage
}

/** 물리 자극 카드 3장: 차량 응답(쓰기 성공·읽은 값) 기준 상태를 카드 명세로 바꾼다. */
private fun stimulusSpecs(s: UiState): List<PleosStimulusSpec> =
    StimulusView.of(
        StimulusView.Input(
            capsKnown = s.hvac.capsKnown,
            unavailable = s.hvac.error != null,
            caps = s.hvac.caps,
            now = s.hvac.now,
            last = s.wake.lastHvac,
            alertStage = s.wake.alert?.takeIf { !it.practice }?.stage,
            autoControl = s.p.settings.hvacControl,
            beep = s.p.settings.beep,
        ),
    ).map { PleosStimulusSpec(it.title, it.label, it.detail, it.active) }

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
        subtitle = (if (s.risk == null && s.wake.alert == null) "준비 중" else "졸음 ${activeStageOf(s).label}") + " · " + (if (driving) "주행 중" else "정차 중"),
        toastState = toast,
        supportingPane = if (driving || s.loading) null else { m -> Column(m.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(PleosSpacing.m)) { Summary(c, s) } },
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
    val r = s.risk
    val stage = activeStageOf(s)
    val a = s.wake.alert
    Column(Modifier.fillMaxSize().testTag("driving_view"), verticalArrangement = Arrangement.spacedBy(PleosSpacing.m)) {
        // 화면 대부분을 단계 색으로 채워 곁눈으로도 단계가 보이게 한다(아래 자극 카드 자리만 남긴다)
        PleosStageHeroBanner(
            score = r?.score,
            stageLevel = stage.n,
            modifier = Modifier.fillMaxHeight(0.8f),
            size = PleosHeroSize.Driving,
            scoreContent = { fg, st -> Text(r?.score?.toString() ?: "–", style = st, color = fg, modifier = Modifier.testTag("risk_score")) },
            stageContent = { fg, st -> Text(if (r == null && a == null) "계산 중" else stage.label, style = st, color = fg, modifier = Modifier.testTag("risk_stage")) },
        ) { fgPrimary, fgSecondary ->
            if (a != null) {
                a.shelterLine?.let { Text(it, style = PleosTheme.typography.titleMedium, color = fgPrimary, modifier = Modifier.testTag("banner_shelter")) }
                if (!Status.isProgress(a.status)) Text(a.status, style = PleosTheme.typography.titleSmall, color = fgPrimary, modifier = Modifier.testTag("banner_status"))
                a.detail?.let { Text(it, style = PleosTheme.typography.bodyLarge.copy(lineBreak = PhraseBreak), color = fgSecondary, modifier = Modifier.testTag("banner_detail")) }
            } else {
                Text("쉬지 않은 주행 ${s.drivingMin}분", style = PleosTheme.typography.titleSmall, color = fgPrimary, modifier = Modifier.testTag("driving_idle"))
                Text(RestAdvice.next(s.drivingMin, stage), style = PleosTheme.typography.bodyLarge, color = fgSecondary)
            }
        }
        PleosStimulusStrip(items = stimulusSpecs(s), stageLevel = stage.n, showDetail = false)
    }
}

// ---------------- 정차 ----------------
/** 정차 화면 보조 패널: 단계 색 히어로 + 빠른 조작. 카드 안에 카드를 겹치지 않는다. */
@Composable
private fun Summary(c: AppController, s: UiState) {
    val r = s.risk
    val stage = activeStageOf(s)
    PleosStageHeroBanner(
        score = r?.score,
        stageLevel = stage.n,
        size = PleosHeroSize.Compact,
        chipLabel = "졸음 위험지수",
        scoreContent = { fg, st -> Text(r?.score?.toString() ?: "–", style = st, color = fg, modifier = Modifier.testTag("risk_score")) },
        stageContent = { fg, st -> Text(if (r == null && s.wake.alert == null) "계산 중" else stage.label, style = st, color = fg) },
    ) { fgPrimary, fgSecondary ->
        Text("연속 주행 ${s.drivingMin}분" + (s.recircMin?.let { " · 내기순환 ${it}분" } ?: ""), style = PleosTheme.typography.bodyMedium, color = fgPrimary)
        Text(RestAdvice.next(s.drivingMin, stage), style = PleosTheme.typography.labelMedium.copy(lineBreak = PhraseBreak), color = fgSecondary)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(PleosSpacing.m)) {
        PleosButton("졸리지 않아요", onClick = { c.notSleepy() }, hierarchy = PleosButtonHierarchy.Secondary, modifier = Modifier.testTag("btn_not_sleepy"))
        PleosButton("퀴즈 연습", onClick = { c.startQuizPractice() }, hierarchy = PleosButtonHierarchy.Secondary, modifier = Modifier.testTag("btn_quiz"))
    }
}

@Composable
private fun Parked(c: AppController, s: UiState, mode: PleosWindowMode) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(PleosSpacing.m)) {
        if (mode == PleosWindowMode.Portrait) Summary(c, s)
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
    val stage = activeStageOf(s)
    PleosStimulusStrip(items = stimulusSpecs(s), stageLevel = stage.n)
    PleosPanel(Modifier.fillMaxWidth(), title = "위험지수 구성") {
        if (r == null) Txt("계산 중…", secondary = true)
        else r.factors.forEach { f ->
            PleosFactorBarRow(
                title = f.label, description = f.note,
                points = f.points, max = f.max,
                trailing = { Txt("${f.points} / ${f.max}", modifier = Modifier.testTag("factor_${f.key}")) },
            )
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
        if (s.nearby.isEmpty()) {
            Txt(
                if (s.origin == null) "현재 위치를 확인하면 가까운 졸음쉼터와 휴게소를 거리순으로 보여드려요."
                else "주변 100km 이내에서 안내 가능한 졸음쉼터·휴게소를 찾지 못했어요.",
                secondary = true,
            )
        }
        s.nearby.forEach { n ->
            val extra = buildList {
                n.shelter.parkingSlots?.let { add("주차 ${it}면") }
                n.shelter.restroom?.let { add(if (it) "화장실 있음" else "화장실 없음") }
            }.joinToString(" · ")
            val desc = "${String.format(Locale.KOREA, "%.1f", n.distanceKm)}km · ${n.shelter.kindLabel} · ${n.shelter.roadName} ${n.shelter.direction}" +
                if (extra.isNotEmpty()) " · $extra" else ""
            PleosListItem(
                title = n.shelter.name,
                description = desc,
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
        if (s.p.rests.isEmpty()) {
            Txt("아직 기록된 휴식이 없어요. 10분 이상 주행한 뒤 15분 이상 정차하면 자동으로 기록됩니다.", secondary = true)
        }
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

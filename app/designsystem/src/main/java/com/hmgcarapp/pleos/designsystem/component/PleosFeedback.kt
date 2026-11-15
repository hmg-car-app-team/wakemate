package com.hmgcarapp.pleos.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hmgcarapp.pleos.designsystem.theme.PleosColors
import com.hmgcarapp.pleos.designsystem.theme.PleosMotion
import com.hmgcarapp.pleos.designsystem.theme.PleosRadius
import com.hmgcarapp.pleos.designsystem.theme.PleosShadowLevel
import com.hmgcarapp.pleos.designsystem.theme.PleosSize
import com.hmgcarapp.pleos.designsystem.theme.PleosSpacing
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme
import com.hmgcarapp.pleos.designsystem.theme.pleosShadow
import com.hmgcarapp.pleos.designsystem.theme.strong
import kotlinx.coroutines.delay

/** 로딩 표시. 응답이 0.25초를 넘길 때 보여줍니다. */
@Composable
fun PleosSpinner(modifier: Modifier = Modifier, size: Dp = 40.dp) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        color = PleosTheme.colors.textDarkPrimary,
        trackColor = PleosTheme.colors.basicAlphaDark100,
        strokeWidth = size / 8,
    )
}

/** System popup 너비: 공식 Large 1200dp / Medium 808dp (Connect-S) */
enum class PleosPopupSize { Large, Medium }

/** 버튼 정렬: Single / Horizontal / Vertical */
enum class PleosPopupButtonAlign { Single, Horizontal, Vertical }

/**
 * System popup. Title, Description(선택), Confirm, Cancel(선택)로 구성합니다.
 * 본문 슬롯(content)에 서비스 맥락에 맞는 항목을 넣을 수 있습니다.
 */
@Composable
fun PleosPopup(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    description: String? = null,
    cancelLabel: String? = null,
    onCancel: () -> Unit = onDismissRequest,
    size: PleosPopupSize = PleosPopupSize.Medium,
    buttonAlign: PleosPopupButtonAlign = if (cancelLabel == null) PleosPopupButtonAlign.Single else PleosPopupButtonAlign.Horizontal,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = PleosTheme.colors
    val typography = PleosTheme.typography
    val d = PleosTheme.dimensions
    val width = if (size == PleosPopupSize.Large) d.popupLargeWidth else d.popupMediumWidth
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = width)
                .heightIn(max = d.popupMaxHeight)
                .fillMaxWidth()
                .pleosShadow(PleosShadowLevel.Level3, d.popupRadius, colors.backgroundPopup)
                .clip(RoundedCornerShape(d.popupRadius))
                .background(colors.backgroundPopup)
                .padding(d.popupPadding),
            verticalArrangement = Arrangement.spacedBy(d.popupGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = typography.titleSmall, color = colors.textDarkPrimary, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            description?.let {
                Text(it, style = typography.bodyMedium, color = colors.textDarkSecondary, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            content?.invoke(this)
            when (buttonAlign) {
                PleosPopupButtonAlign.Single ->
                    PleosButton(confirmLabel, onConfirm, Modifier.fillMaxWidth(), hierarchy = PleosButtonHierarchy.Secondary, size = PleosButtonSize.Large)
                PleosPopupButtonAlign.Horizontal -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    cancelLabel?.let { PleosButton(it, onCancel, Modifier.weight(1f), size = PleosButtonSize.Large) }
                    PleosButton(confirmLabel, onConfirm, Modifier.weight(1f), hierarchy = PleosButtonHierarchy.Secondary, size = PleosButtonSize.Large)
                }
                PleosPopupButtonAlign.Vertical -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PleosButton(confirmLabel, onConfirm, Modifier.fillMaxWidth(), hierarchy = PleosButtonHierarchy.Secondary, size = PleosButtonSize.Large)
                    cancelLabel?.let { PleosButton(it, onCancel, Modifier.fillMaxWidth(), size = PleosButtonSize.Large) }
                }
            }
        }
    }
}

/** 단계별 반전 색 조합. 컴포넌트와 대비 테스트가 같은 함수를 쓴다. */
data class PleosStageColors(
    val background: Color,
    val content: Color,
    val contentSecondary: Color,
    val accent: Color,
    val track: Color,
)

/**
 * 위험 단계별 반전 색. 0=정상(기본 면), 1=주의(노랑), 2=경고(주황), 3 이상=위험(빨강).
 * 노랑·주황·빨강 배경에는 어두운 글씨를 쓴다(흰 글씨는 빨강 위에서 본문 대비 4.5:1에 못 미친다).
 */
fun pleosStageColors(c: PleosColors, stageLevel: Int): PleosStageColors {
    if (stageLevel <= 0) return PleosStageColors(c.surfaceBasic, c.textDarkPrimary, c.textDarkSecondary, c.informativeActive, c.basicAlphaDark100)
    val bg = when (stageLevel) {
        1 -> c.regulationYellow
        2 -> c.regulationOrange
        else -> c.informativeNegative
    }
    val ink = c.basicStaticDark100
    return PleosStageColors(bg, ink, ink.copy(alpha = 0.9f), ink, ink.copy(alpha = 0.18f))
}

/** 작동 중인 자극 카드 색. 위험 단계면 그 단계 색, 정상 단계면 진한 채움 면. */
fun pleosActiveCardColors(c: PleosColors, stageLevel: Int): PleosStageColors =
    if (stageLevel >= 1) pleosStageColors(c, stageLevel)
    else PleosStageColors(c.buttonFilledEnabled, c.textLightPrimary, c.textLightSecondary, c.textLightPrimary, c.textLightPrimary.copy(alpha = 0.18f))

@Composable
private fun StageGauge(fraction: Float, diameter: Dp, stroke: Dp, sc: PleosStageColors, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier = Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val sw = stroke.toPx()
            val pad = sw / 2f
            val arc = Size(this.size.width - sw, this.size.height - sw)
            drawArc(sc.track, 135f, 270f, false, Offset(pad, pad), arc, style = Stroke(sw, cap = StrokeCap.Round))
            drawArc(sc.accent, 135f, 270f * fraction, false, Offset(pad, pad), arc, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

/** 히어로 크기: 정차 화면 보조 패널(Compact), 일반(Regular), 주행 화면 전체(Driving). */
enum class PleosHeroSize { Compact, Regular, Driving }

/**
 * 위험 단계에 맞춰 배경 전체 색이 바뀌는 히어로 배너(아크 게이지 + 점수 + 단계 + 상세).
 * 색은 [pleosStageColors]. 단계가 바뀔 때 한 번만 부드럽게 전환하고 깜빡이지 않는다(주행 중 주의분산 최소화).
 * [scoreContent]·[stageContent] 는 배경에 맞는 글자색과 크기별 글꼴을 받는다. 높이를 주면 내용은 세로 가운데에 놓인다.
 */
@Composable
fun PleosStageHeroBanner(
    score: Int?,
    stageLevel: Int,
    modifier: Modifier = Modifier,
    size: PleosHeroSize = PleosHeroSize.Regular,
    chipLabel: String? = null,
    scoreContent: @Composable (Color, TextStyle) -> Unit,
    stageContent: @Composable (Color, TextStyle) -> Unit,
    detailContent: @Composable ColumnScope.(Color, Color) -> Unit = { _, _ -> },
) {
    val colors = PleosTheme.colors
    val type = PleosTheme.typography
    val d = PleosTheme.dimensions
    val sc = pleosStageColors(colors, stageLevel)
    val bg by animateColorAsState(sc.background, tween(PleosMotion.DurationMedium1), label = "hero-bg")
    val fraction by animateFloatAsState(
        targetValue = ((score ?: 0).coerceIn(0, 100)) / 100f,
        animationSpec = tween(PleosMotion.DurationMedium1, easing = PleosMotion.Emphasized),
        label = "hero-arc",
    )
    val outline = if (colors.isDark) colors.basicAlphaDark100 else colors.basicAlphaDark50
    val gauge = when (size) { PleosHeroSize.Compact -> 148.dp; PleosHeroSize.Regular -> 240.dp; PleosHeroSize.Driving -> 400.dp }
    val stroke = when (size) { PleosHeroSize.Compact -> 14.dp; PleosHeroSize.Regular -> 22.dp; PleosHeroSize.Driving -> 32.dp }
    val gap = when (size) { PleosHeroSize.Compact -> 24.dp; PleosHeroSize.Regular -> 40.dp; PleosHeroSize.Driving -> 72.dp }
    val padH = when (size) { PleosHeroSize.Compact -> 24.dp; PleosHeroSize.Regular -> 40.dp; PleosHeroSize.Driving -> 64.dp }
    val scoreStyle = when (size) {
        PleosHeroSize.Compact -> type.headlineMedium
        PleosHeroSize.Regular -> type.headlineLarge
        PleosHeroSize.Driving -> type.headlineLarge.copy(fontSize = 128.sp, lineHeight = 136.sp)
    }
    val stageStyle = when (size) { PleosHeroSize.Compact -> type.titleSmall; PleosHeroSize.Regular -> type.titleMedium; PleosHeroSize.Driving -> type.headlineMedium }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.listRadius))
            .background(bg)
            .border(1.dp, if (stageLevel >= 1) sc.content.copy(alpha = 0.2f) else outline, RoundedCornerShape(d.listRadius))
            .padding(horizontal = padH, vertical = if (size == PleosHeroSize.Compact) 24.dp else 36.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        chipLabel?.let {
            Row(
                modifier = Modifier.clip(RoundedCornerShape(100)).background(sc.content.copy(alpha = 0.1f)).padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(sc.accent))
                Text(it, style = type.labelMedium.strong(), color = sc.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StageGauge(fraction, gauge, stroke, sc) {
                scoreContent(sc.content, scoreStyle)
                if (size != PleosHeroSize.Compact) Text("/ 100", style = type.labelMedium, color = sc.contentSecondary)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (size == PleosHeroSize.Compact) 10.dp else 16.dp)) {
                stageContent(sc.content, stageStyle)
                detailContent(sc.content, sc.contentSecondary)
            }
        }
    }
}

/** 물리 자극 1종 카드 명세. [active] 는 차량이 작동을 확인해 준 경우만 true. */
data class PleosStimulusSpec(
    val title: String,
    val stateLabel: String,
    val detail: String,
    val active: Boolean,
)

/**
 * 차량 물리 자극 카드 줄. 작동이 확인된 장치만 단계 색으로 켜진다.
 * 주행 중에는 [showDetail]=false 로 제목과 상태만 보여 읽을 글을 줄인다.
 */
@Composable
fun PleosStimulusStrip(
    items: List<PleosStimulusSpec>,
    stageLevel: Int,
    modifier: Modifier = Modifier,
    showDetail: Boolean = true,
) {
    val colors = PleosTheme.colors
    val d = PleosTheme.dimensions
    val idle = PleosStageColors(colors.surfaceBasic, colors.textDarkPrimary, colors.textDarkSecondary, colors.textDarkTertiary, colors.basicAlphaDark100)
    val outline = if (colors.isDark) colors.basicAlphaDark100 else colors.basicAlphaDark50
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PleosSpacing.m)) {
        items.forEach { item ->
            val sc = if (item.active) pleosActiveCardColors(colors, stageLevel) else idle
            val bg by animateColorAsState(sc.background, tween(PleosMotion.DurationMedium1), label = "stimulus-bg")
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(d.listRadius))
                    .background(bg)
                    .border(1.dp, if (item.active) sc.content.copy(alpha = 0.2f) else outline, RoundedCornerShape(d.listRadius))
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(item.title, style = PleosTheme.typography.bodyMedium.strong(), color = sc.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (item.active) sc.content else sc.accent))
                    Text(item.stateLabel, style = PleosTheme.typography.labelMedium.strong(), color = sc.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (showDetail) Text(item.detail, style = PleosTheme.typography.labelMedium, color = sc.contentSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * 위험지수 구성 요인 프로그레스 바 행.
 * 제목·현재 측정값 설명·배점 슬롯(trailing)과 함께 시각적 수평 게이지 바를 표시합니다.
 */
@Composable
fun PleosFactorBarRow(
    title: String,
    description: String,
    points: Int,
    max: Int,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    val colors = PleosTheme.colors
    val ratio = if (max <= 0) 0f else (points.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    val barColor = when {
        ratio >= 0.75f -> colors.informativeNegative
        ratio >= 0.4f -> colors.regulationOrange
        else -> colors.informativeActive
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PleosRadius.m))
            .background(colors.surfaceLow)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = PleosTheme.typography.bodyMedium.strong(), color = colors.textDarkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(description, style = PleosTheme.typography.labelMedium, color = colors.textDarkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(16.dp))
            trailing()
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(colors.basicAlphaDark100),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(ratio)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(5.dp))
                    .background(barColor),
            )
        }
    }
}

/** Toast 상태. show()를 호출하면 메시지를 잠시 띄웁니다. */
@Stable
class PleosToastState {
    var message: String? by mutableStateOf(null)
        private set
    internal var serial by mutableStateOf(0L)
        private set
    internal var durationMs: Long = 3000

    fun show(message: String, durationMs: Long = 3000) {
        this.message = message
        this.durationMs = durationMs
        serial++
    }

    fun dismiss() {
        message = null
    }
}

@Composable
fun rememberPleosToastState(): PleosToastState = remember { PleosToastState() }

/**
 * Toast host. 앱 패널 안에서 좌우·하단 24dp 여백을 두고,
 * 전체 최대 너비 792dp, 메시지 최대 너비 712dp로 하단 중앙에 띄웁니다.
 */
@Composable
fun BoxScope.PleosToastHost(state: PleosToastState, modifier: Modifier = Modifier) {
    LaunchedEffect(state.serial) {
        if (state.message != null) {
            delay(state.durationMs)
            state.dismiss()
        }
    }
    val d = PleosTheme.dimensions
    AnimatedVisibility(
        visible = state.message != null,
        modifier = modifier.align(Alignment.BottomCenter).padding(start = d.toastMargin, end = d.toastMargin, bottom = d.toastMargin),
        enter = fadeIn(tween(PleosMotion.DurationMedium1)) + slideInVertically(tween(PleosMotion.DurationMedium1, easing = PleosMotion.Emphasized)) { it / 2 },
        exit = fadeOut(tween(PleosMotion.DurationShort2)) + slideOutVertically(tween(PleosMotion.DurationShort2)) { it / 2 },
    ) {
        val colors = PleosTheme.colors
        Box(
            modifier = Modifier
                .widthIn(max = d.toastMaxWidth)
                .pleosShadow(PleosShadowLevel.Level2, d.toastRadius, colors.buttonFilledEnabled)
                .clip(RoundedCornerShape(d.toastRadius))
                .background(colors.buttonFilledEnabled)
                .padding(horizontal = d.toastPaddingH, vertical = d.toastPaddingV),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                state.message.orEmpty(),
                modifier = Modifier.widthIn(max = d.toastMaxWidth - d.toastPaddingH * 2),
                style = PleosTheme.typography.bodyMedium,
                color = colors.textLightPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}


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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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

/**
 * 단계별 전면 색상 반전 히어로 배너.
 * - 0단계(정상): 기본 서피스 + 그린 아크 게이지
 * - 1단계(주의): 앰버(regulationYellow) 전면 색상 반전 + 다크 고대비 텍스트
 * - 2단계(경고): 오렌지(regulationOrange) 전면 색상 반전 + 다크 고대비 텍스트
 * - 3단계(위험): 크림슨 레드(informativeNegative) 전면 색상 반전 + 화이트 고대비 텍스트
 */
@Composable
fun PleosStageHeroBanner(
    score: Int?,
    stageLevel: Int,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    bannerTag: String? = null,
    scoreContent: @Composable (Color) -> Unit,
    stageContent: @Composable (Color) -> Unit,
    detailContent: @Composable ColumnScope.(Color, Color) -> Unit = { _, _ -> },
) {
    val colors = PleosTheme.colors
    val d = PleosTheme.dimensions
    val targetBg = when {
        stageLevel >= 3 -> colors.informativeNegative
        stageLevel == 2 -> colors.regulationOrange
        stageLevel == 1 -> colors.regulationYellow
        else -> colors.surfaceBasic
    }
    val bg by animateColorAsState(targetBg, tween(PleosMotion.DurationMedium1), label = "hero-bg")
    val fgPrimary = when {
        stageLevel >= 3 -> colors.basicStaticLight100
        stageLevel >= 1 -> colors.basicStaticDark100
        else -> colors.textDarkPrimary
    }
    val fgSecondary = when {
        stageLevel >= 3 -> colors.basicStaticLight200
        stageLevel >= 1 -> colors.basicStaticDark200
        else -> colors.textDarkSecondary
    }
    val arcColor = when {
        stageLevel >= 3 -> colors.basicStaticLight100
        stageLevel >= 1 -> colors.basicStaticDark100
        else -> colors.informativeActive
    }
    val trackColor = when {
        stageLevel >= 3 -> colors.basicAlphaLight200
        stageLevel >= 1 -> colors.basicAlphaDark200
        else -> colors.basicAlphaDark100
    }
    val fraction by animateFloatAsState(
        targetValue = ((score ?: 0).coerceIn(0, 100)) / 100f,
        animationSpec = tween(PleosMotion.DurationMedium1, easing = PleosMotion.Emphasized),
        label = "hero-arc",
    )
    val gaugeSize = if (compact) 148.dp else 220.dp
    val strokeDp = if (compact) 14.dp else 20.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.listRadius))
            .background(bg)
            .border(
                width = if (stageLevel >= 1) 2.dp else 1.dp,
                color = if (stageLevel >= 1) fgPrimary.copy(alpha = 0.24f) else if (colors.isDark) colors.basicAlphaDark100 else colors.basicAlphaDark50,
                shape = RoundedCornerShape(d.listRadius),
            )
            .padding(horizontal = if (compact) 24.dp else 36.dp, vertical = if (compact) 24.dp else 32.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 24.dp),
    ) {
        if (bannerTag != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100))
                        .background(fgPrimary.copy(alpha = if (stageLevel >= 1) 0.16f else 0.08f))
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(arcColor))
                    Text(bannerTag, style = PleosTheme.typography.labelMedium.strong(), color = fgPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    when {
                        stageLevel >= 3 -> "즉시 정차 권고 · 전면 위험 경보"
                        stageLevel == 2 -> "물리 각성 장치 작동 · 환기 및 시트·조명 제어"
                        stageLevel == 1 -> "주의 환기 단계 · 음성 안내"
                        else -> "실시간 차량 신호 감시 중"
                    },
                    style = PleosTheme.typography.labelMedium,
                    color = fgSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (compact) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(gaugeSize), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(gaugeSize)) {
                        val sw = strokeDp.toPx()
                        val pad = sw / 2f
                        val arcSize = Size(size.width - sw, size.height - sw)
                        drawArc(color = trackColor, startAngle = 135f, sweepAngle = 270f, useCenter = false, topLeft = Offset(pad, pad), size = arcSize, style = Stroke(width = sw, cap = StrokeCap.Round))
                        drawArc(color = arcColor, startAngle = 135f, sweepAngle = 270f * fraction, useCenter = false, topLeft = Offset(pad, pad), size = arcSize, style = Stroke(width = sw, cap = StrokeCap.Round))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        scoreContent(fgPrimary)
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    stageContent(fgPrimary)
                    detailContent(fgPrimary, fgSecondary)
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(gaugeSize), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(gaugeSize)) {
                        val sw = strokeDp.toPx()
                        val pad = sw / 2f
                        val arcSize = Size(size.width - sw, size.height - sw)
                        drawArc(color = trackColor, startAngle = 135f, sweepAngle = 270f, useCenter = false, topLeft = Offset(pad, pad), size = arcSize, style = Stroke(width = sw, cap = StrokeCap.Round))
                        drawArc(color = arcColor, startAngle = 135f, sweepAngle = 270f * fraction, useCenter = false, topLeft = Offset(pad, pad), size = arcSize, style = Stroke(width = sw, cap = StrokeCap.Round))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        scoreContent(fgPrimary)
                        Text("/ 100", style = PleosTheme.typography.labelMedium, color = fgSecondary)
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    stageContent(fgPrimary)
                    detailContent(fgPrimary, fgSecondary)
                }
            }
        }
    }
}

/** 물리 자극 1종 상태 데이터. */
data class PleosStimulusSpec(
    val title: String,
    val stateLabel: String,
    val detail: String,
    val active: Boolean,
)

/**
 * 차량 물리 자극 3종(공조 냉풍 환기 · 운전석 시트 통풍 · 실내 앰비언트 조명/경고음) 라이브 상태 스트립.
 * 작동 중인 장치는 단계별 강조 배경으로 반전 점등되어 어떤 하드웨어가 깨우고 있는지 한눈에 보여줍니다.
 */
@Composable
fun PleosStimulusStrip(
    items: List<PleosStimulusSpec>,
    stageLevel: Int,
    modifier: Modifier = Modifier,
) {
    val colors = PleosTheme.colors
    val d = PleosTheme.dimensions
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PleosSpacing.m),
    ) {
        items.forEach { item ->
            val activeBg = when {
                !item.active -> colors.surfaceBasic
                stageLevel >= 3 -> colors.informativeNegative
                stageLevel == 2 -> colors.regulationOrange
                stageLevel == 1 -> colors.regulationYellow
                else -> colors.informativePositive
            }
            val cardBg by animateColorAsState(activeBg, tween(PleosMotion.DurationMedium1), label = "stim-bg")
            val fgPrimary = when {
                !item.active -> colors.textDarkPrimary
                stageLevel >= 3 || stageLevel == 0 -> colors.basicStaticLight100
                else -> colors.basicStaticDark100
            }
            val fgSecondary = when {
                !item.active -> colors.textDarkSecondary
                stageLevel >= 3 || stageLevel == 0 -> colors.basicStaticLight200
                else -> colors.basicStaticDark200
            }
            val dotColor = when {
                !item.active -> colors.textDarkTertiary
                stageLevel >= 3 || stageLevel == 0 -> colors.basicStaticLight100
                else -> colors.basicStaticDark100
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(d.listRadius))
                    .background(cardBg)
                    .border(
                        width = if (item.active) 2.dp else 1.dp,
                        color = if (item.active) fgPrimary.copy(alpha = 0.28f) else if (colors.isDark) colors.basicAlphaDark100 else colors.basicAlphaDark50,
                        shape = RoundedCornerShape(d.listRadius),
                    )
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        item.title,
                        style = PleosTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = fgPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(100))
                            .background(fgPrimary.copy(alpha = if (item.active) 0.18f else 0.08f))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                        Text(item.stateLabel, style = PleosTheme.typography.labelSmall.strong(), color = fgPrimary, maxLines = 1)
                    }
                }
                Text(
                    item.detail,
                    style = PleosTheme.typography.labelMedium,
                    color = fgSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
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


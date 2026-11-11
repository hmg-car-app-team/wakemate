package com.hmgcarapp.pleos.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme
import com.hmgcarapp.pleos.designsystem.theme.pleosShadow
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


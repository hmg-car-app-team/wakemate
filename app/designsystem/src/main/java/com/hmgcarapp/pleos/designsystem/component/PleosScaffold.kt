package com.hmgcarapp.pleos.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hmgcarapp.pleos.designsystem.theme.PleosSpacing
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme
import com.hmgcarapp.pleos.designsystem.theme.PleosWindowMode
import com.hmgcarapp.pleos.designsystem.theme.pleosWindowMode
import com.hmgcarapp.pleos.designsystem.theme.strong

/**
 * 앱 화면 뼈대. Page navigation + 본문 + Toast를 한 번에 배치하고,
 * 창 너비에 따라 Landscape(2단) / Portrait(1단)를 자동으로 고릅니다.
 *
 * - supportingPane: Landscape에서 왼쪽에 고정되는 목록·카테고리 영역(기본 400dp)
 * - content: 본문. 현재 창 형태를 받아 Portrait에서 다른 배치를 할 수 있습니다.
 *   Portrait에서는 supportingPane이 사라지므로 목록 → Depth 화면 전환으로 처리합니다.
 */
@Composable
fun PleosScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    appIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    toastState: PleosToastState? = null,
    supportingPane: (@Composable (Modifier) -> Unit)? = null,
    supportingPaneWidth: Dp = 400.dp,
    content: @Composable (PleosWindowMode) -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(PleosTheme.colors.backgroundPrimary)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(Modifier.fillMaxSize()) {
            PleosPageNavigation(title = title, subtitle = subtitle, onBack = onBack, appIcon = appIcon, actions = actions)
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = PleosSpacing.l, end = PleosSpacing.l, bottom = PleosSpacing.l),
            ) {
                val mode = pleosWindowMode(maxWidth)
                if (mode == PleosWindowMode.Landscape && supportingPane != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(PleosSpacing.l)) {
                        supportingPane(Modifier.width(supportingPaneWidth).fillMaxHeight())
                        Box(Modifier.weight(1f).fillMaxHeight()) { content(mode) }
                    }
                } else {
                    content(mode)
                }
            }
        }
        toastState?.let { PleosToastHost(it) }
    }
}

/** 상태 종류. 색은 informative 토큰을 씁니다. */
enum class PleosStatus { Active, Positive, Negative, Autonomous, Neutral }

/**
 * 상태 배지(점 + 라벨). 예: "연결됨", "지원 안 함", "충전 중".
 * informative 색 글씨는 흰 배경에서 대비 4.5:1에 못 미치므로 색은 점과 옅은 배경에만 쓰고
 * 글씨는 text_dark_primary로 둡니다.
 */
@Composable
fun PleosStatusBadge(label: String, status: PleosStatus, modifier: Modifier = Modifier) {
    val colors = PleosTheme.colors
    val tone = when (status) {
        PleosStatus.Active -> colors.informativeActive
        PleosStatus.Positive -> colors.informativePositive
        PleosStatus.Negative -> colors.informativeNegative
        PleosStatus.Autonomous -> colors.informativeAutonomousDriving
        PleosStatus.Neutral -> colors.textDarkTertiary
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(100))
            .background(tone.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(tone))
        Text(label, style = PleosTheme.typography.labelMedium.strong(), color = colors.textDarkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

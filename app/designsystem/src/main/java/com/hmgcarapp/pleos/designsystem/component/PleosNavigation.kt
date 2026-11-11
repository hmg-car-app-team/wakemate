package com.hmgcarapp.pleos.designsystem.component

import com.hmgcarapp.pleos.designsystem.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hmgcarapp.pleos.designsystem.theme.PleosRadius
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme
import com.hmgcarapp.pleos.designsystem.theme.pleosPressScale

/**
 * Page navigation.
 * - Main: [앱 아이콘] 제목(Primary menu) [Secondary menu] ... [Suffix 아이콘들]
 * - Depth: [뒤로] 제목 / 부제목 ... [Suffix]
 * primaryMenu·secondaryMenu 슬롯에는 PleosDropdown 등을 넣습니다.
 */
@Composable
fun PleosPageNavigation(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    appIcon: (@Composable () -> Unit)? = null,
    secondaryMenu: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = PleosTheme.colors
    val d = PleosTheme.dimensions
    // 공식: 높이 80, 좌우 여백 40, 요소 간격 24, 앱 아이콘 80 / 모서리 20 (Connect-S)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = d.pageNavHeight)
            .padding(horizontal = d.pageNavPaddingH),
        horizontalArrangement = Arrangement.spacedBy(d.pageNavGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            PleosIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.pleos_back), onBack, style = PleosIconButtonStyle.Ghost, size = PleosIconButtonSize.Medium)
        } else {
            appIcon?.invoke()
        }
        // 제목 + Secondary menu가 남은 폭을 모두 쓰고, actions는 오른쪽 끝에 붙습니다.
        // (제목과 빈 공간이 폭을 반씩 나누면 Portrait 창에서 제목이 일찍 잘립니다.)
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    title,
                    style = PleosTheme.typography.titleSmall,
                    color = colors.textDarkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                subtitle?.let { Text(it, style = PleosTheme.typography.bodySmall, color = colors.textDarkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            secondaryMenu?.invoke()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/**
 * List panel 행. 한 줄 제목 + 한 줄 설명, 넘치면 말줄임합니다.
 * 주행 중 긴 목록(UX_RESTRICTIONS_LIMIT_CONTENT)은 앞쪽 항목만 보여주세요.
 */
@Composable
fun PleosListItem(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val colors = PleosTheme.colors
    val d = PleosTheme.dimensions
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 공식 vehicle_settings list item: 높이 112, 모서리 24, 여백 20×16 (Connect-S)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pleosPressScale(interaction, enabled) else Modifier)
            .heightIn(min = d.listItemHeight)
            .clip(RoundedCornerShape(d.listItemRadius))
            .background(
                when {
                    selected -> colors.surfaceHigh
                    pressed -> colors.buttonBasicEnabled
                    else -> Color.Transparent
                },
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
                } else Modifier,
            )
            .padding(horizontal = d.listItemPaddingH, vertical = d.listItemPaddingV),
        horizontalArrangement = Arrangement.spacedBy(d.listItemPaddingH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = PleosTheme.typography.bodyMedium, color = if (enabled) colors.textDarkPrimary else colors.textDarkQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            description?.let { Text(it, style = PleosTheme.typography.labelMedium, color = colors.textDarkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        trailing?.invoke()
    }
}

/**
 * 카드·패널 컨테이너(surface_basic). 화면 배경(background_primary) 위에 올립니다.
 * 가이드 표대로면 Dark에서 두 토큰이 모두 basic_50(#1D1E21)이라 경계가 사라지므로
 * 1dp 외곽선(basic_alpha_dark_100)으로 구분합니다. 근거: docs/03-foundations.md
 */
@Composable
fun PleosPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = PleosTheme.colors
    val d = PleosTheme.dimensions
    // 공식 vehicle_settings list 컨테이너: 모서리 32, 여백 20×24 (Connect-S)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(d.listRadius))
            .background(colors.surfaceBasic)
            .border(1.dp, if (colors.isDark) colors.basicAlphaDark100 else colors.basicAlphaDark50, RoundedCornerShape(d.listRadius))
            .padding(horizontal = d.listPaddingV, vertical = d.listPaddingV),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        title?.let { Text(it, style = PleosTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = colors.textDarkPrimary) }
        content()
    }
}

/**
 * Quick control 카드(Theme 예시의 Doors / Windows 카드).
 * 활성 상태는 브랜드 강조색과 인디케이터 막대로 표시합니다.
 */
@Composable
fun PleosQuickControlCard(
    title: String,
    status: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
) {
    val colors = PleosTheme.colors
    val brand = PleosTheme.brand
    val interaction = remember { MutableInteractionSource() }
    val container = if (active) brand.emphasis else colors.buttonBasicEnabled
    val content = if (active) brand.onEmphasis else colors.textDarkPrimary
    Box(
        modifier = modifier
            .pleosPressScale(interaction)
            .size(width = 200.dp, height = 200.dp)
            .clip(RoundedCornerShape(PleosRadius.l))
            .background(container)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(20.dp),
    ) {
        Column(Modifier.align(Alignment.TopStart)) {
            Text(title, style = PleosTheme.typography.bodySmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = content)
            Text(status, style = PleosTheme.typography.labelMedium, color = content.copy(alpha = 0.72f))
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(width = 6.dp, height = 32.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (active) brand.indicator else colors.basicAlphaDark100),
        )
        icon?.let { Box(Modifier.align(Alignment.BottomEnd)) { it() } }
    }
}


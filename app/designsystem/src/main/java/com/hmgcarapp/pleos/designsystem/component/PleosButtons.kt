package com.hmgcarapp.pleos.designsystem.component

import com.hmgcarapp.pleos.designsystem.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hmgcarapp.pleos.designsystem.theme.PleosShadowLevel
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme
import com.hmgcarapp.pleos.designsystem.theme.pleosPressScale
import com.hmgcarapp.pleos.designsystem.theme.pleosShadow
import com.hmgcarapp.pleos.designsystem.theme.strong

/** 가이드 Basic button > Hierarchy. Primary는 옅은 틴트, Secondary는 진한 채움입니다. */
enum class PleosButtonHierarchy { Primary, Secondary }

/** 가이드 Basic button > Size. 실제 dp는 폼팩터별 공식 값(PleosTheme.dimensions)에서 가져옵니다. */
enum class PleosButtonSize { Large, Medium, Small }

/** 한 크기의 버튼 치수. Connect-S 기준 Large 100 / Medium 80 / Small 64dp */
@Immutable
data class PleosButtonSpec(
    val height: Dp,
    val horizontalPadding: Dp,
    val iconSize: Dp,
    val corner: Dp,
    val gap: Dp,
    val minWidth: Dp,
)

@Composable
@ReadOnlyComposable
fun PleosButtonSize.spec(): PleosButtonSpec {
    val d = PleosTheme.dimensions
    return when (this) {
        PleosButtonSize.Large -> PleosButtonSpec(d.buttonLargeHeight, d.buttonLargePaddingH, d.buttonLargeIcon, d.buttonLargeRadius, d.buttonLargeGap, d.buttonLargeMinWidth)
        PleosButtonSize.Medium -> PleosButtonSpec(d.buttonMediumHeight, d.buttonMediumPaddingH, d.buttonMediumIcon, d.buttonMediumRadius, d.buttonMediumGap, d.buttonMediumMinWidth)
        PleosButtonSize.Small -> PleosButtonSpec(d.buttonSmallHeight, d.buttonSmallPaddingH, d.buttonSmallIcon, d.buttonSmallRadius, d.buttonSmallGap, d.buttonSmallMinWidth)
    }
}

@Composable
private fun PleosButtonSize.textStyle(): TextStyle = when (this) {
    PleosButtonSize.Large -> PleosTheme.typography.bodyLarge.strong()
    PleosButtonSize.Medium -> PleosTheme.typography.bodyMedium.strong()
    PleosButtonSize.Small -> PleosTheme.typography.bodySmall.strong()
}

/**
 * Basic button. 상태: Enabled / Pressed / Disabled / Loading.
 * 눌림은 95% 축소로 피드백하고, 로딩 중에는 입력을 받지 않습니다.
 */
@Composable
fun PleosButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hierarchy: PleosButtonHierarchy = PleosButtonHierarchy.Primary,
    size: PleosButtonSize = PleosButtonSize.Medium,
    enabled: Boolean = true,
    loading: Boolean = false,
    prefixIcon: ImageVector? = null,
    suffixIcon: ImageVector? = null,
) {
    val colors = PleosTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val active = enabled && !loading
    val container = when {
        loading -> colors.surfaceBasic
        !enabled -> if (hierarchy == PleosButtonHierarchy.Primary) colors.buttonBasicDisabled else colors.buttonFilledDisabled
        hierarchy == PleosButtonHierarchy.Primary -> if (pressed) colors.buttonBasicPressed else colors.buttonBasicEnabled
        else -> if (pressed) colors.buttonFilledPressed else colors.buttonFilledEnabled
    }
    val content = when {
        !enabled -> colors.textDarkQuaternary
        hierarchy == PleosButtonHierarchy.Secondary -> colors.textLightPrimary
        else -> colors.textDarkPrimary
    }
    val spec = size.spec()
    val shape = RoundedCornerShape(spec.corner)
    val loadingLabel = stringResource(R.string.pleos_loading)
    val disabledBorder = PleosTheme.dimensions.buttonDisabledBorder
    Box(
        modifier = modifier
            .pleosPressScale(interaction, active)
            .defaultMinSize(minWidth = spec.minWidth)
            .height(spec.height)
            .clip(shape)
            .background(container)
            .then(if (!enabled || loading) Modifier.border(BorderStroke(disabledBorder, colors.basicAlphaDark100), shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, enabled = active, role = Role.Button, onClick = onClick)
            .semantics { if (loading) stateDescription = loadingLabel }
            .padding(horizontal = spec.horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            PleosSpinner(size = spec.iconSize)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(spec.gap), verticalAlignment = Alignment.CenterVertically) {
                prefixIcon?.let { Icon(it, contentDescription = null, tint = content, modifier = Modifier.size(spec.iconSize)) }
                Text(text, style = size.textStyle(), color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                suffixIcon?.let { Icon(it, contentDescription = null, tint = content, modifier = Modifier.size(spec.iconSize)) }
            }
        }
    }
}

/** Icon button의 배경 형태 */
enum class PleosIconButtonStyle { Filled, Ghost, Circle }

/** Icon button 크기: Medium 80dp / Small 64dp (Connect-S) */
enum class PleosIconButtonSize { Medium, Small }

/** 누구나 의미를 아는 아이콘에만 씁니다. 그 외에는 라벨이 있는 버튼을 씁니다. */
@Composable
fun PleosIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PleosIconButtonStyle = PleosIconButtonStyle.Filled,
    size: PleosIconButtonSize = PleosIconButtonSize.Medium,
    enabled: Boolean = true,
) {
    val colors = PleosTheme.colors
    val d = PleosTheme.dimensions
    val box = if (size == PleosIconButtonSize.Medium) d.iconButtonMedium else d.iconButtonSmall
    val corner = if (size == PleosIconButtonSize.Medium) d.iconButtonMediumRadius else d.iconButtonSmallRadius
    // 공식 content 영역(Medium 56 / Small 44dp)은 아이콘 여백을 포함하므로 Material 아이콘은 그 70%로 그립니다.
    val iconSize = (if (size == PleosIconButtonSize.Medium) d.iconButtonMediumContent else d.iconButtonSmallContent) * 0.7f
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val container = when {
        style == PleosIconButtonStyle.Ghost -> if (pressed) colors.buttonBasicEnabled else Color.Transparent
        !enabled -> colors.buttonBasicDisabled
        pressed -> colors.buttonBasicPressed
        else -> colors.buttonBasicEnabled
    }
    val shape = if (style == PleosIconButtonStyle.Circle) CircleShape else RoundedCornerShape(corner)
    Box(
        modifier = modifier
            .pleosPressScale(interaction, enabled)
            .size(box)
            .clip(shape)
            .background(container)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) colors.iconDarkPrimary else colors.iconDarkQuaternary,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** 부가 정보로 이동하는 링크형 버튼. 기본 뒤쪽 아이콘은 화살표입니다. */
@Composable
fun PleosTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    enabled: Boolean = true,
    prefixIcon: ImageVector? = null,
    suffixIcon: ImageVector? = Icons.AutoMirrored.Filled.KeyboardArrowRight,
) {
    val colors = PleosTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val style = if (small) PleosTheme.typography.bodySmall.strong() else PleosTheme.typography.bodyMedium.strong()
    val tint = if (enabled) colors.textDarkPrimary else colors.textDarkQuaternary
    val d = PleosTheme.dimensions
    val iconSize = if (small) d.textButtonSmallIcon else d.textButtonMediumIcon
    Row(
        modifier = modifier
            .pleosPressScale(interaction, enabled)
            .height(if (small) d.buttonSmallHeight else d.buttonMediumHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(if (pressed) colors.buttonBasicEnabled else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(if (small) d.textButtonSmallGap else d.textButtonMediumGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        prefixIcon?.let { Icon(it, null, tint = tint, modifier = Modifier.size(iconSize)) }
        Text(text, style = style, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        suffixIcon?.let { Icon(it, null, tint = tint, modifier = Modifier.size(iconSize)) }
    }
}

/**
 * On/Off 상태가 있는 버튼(예: 조명 Auto, 열선 단계). Selected는 진한 채움입니다.
 * floating=true면 그림자 Level1을 가진 떠 있는 버튼이 됩니다.
 */
@Composable
fun PleosToggleButton(
    text: String,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: PleosButtonSize = PleosButtonSize.Medium,
    enabled: Boolean = true,
    floating: Boolean = false,
    prefixIcon: ImageVector? = null,
) {
    val colors = PleosTheme.colors
    val brand = PleosTheme.brand
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val container = when {
        !enabled -> colors.buttonBasicDisabled
        selected -> brand.emphasis
        floating -> colors.surfaceBasic
        pressed -> colors.buttonBasicPressed
        else -> colors.buttonBasicEnabled
    }
    val content = when {
        !enabled -> colors.textDarkQuaternary
        selected -> brand.onEmphasis
        else -> colors.textDarkPrimary
    }
    val spec = size.spec()
    val shape = RoundedCornerShape(spec.corner)
    val stateLabel = stringResource(if (selected) R.string.pleos_on else R.string.pleos_off)
    Row(
        modifier = modifier
            .pleosPressScale(interaction, enabled)
            .then(if (floating && enabled) Modifier.pleosShadow(PleosShadowLevel.Level1, spec.corner, container) else Modifier)
            .defaultMinSize(minWidth = spec.minWidth)
            .height(spec.height)
            .clip(shape)
            .background(container)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Switch) {
                onSelectedChange(!selected)
            }
            .semantics { stateDescription = stateLabel }
            .padding(horizontal = spec.horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(spec.gap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        prefixIcon?.let { Icon(it, null, tint = content, modifier = Modifier.size(spec.iconSize)) }
        Text(text, style = size.textStyle(), color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}


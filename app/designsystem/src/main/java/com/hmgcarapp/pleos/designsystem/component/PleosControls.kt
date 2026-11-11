package com.hmgcarapp.pleos.designsystem.component

import com.hmgcarapp.pleos.designsystem.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import com.hmgcarapp.pleos.designsystem.icon.PleosIcons
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hmgcarapp.pleos.designsystem.theme.PleosRadius
import com.hmgcarapp.pleos.designsystem.theme.PleosShadowLevel
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme
import com.hmgcarapp.pleos.designsystem.theme.pleosShadow
import com.hmgcarapp.pleos.designsystem.theme.strong

/**
 * Tabs(가이드의 Segmented menu). 2개 이상 옵션 중 하나를 고릅니다.
 * 컨테이너는 controller_tab_background, 선택 항목은 떠 있는 흰 면입니다.
 */
@Composable
fun PleosSegmentedTabs(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = PleosTheme.colors
    val d = PleosTheme.dimensions
    Row(
        modifier = modifier
            .height(d.tabsHeight)
            .clip(RoundedCornerShape(d.tabsRadius))
            .background(colors.controllerTabBackground)
            .padding(d.tabsPadding)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(d.tabsPadding),
    ) {
        items.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (selected) Modifier.pleosShadow(PleosShadowLevel.Level1, d.tabsButtonRadius, colors.surfaceBasic) else Modifier)
                    .clip(RoundedCornerShape(d.tabsButtonRadius))
                    .background(if (selected) colors.surfaceBasic else Color.Transparent)
                    .selectable(selected, enabled = enabled, role = Role.Tab) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = if (selected) PleosTheme.typography.bodySmall.strong() else PleosTheme.typography.bodySmall,
                    color = if (selected) colors.textDarkPrimary else colors.textDarkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Stepper. 최솟값/최댓값 사이를 한 단계씩 바꿉니다(예: 공조 온도, 풍량).
 * 값 표시는 클릭 없이 한눈에 읽히도록 크게 둡니다.
 */
@Composable
fun PleosStepper(
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    decreaseEnabled: Boolean = true,
    increaseEnabled: Boolean = true,
    decreaseDescription: String = stringResource(R.string.pleos_decrease),
    increaseDescription: String = stringResource(R.string.pleos_increase),
) {
    val colors = PleosTheme.colors
    val d = PleosTheme.dimensions
    // 공식: 420×100, 모서리 24, 테두리 2 (Connect-S). 흰 패널 위에서도 경계가 보이도록 테두리를 그립니다.
    Row(
        modifier = modifier
            // 공식 너비 420dp(Connect-S). 호출부 modifier의 너비가 있으면 그쪽이 우선합니다.
            // widthIn(min)만 두면 같은 Row의 weight 형제 요소 폭을 모두 빼앗습니다.
            .width(d.stepperWidth)
            .height(d.stepperHeight)
            .clip(RoundedCornerShape(d.stepperRadius))
            .background(colors.controllerStepperBackground)
            .border(d.stepperBorder, colors.basicAlphaDark100, RoundedCornerShape(d.stepperRadius))
            .padding(horizontal = d.stepperPaddingH / 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PleosIconButton(PleosIcons.Minus, decreaseDescription, onDecrease, style = PleosIconButtonStyle.Ghost, size = PleosIconButtonSize.Small, enabled = decreaseEnabled)
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = PleosTheme.typography.titleSmall,
            color = colors.textDarkPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        PleosIconButton(Icons.Filled.Add, increaseDescription, onIncrease, style = PleosIconButtonStyle.Ghost, size = PleosIconButtonSize.Small, enabled = increaseEnabled)
    }
}

/**
 * Continuous slider. 실제 범위를 아는 값에만 씁니다(범위를 추측해 슬라이더를 만들지 않습니다).
 * prefix/suffix 아이콘은 -, + 단계 버튼으로 동작합니다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PleosSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    prefixIcon: ImageVector? = PleosIcons.Minus,
    suffixIcon: ImageVector? = Icons.Filled.Add,
    stepAmount: Float = (valueRange.endInclusive - valueRange.start) / 10f,
) {
    val colors = PleosTheme.colors
    val d = PleosTheme.dimensions
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = modifier.height(d.sliderButton),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        prefixIcon?.let {
            PleosIconButton(it, stringResource(R.string.pleos_decrease), { onValueChange((value - stepAmount).coerceIn(valueRange)) }, style = PleosIconButtonStyle.Ghost, size = PleosIconButtonSize.Small, enabled = enabled && value > valueRange.start)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f).height(d.sliderHeight),
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            interactionSource = interaction,
            // 공식: 손잡이 48dp(눌림 56dp), 트랙 8dp (Connect-S)
            thumb = {
                val knob = if (pressed) d.sliderKnobPressed else d.sliderKnob
                Box(
                    Modifier
                        .size(knob)
                        .pleosShadow(PleosShadowLevel.Level1, knob / 2, if (enabled) colors.controllerSliderKnob else colors.basicAlphaDark200)
                        .clip(CircleShape)
                        .background(if (enabled) colors.controllerSliderKnob else colors.basicAlphaDark200)
                        .border(2.dp, if (enabled) colors.buttonFilledEnabled else colors.basicAlphaDark100, CircleShape),
                )
            },
            track = { state ->
                val span = state.valueRange.endInclusive - state.valueRange.start
                val fraction = if (span == 0f) 0f else ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(d.sliderTrackHeight)
                        .clip(RoundedCornerShape(d.sliderTrackHeight / 2))
                        .background(if (pressed) colors.controllerSliderPressed else colors.controllerSliderNormal),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(if (enabled) colors.buttonFilledEnabled else colors.basicAlphaDark100),
                    )
                }
            },
        )
        suffixIcon?.let {
            PleosIconButton(it, stringResource(R.string.pleos_increase), { onValueChange((value + stepAmount).coerceIn(valueRange)) }, style = PleosIconButtonStyle.Ghost, size = PleosIconButtonSize.Small, enabled = enabled && value < valueRange.endInclusive)
        }
    }
}





package com.hmgcarapp.pleos.designsystem.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hmgcarapp.pleos.designsystem.theme.PleosMotion
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme

/**
 * Switch. 트랙은 둥근 사각형이며 ON 색은 informative_active입니다.
 * 라벨이 있으면 행 전체가 터치 영역입니다.
 */
@Composable
fun PleosSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
) {
    val colors = PleosTheme.colors
    val d = PleosTheme.dimensions
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 공식 Medium: 너비 80, 안쪽 여백 8, 손잡이 32, 모서리 16 / 손잡이 모서리 8 (Connect-S)
    val travel = d.switchWidth - d.switchKnob - d.switchPadding * 2
    val knobOffset by animateDpAsState(
        targetValue = if (checked) travel else 0.dp,
        animationSpec = tween(PleosMotion.DurationShort2, easing = PleosMotion.Emphasized),
        label = "pleos-switch-knob",
    )
    val track = when {
        !enabled -> colors.switchDisabled
        checked -> colors.switchOn
        else -> colors.switchOff
    }
    val knob = when {
        !enabled -> colors.switchKnobDisabled
        checked -> colors.switchKnobOn
        else -> colors.switchKnobOff
    }
    Row(
        modifier = modifier
            .heightIn(min = d.selectionContainerLarge)
            .toggleable(checked, interactionSource = interaction, indication = null, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        label?.let {
            Text(it, style = PleosTheme.typography.bodyMedium, color = if (enabled) colors.textDarkPrimary else colors.textDarkQuaternary, modifier = Modifier.weight(1f))
        }
        Box(
            modifier = Modifier
                .scale(if (pressed) PleosMotion.PressedScale else 1f)
                .width(d.switchWidth)
                .height(d.switchKnob + d.switchPadding * 2)
                .clip(RoundedCornerShape(d.switchRadius))
                .background(track)
                .padding(d.switchPadding),
        ) {
            Box(
                Modifier
                    .offset(x = knobOffset)
                    .size(d.switchKnob)
                    .clip(RoundedCornerShape(d.switchKnobRadius))
                    .background(knob),
            )
        }
    }
}

/** 다중 선택 */
@Composable
fun PleosCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
) {
    val colors = PleosTheme.colors
    Row(
        modifier = modifier
            .heightIn(min = PleosTheme.dimensions.selectionContainerLarge)
            .clip(RoundedCornerShape(PleosTheme.dimensions.checkboxRadius))
            .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.scale(1.5f),
            colors = CheckboxDefaults.colors(
                checkedColor = colors.informativeActive,
                uncheckedColor = colors.basicAlphaDark300,
                checkmarkColor = colors.basicStaticLight100,
                disabledCheckedColor = colors.basicAlphaDark100,
                disabledUncheckedColor = colors.basicAlphaDark100,
            ),
        )
        label?.let { Text(it, style = PleosTheme.typography.bodyMedium, color = if (enabled) colors.textDarkPrimary else colors.textDarkQuaternary) }
    }
}

/** 단일 선택(같은 그룹 안에서 하나만) */
@Composable
fun PleosRadio(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
) {
    val colors = PleosTheme.colors
    Row(
        modifier = modifier
            .heightIn(min = PleosTheme.dimensions.selectionContainerLarge)
            .clip(RoundedCornerShape(PleosTheme.dimensions.checkboxRadius))
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            modifier = Modifier.scale(1.5f),
            colors = RadioButtonDefaults.colors(
                selectedColor = colors.informativeActive,
                unselectedColor = colors.basicAlphaDark300,
                disabledSelectedColor = colors.basicAlphaDark100,
                disabledUnselectedColor = colors.basicAlphaDark100,
            ),
        )
        label?.let { Text(it, style = PleosTheme.typography.bodyMedium, color = if (enabled) colors.textDarkPrimary else colors.textDarkQuaternary) }
    }
}



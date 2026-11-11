package com.hmgcarapp.pleos.designsystem.component

import com.hmgcarapp.pleos.designsystem.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hmgcarapp.pleos.designsystem.restriction.LocalPleosUxRestrictions
import com.hmgcarapp.pleos.designsystem.theme.PleosRadius
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme
import com.hmgcarapp.pleos.designsystem.theme.pleosPressScale
import com.hmgcarapp.pleos.designsystem.theme.strong

/** 필드·드롭다운 크기. 공식 Medium 100dp / Small 80dp (Connect-S). */
enum class PleosFieldSize { Medium, Small }

/**
 * Text field. 상태: Enabled / Focused / Entered / Error / Read only.
 * 주행 중 키보드가 제한(UX_RESTRICTIONS_NO_KEYBOARD)되면 자동으로 읽기 전용이 되고
 * restrictedHint를 보여줍니다. 음성·프리셋·최근 항목 입력을 함께 제공하세요.
 */
@Composable
fun PleosTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    size: PleosFieldSize = PleosFieldSize.Medium,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    errorText: String? = null,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    suffix: (@Composable () -> Unit)? = null,
    restrictedHint: String = stringResource(R.string.pleos_keyboard_restricted),
) {
    val colors = PleosTheme.colors
    val typography = PleosTheme.typography
    val keyboardBlocked = LocalPleosUxRestrictions.current.noKeyboard
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val editable = enabled && !readOnly && !keyboardBlocked
    val d = PleosTheme.dimensions
    val medium = size == PleosFieldSize.Medium
    val height = if (medium) d.fieldMediumHeight else d.fieldSmallHeight
    val corner = RoundedCornerShape(d.fieldMediumRadius)
    val border = if (medium) d.fieldMediumBorder else d.fieldSmallBorder
    val borderColor = when {
        errorText != null -> colors.regulationRed
        focused && editable -> colors.fieldFocused
        else -> colors.basicAlphaDark100
    }
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .height(height)
                .clip(corner)
                .background(if (readOnly || keyboardBlocked) colors.surfaceHigh else colors.fieldBackground)
                .border(if (focused || errorText != null) border else 1.dp, borderColor, corner)
                .padding(horizontal = if (medium) d.fieldMediumPaddingH else d.fieldSmallPaddingH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled && !keyboardBlocked,
                readOnly = readOnly || keyboardBlocked,
                singleLine = true,
                textStyle = typography.bodyMedium.copy(color = if (enabled) colors.textDarkPrimary else colors.textDarkQuaternary),
                cursorBrush = SolidColor(colors.informativePositive),
                interactionSource = interaction,
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                if (keyboardBlocked) restrictedHint else placeholder,
                                style = typography.bodyMedium,
                                color = colors.textDarkTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            if (focused && editable && value.isNotEmpty()) {
                PleosIconButton(Icons.Filled.Close, stringResource(R.string.pleos_clear), { onValueChange("") }, style = PleosIconButtonStyle.Ghost, size = PleosIconButtonSize.Small)
            }
            suffix?.let {
                Spacer(Modifier.width(12.dp))
                it()
            }
        }
        errorText?.let {
            Text(it, style = typography.labelMedium, color = colors.regulationRed, modifier = Modifier.padding(start = 8.dp, top = d.fieldHelpGap))
        }
    }
}

/** 숫자 입력 필드 */
@Composable
fun PleosNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    size: PleosFieldSize = PleosFieldSize.Medium,
    errorText: String? = null,
    enabled: Boolean = true,
) = PleosTextField(
    value = value,
    onValueChange = { next -> if (next.all { it.isDigit() || it == '.' || it == '-' }) onValueChange(next) },
    modifier = modifier,
    placeholder = placeholder,
    size = size,
    errorText = errorText,
    enabled = enabled,
    keyboardType = KeyboardType.Number,
)

/** Dropdown 항목 */
data class PleosDropdownOption(val label: String, val icon: ImageVector? = null)

/**
 * Dropdown. 메뉴 이동이나 속성 변경에 씁니다.
 * 가이드: 드롭다운·다단 메뉴를 남용하지 말고 자주 쓰는 기능은 첫 화면에 둡니다.
 */
@Composable
fun PleosDropdown(
    options: List<PleosDropdownOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    size: PleosFieldSize = PleosFieldSize.Medium,
    enabled: Boolean = true,
    prefixIcon: ImageVector? = null,
) {
    val colors = PleosTheme.colors
    val typography = PleosTheme.typography
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val d = PleosTheme.dimensions
    val medium = size == PleosFieldSize.Medium
    val shape = RoundedCornerShape(if (medium) d.dropdownMediumRadius else d.dropdownSmallRadius)
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .pleosPressScale(interaction, enabled)
                .height(if (medium) d.dropdownMediumHeight else d.dropdownSmallHeight)
                .clip(shape)
                .background(if (pressed || expanded) colors.dropdownPressed else colors.dropdownNormal)
                .border(d.dropdownBorder, colors.basicAlphaDark100, shape)
                .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button) { expanded = true }
                .padding(horizontal = d.dropdownPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = if (enabled) colors.textDarkPrimary else colors.textDarkQuaternary
            prefixIcon?.let { Icon(it, null, tint = tint, modifier = Modifier.size(32.dp)) }
            Text(
                options.getOrNull(selectedIndex)?.label.orEmpty(),
                style = typography.bodyMedium.strong(),
                color = tint,
                // weight()를 쓰면 가로 스크롤 안에서 폭이 0으로 접히므로 최대 폭만 제한합니다.
                modifier = Modifier.widthIn(max = 480.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = tint, modifier = Modifier.size(d.dropdownSuffixIcon * 0.75f))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(colors.backgroundPopup),
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option.label, style = typography.bodyMedium, color = colors.textDarkPrimary) },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    },
                    leadingIcon = option.icon?.let { icon -> { Icon(icon, null, tint = colors.iconDarkPrimary) } },
                    trailingIcon = if (index == selectedIndex) {
                        { Icon(Icons.Filled.Check, null, tint = colors.informativeActive) }
                    } else null,
                    modifier = Modifier.height(d.dropdownSmallHeight),
                )
            }
        }
    }
}



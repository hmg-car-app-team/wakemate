package com.hmgcarapp.pleos.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.hmgcarapp.pleos.designsystem.R

/**
 * Pleos Connect 시스템 UI 폰트 Asta Sans(OFL-1.1, 가변 폰트).
 * 가변 축(wght)을 FontWeight 값으로 맞춰 씁니다. variationSettings를 명시하지 않으면
 * 가변축을 무시하는 예전 오버로드가 선택되어 모든 굵기가 기본 인스턴스로 그려집니다.
 */
@OptIn(ExperimentalTextApi::class)
private fun astaSans(weight: FontWeight) = Font(
    resId = R.font.asta_sans,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val AstaSans: FontFamily = FontFamily(
    astaSans(FontWeight.Normal),
    astaSans(FontWeight.Medium),
    astaSans(FontWeight.SemiBold),
    astaSans(FontWeight.Bold),
    astaSans(FontWeight.ExtraBold),
)

internal fun pleosTextStyle(size: Int, lineHeight: Int, weight: FontWeight) = TextStyle(
    fontFamily = AstaSans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
)

/**
 * 가이드의 Font Hierarchy(Headline / Title / Body / Label)를 그대로 옮긴 값입니다.
 * 기본값은 Connect-S 기준이고, 다른 폼팩터 값은 pleosTypographyFor()가 공식 typo 변수로 만듭니다.
 * Body의 "Strong" 굵기는 [strong]으로 만듭니다.
 */
@Immutable
data class PleosTypography(
    val headlineLarge: TextStyle = pleosTextStyle(60, 80, FontWeight.Bold),
    val headlineMedium: TextStyle = pleosTextStyle(56, 72, FontWeight.Bold),
    val headlineSmall: TextStyle = pleosTextStyle(48, 64, FontWeight.Bold),
    val titleLarge: TextStyle = pleosTextStyle(40, 52, FontWeight.Bold),
    val titleMedium: TextStyle = pleosTextStyle(36, 44, FontWeight.Bold),
    val titleSmall: TextStyle = pleosTextStyle(32, 40, FontWeight.Bold),
    val bodyLarge: TextStyle = pleosTextStyle(30, 38, FontWeight.Normal),
    val bodyMedium: TextStyle = pleosTextStyle(28, 36, FontWeight.Normal),
    val bodySmall: TextStyle = pleosTextStyle(26, 34, FontWeight.Normal),
    val labelMedium: TextStyle = pleosTextStyle(24, 28, FontWeight.Normal),
    val labelSmall: TextStyle = pleosTextStyle(20, 24, FontWeight.Normal),
)

/** Body Strong 등 굵은 변형 */
fun TextStyle.strong(): TextStyle = copy(fontWeight = FontWeight.Bold)

/** Headline·Title의 Extra Bold 변형 */
fun TextStyle.extraBold(): TextStyle = copy(fontWeight = FontWeight.ExtraBold)

/** Title의 Regular 변형 */
fun TextStyle.regular(): TextStyle = copy(fontWeight = FontWeight.Normal)

/** Material 컴포넌트가 Pleos 크기를 쓰도록 매핑합니다. */
internal fun PleosTypography.toMaterial(): Typography = Typography(
    displayLarge = headlineLarge,
    displayMedium = headlineMedium,
    displaySmall = headlineSmall,
    headlineLarge = titleLarge,
    headlineMedium = titleMedium,
    headlineSmall = titleSmall,
    titleLarge = bodyLarge.strong(),
    titleMedium = bodyMedium.strong(),
    titleSmall = bodySmall.strong(),
    bodyLarge = bodyLarge,
    bodyMedium = bodyMedium,
    bodySmall = bodySmall,
    labelLarge = bodySmall.strong(),
    labelMedium = labelMedium,
    labelSmall = labelSmall,
)

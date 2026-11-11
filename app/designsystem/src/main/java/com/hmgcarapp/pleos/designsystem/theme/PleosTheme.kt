package com.hmgcarapp.pleos.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 브랜드 테마. 가이드는 42dot·Kia·Genesis 테마를 보여주지만 색 수치는 공개하지 않습니다.
 * 아래 값은 Theme 예시 도면의 fill 값에서 뽑은 참고값입니다.
 */
enum class PleosBrand { Dot42, Kia, Genesis }

@Immutable
data class PleosBrandColors(
    /** 선택·강조 카드 배경 */
    val emphasis: Color,
    val onEmphasis: Color,
    /** 상태 인디케이터 막대·강조 아이콘 */
    val indicator: Color,
)

private fun brandColors(brand: PleosBrand, colors: PleosColors): PleosBrandColors = when (brand) {
    PleosBrand.Dot42 -> PleosBrandColors(colors.buttonFilledEnabled, colors.textLightPrimary, Color(0xFF00BA13))
    PleosBrand.Kia -> PleosBrandColors(Color(0xFF28363F), Color.White, Color(0xFFF6B200))
    PleosBrand.Genesis -> PleosBrandColors(Color(0xFFDE5824), Color.White, Color(0xFFFFA467))
}

val LocalPleosColors = staticCompositionLocalOf { PleosLightColors }
val LocalPleosTypography = staticCompositionLocalOf { PleosTypography() }
val LocalPleosBrand = staticCompositionLocalOf { brandColors(PleosBrand.Dot42, PleosLightColors) }
val LocalPleosDimensions = staticCompositionLocalOf { PleosDimensionsConnectS }
val LocalPleosFormFactor = staticCompositionLocalOf { PleosFormFactor.ConnectS }

object PleosTheme {
    val colors: PleosColors
        @Composable @ReadOnlyComposable get() = LocalPleosColors.current
    val typography: PleosTypography
        @Composable @ReadOnlyComposable get() = LocalPleosTypography.current
    val brand: PleosBrandColors
        @Composable @ReadOnlyComposable get() = LocalPleosBrand.current

    /** 현재 폼팩터의 공식 컴포넌트 크기(버튼 높이, 모서리, 여백 등) */
    val dimensions: PleosDimensions
        @Composable @ReadOnlyComposable get() = LocalPleosDimensions.current
    val formFactor: PleosFormFactor
        @Composable @ReadOnlyComposable get() = LocalPleosFormFactor.current

    /** 현재 폼팩터의 화면 영역 크기(Status bar, Driving view, 앱 창, GNB) */
    val window: PleosWindowDimensions
        @Composable @ReadOnlyComposable get() = pleosWindowFor(LocalPleosFormFactor.current)
}

/**
 * 앱 최상단에 한 번 감쌉니다. Material3 컴포넌트도 Pleos 색·글꼴을 쓰도록 매핑합니다.
 * 가이드 원칙: 주간/야간 조명에 맞춰 Light/Dark를 자동 전환합니다(기본값: 시스템 설정).
 * formFactor는 디스플레이 해상도·밀도로 자동 감지하며, 시연용으로 직접 지정할 수 있습니다.
 */
@Composable
fun PleosTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    brand: PleosBrand = PleosBrand.Dot42,
    formFactor: PleosFormFactor = rememberPleosFormFactor(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) PleosDarkColors else PleosLightColors
    val typography = remember(formFactor) { pleosTypographyFor(formFactor) }
    val dimensions = pleosDimensionsFor(formFactor)
    val brandColors = remember(brand, colors) { brandColors(brand, colors) }
    MaterialTheme(
        colorScheme = colors.toMaterialColorScheme(),
        typography = typography.toMaterial(),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(PleosRadius.s),
            small = RoundedCornerShape(PleosRadius.s),
            medium = RoundedCornerShape(PleosRadius.m),
            large = RoundedCornerShape(PleosRadius.l),
            extraLarge = RoundedCornerShape(PleosRadius.xl),
        ),
    ) {
        CompositionLocalProvider(
            LocalPleosColors provides colors,
            LocalPleosTypography provides typography,
            LocalPleosBrand provides brandColors,
            LocalPleosDimensions provides dimensions,
            LocalPleosFormFactor provides formFactor,
            LocalContentColor provides colors.textDarkPrimary,
            content = content,
        )
    }
}

internal fun PleosColors.toMaterialColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = buttonFilledEnabled,
        onPrimary = textLightPrimary,
        primaryContainer = surfaceHigh,
        onPrimaryContainer = textDarkPrimary,
        secondary = informativePositive,
        onSecondary = basicStaticLight100,
        tertiary = informativeActive,
        onTertiary = basicStaticLight100,
        error = informativeNegative,
        onError = basicStaticLight100,
        background = backgroundPrimary,
        onBackground = textDarkPrimary,
        surface = surfaceBasic,
        onSurface = textDarkPrimary,
        surfaceVariant = surfaceHigh,
        onSurfaceVariant = textDarkSecondary,
        surfaceContainerLowest = surfaceBasic,
        surfaceContainerLow = surfaceLow,
        surfaceContainer = surfaceBasic,
        surfaceContainerHigh = surfaceHigh,
        surfaceContainerHighest = surfaceAccent,
        outline = basicAlphaDark200,
        outlineVariant = basicAlphaDark100,
        scrim = overlayDefault,
    )
}

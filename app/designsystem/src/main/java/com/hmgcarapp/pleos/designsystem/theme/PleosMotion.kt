package com.hmgcarapp.pleos.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/** 가이드 Interaction > Tension 값 */
object PleosMotion {
    const val DurationShort1 = 100
    const val DurationShort2 = 150
    const val DurationMedium1 = 250
    const val DurationMedium2 = 300
    const val DurationLong1 = 400
    const val DurationLong2 = 450

    /** cubic-bezier(0.16, 1, 0.3, 1) */
    val Emphasized = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** 눌림 시 중심 기준 100% → 95% */
    const val PressedScale = 0.95f

    /** 입력 후 시스템 응답 상한(ms). 넘으면 로딩 상태를 보여야 합니다. */
    const val ResponseBudgetMs = 250L
}

/** Enabled 100% → Pressed 95% → Release 100% */
fun Modifier.pleosPressScale(interactionSource: InteractionSource, enabled: Boolean = true): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) PleosMotion.PressedScale else 1f,
        animationSpec = tween(PleosMotion.DurationShort2, easing = PleosMotion.Emphasized),
        label = "pleos-press-scale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

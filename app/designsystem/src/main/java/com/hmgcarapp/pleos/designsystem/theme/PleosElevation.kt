package com.hmgcarapp.pleos.designsystem.theme

import android.graphics.Paint
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 가이드 Shadow 한 겹(Figma 값 그대로: X, Y, Blur, Spread, Color) */
data class PleosShadowLayer(val x: Dp, val y: Dp, val blur: Dp, val color: Color)

/** Level 1~3. 높을수록 위에 떠 있는 콘텐츠입니다. */
enum class PleosShadowLevel(val layers: List<PleosShadowLayer>) {
    Level1(listOf(PleosShadowLayer(0.dp, 6.dp, 24.dp, Color.Black.copy(alpha = 0.08f)))),
    Level2(
        listOf(
            PleosShadowLayer(0.dp, 8.dp, 32.dp, Color.Black.copy(alpha = 0.16f)),
            PleosShadowLayer(0.dp, 2.dp, 2.dp, Color.Black.copy(alpha = 0.08f)),
        ),
    ),
    Level3(
        listOf(
            PleosShadowLayer(0.dp, 20.dp, 40.dp, Color.Black.copy(alpha = 0.24f)),
            PleosShadowLayer(0.dp, 2.dp, 4.dp, Color.Black.copy(alpha = 0.20f)),
        ),
    ),
}

/**
 * 둥근 사각형 그림자와 배경을 함께 그립니다.
 * Figma Blur는 가우시안 지름에 가까워 Android shadowLayer 반경으로 절반을 씁니다.
 */
fun Modifier.pleosShadow(
    level: PleosShadowLevel,
    cornerRadius: Dp,
    fill: Color,
): Modifier = drawBehind {
    val radius = cornerRadius.toPx()
    drawIntoCanvas { canvas ->
        level.layers.forEach { layer ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = fill.toArgb()
                setShadowLayer(
                    (layer.blur.toPx() / 2f).coerceAtLeast(0.5f),
                    layer.x.toPx(),
                    layer.y.toPx(),
                    layer.color.toArgb(),
                )
            }
            canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, paint)
        }
    }
}

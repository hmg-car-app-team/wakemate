package com.hmgcarapp.pleos.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * material-icons-core에 없는 기본 아이콘.
 * 가이드: 48dp 그리드, Filled/Outlined 두 타입, 한 벌을 만들어 크기만 조정.
 */
object PleosIcons {
    val Minus: ImageVector by lazy {
        ImageVector.Builder("Minus", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(5f, 11f)
                horizontalLineTo(19f)
                verticalLineTo(13f)
                horizontalLineTo(5f)
                close()
            }
        }.build()
    }
}

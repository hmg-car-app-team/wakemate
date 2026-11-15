package com.hmgcarapp.pleos.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * material-icons-core에 없는 기본 아이콘.
 * 가이드: 48dp 그리드, Filled/Outlined 두 타입, 한 벌을 만들어 크기만 조정.
 * 차량 자극 아이콘(공조·시트 통풍·핸들 열선·무드등)은 24 그리드에 면(fill)과 2px 선(stroke)으로 그린다. 색은 사용하는 곳에서 tint 로 준다.
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

    /** 공조 환기: 바람 줄기 세 개. */
    val Air: ImageVector by lazy {
        icon(
            "Air",
            fills = listOf(
                "M14.5,17c0,1.65 -1.35,3 -3,3s-3,-1.35 -3,-3h2c0,0.55 0.45,1 1,1s1,-0.45 1,-1s-0.45,-1 -1,-1H2v-2h9.5C13.15,14 14.5,15.35 14.5,17z" +
                    "M19,6.5C19,4.57 17.43,3 15.5,3S12,4.57 12,6.5h2C14,5.67 14.67,5 15.5,5S17,5.67 17,6.5S16.33,8 15.5,8H2v2h13.5C17.43,10 19,8.43 19,6.5z" +
                    "M18.5,11H2v2h16.5c0.83,0 1.5,0.67 1.5,1.5S19.33,16 18.5,16v2c1.93,0 3.5,-1.57 3.5,-3.5S20.43,11 18.5,11z",
            ),
        )
    }

    /** 시트 통풍: 옆에서 본 시트와 등받이에서 나오는 바람. */
    val SeatVent: ImageVector by lazy {
        icon(
            "SeatVent",
            fills = listOf(
                "M6.4,2h1.8a1.4,1.4 0 0 1 1.4,1.4v0.6a1.4,1.4 0 0 1 -1.4,1.4h-1.8a1.4,1.4 0 0 1 -1.4,-1.4v-0.6a1.4,1.4 0 0 1 1.4,-1.4z",
                "M5.9,6.6h2.4c0.62,0 1.15,0.43 1.28,1.03l1.62,7.37H7.0c-0.62,0 -1.15,-0.43 -1.28,-1.03L4.62,8.19C4.45,7.37 5.07,6.6 5.9,6.6z",
                "M8,15.8h8a1.4,1.4 0 0 1 0,2.8h-8a1.4,1.4 0 0 1 0,-2.8z",
                "M10.2,19.4h5.6v2h-5.6z",
            ),
            strokes = listOf(
                "M13.2,5.2c1,-1 2,-1 3,0s2,1 3,0",
                "M13.8,8.8c1,-1 2,-1 3,0s2,1 3,0",
                "M14.4,12.4c1,-1 2,-1 3,0s2,1 3,0",
            ),
        )
    }

    /** 핸들 열선: 스티어링휠(테·허브·살 세 개). */
    val SteeringWheel: ImageVector by lazy {
        icon(
            "SteeringWheel",
            evenOdd = listOf("M2.5,12a9.5,9.5 0 1,0 19,0a9.5,9.5 0 1,0 -19,0zM4.7,12a7.3,7.3 0 1,0 14.6,0a7.3,7.3 0 1,0 -14.6,0z"),
            fills = listOf(
                "M9,12a3,3 0 1,0 6,0a3,3 0 1,0 -6,0z",
                "M4.4,10.9h15.2v2H4.4z",
                "M11,12h2v7.6h-2z",
            ),
        )
    }

    /** 무드등: 빛을 내는 실내 조명 띠. */
    val AmbientLight: ImageVector by lazy {
        icon(
            "AmbientLight",
            fills = listOf("M4,15.5h16a1.5,1.5 0 0 1 0,3h-16a1.5,1.5 0 0 1 0,-3z"),
            strokes = listOf("M12,5.6v5.2", "M5.6,8.2l2.6,2.6", "M18.4,8.2l-2.6,2.6"),
        )
    }

    /** 경고음: 스피커와 소리 물결. */
    val Sound: ImageVector by lazy {
        icon(
            "Sound",
            fills = listOf(
                "M3,9v6h4l5,5V4L7,9H3z",
                "M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02z",
                "M14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z",
            ),
        )
    }

    private fun icon(name: String, fills: List<String> = emptyList(), evenOdd: List<String> = emptyList(), strokes: List<String> = emptyList()): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            evenOdd.forEach { addPath(addPathNodes(it), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.Black)) }
            fills.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.Black)) }
            strokes.forEach {
                addPath(addPathNodes(it), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round)
            }
        }.build()
}

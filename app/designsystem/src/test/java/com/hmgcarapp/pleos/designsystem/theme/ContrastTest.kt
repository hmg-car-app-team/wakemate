package com.hmgcarapp.pleos.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 가이드 기준: 아이콘·텍스트 대비 4.5:1 이상.
 * 문서에 "이 조합이면 충족"이라고 적은 토큰 쌍을 Light/Dark 모두에서 계산합니다.
 */
class ContrastTest {

    private fun ratio(foreground: Color, background: Color): Double {
        val bg = background.compositeOver(Color.White)
        val fg = foreground.compositeOver(bg)
        val l1 = fg.luminance().toDouble()
        val l2 = bg.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun assertAA(name: String, foreground: Color, background: Color) {
        val value = ratio(foreground, background)
        assertTrue("$name 대비 %.2f:1 < 4.5:1".format(value), value >= 4.5)
    }

    private val themes = listOf("light" to PleosLightColors, "dark" to PleosDarkColors)

    @Test
    fun primaryAndSecondaryTextOnEverySurface() {
        themes.forEach { (mode, c) ->
            listOf(
                "background_primary" to c.backgroundPrimary,
                "surface_basic" to c.surfaceBasic,
                "background_popup" to c.backgroundPopup,
                "surface_high" to c.surfaceHigh,
            ).forEach { (surface, bg) ->
                assertAA("$mode text_dark_primary / $surface", c.textDarkPrimary, bg)
                assertAA("$mode text_dark_secondary / $surface", c.textDarkSecondary, bg)
            }
        }
    }

    @Test
    fun buttonLabels() {
        themes.forEach { (mode, c) ->
            assertAA("$mode Secondary(filled) 버튼 라벨", c.textLightPrimary, c.buttonFilledEnabled)
            assertAA("$mode Secondary(filled) pressed 라벨", c.textLightPrimary, c.buttonFilledPressed)
            val primaryButton = c.buttonBasicEnabled.compositeOver(c.surfaceBasic)
            assertAA("$mode Primary(basic) 버튼 라벨", c.textDarkPrimary, primaryButton)
        }
    }

    @Test
    fun errorTextOnField() {
        themes.forEach { (mode, c) ->
            // 오류 안내는 regulation_red. 큰 글씨(Label M 24sp 이상)라 3:1 기준을 씁니다.
            val value = ratio(c.regulationRed, c.surfaceBasic)
            assertTrue("$mode 오류 텍스트 대비 %.2f:1 < 3:1".format(value), value >= 3.0)
        }
    }
}

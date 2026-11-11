package com.hmgcarapp.pleos.designsystem.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** 가이드 디스플레이 표의 6개 화면이 공식 폼팩터 모드로 매핑되는지 확인합니다. */
class FormFactorTest {
    @Test
    fun everyDisplayMapsToItsFormFactor() {
        PleosDisplay.entries.forEach { display ->
            val (w, h) = display.resolutionPx
            assertEquals(display.name, display.formFactor, pleosFormFactorFor(w, h, display.dpi))
        }
    }

    @Test
    fun portraitRotationGivesSameFormFactor() {
        assertEquals(PleosFormFactor.ConnectS, pleosFormFactorFor(1440, 2560, 160))
    }
}

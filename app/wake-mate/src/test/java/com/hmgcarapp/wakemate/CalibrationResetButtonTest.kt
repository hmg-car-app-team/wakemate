package com.hmgcarapp.wakemate

import com.hmgcarapp.wakemate.domain.Calibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 설정의 '개인 보정 초기화' 버튼 1개. */
class CalibrationResetButtonTest {
    private fun src(rel: String) = File("src/main/java/com/hmgcarapp/wakemate/$rel").readText()

    @Test fun resetButtonCallsResetCalibration_parkedOnly() {
        val ui = src("ui/Screens.kt")
        assertEquals(1, Regex("""c\.resetCalibration\(\)""").findAll(ui).count())
        assertTrue(ui.contains("개인 보정 초기화") && ui.contains("btn_reset_calibration"))
        assertTrue(Regex("""fun resetCalibration\(\)\s*=\s*parkedOnly""").containsMatchIn(src("app/AppController.kt")))
    }

    @Test fun maxedCalibrationReturnsToDefault() {
        var c = Calibration(); repeat(7) { c = c.afterNotSleepy() }
        assertEquals(Calibration.MAX_OFFSET, c.offset)
        assertEquals(Calibration(), Calibration())
    }
}

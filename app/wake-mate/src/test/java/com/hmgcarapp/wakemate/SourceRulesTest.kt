package com.hmgcarapp.wakemate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 제작 규칙(디자인시스템·개인정보·모듈 의존·영역 하드코딩)이 소스에서 깨지지 않게 막는 정적 점검. */
class SourceRulesTest {
    private val root = File("src/main/java/com/hmgcarapp/wakemate")
    private fun kt(sub: String? = null): List<File> = (if (sub == null) root else File(root, sub)).walkTopDown().filter { it.extension == "kt" }.toList()
    private fun code(f: File) = f.readLines().filter { !it.trimStart().startsWith("//") && !it.trimStart().startsWith("*") && !it.trimStart().startsWith("/*") }.joinToString("\n")

    @Test fun sourcesExist() { assertTrue(kt().size >= 20) }

    @Test fun ui_usesOnlyPleosComponentsAndTokens() {
        kt("ui").forEach { f ->
            val c = code(f)
            val material = Regex("import androidx\\.compose\\.material3\\.(\\w+)").findAll(c).map { it.groupValues[1] }.toSet()
            assertEquals("${f.name}: Material 위젯 금지(Text 만 허용)", emptySet<String>(), material - "Text")
            assertFalse("${f.name}: 하드코딩 색", Regex("\\bColor\\(|0x[0-9A-Fa-f]{6,8}|Color\\.(Red|Blue|Green|White|Black|Gray)").containsMatchIn(c))
            assertFalse("${f.name}: sp/fontSize 직접 지정", Regex("\\d\\.sp\\b|fontSize\\s*=").containsMatchIn(c))
            assertFalse("${f.name}: dp 직접 지정(토큰 사용)", Regex("\\d\\.dp\\b").containsMatchIn(c))
            assertFalse("${f.name}: weight 와 고정폭 컨트롤 혼용 방지(ui 에서 weight 금지)", c.contains(".weight("))
        }
    }

    @Test fun noDependencyOnOtherAppModules() {
        kt().forEach { f ->
            Regex("import (com\\.hmgcarapp\\.[a-z]+)").findAll(code(f)).map { it.groupValues[1] }.forEach {
                assertTrue("${f.name}: 허용되지 않은 의존 $it", it in setOf("com.hmgcarapp.core", "com.hmgcarapp.pleos", "com.hmgcarapp.wakemate"))
            }
        }
    }

    @Test fun hvacAreasComeFromCapabilityResponses_notHardcoded() {
        (kt("vehicle") + kt("app")).forEach { f ->
            val c = code(f)
            assertFalse("${f.name}: SeatArea 하드코딩", Regex("SeatArea\\(|SeatValue\\.|ROW_1_LEFT").containsMatchIn(c))
        }
        assertTrue(code(File(root, "domain/AreaPicker.kt")).contains("ROW_1_LEFT")) // 선택 기준(영역 이름 비교)만 순수 함수에 있다
    }

    @Test fun noNetworkNoSecretsNoCleartext() {
        kt().forEach { f ->
            val c = code(f)
            assertFalse("${f.name}: 평문 http", Regex("\"http://").containsMatchIn(c))
            assertFalse("${f.name}: API 키 사용", c.contains("API_KEY") || c.contains("apiKey"))
        }
        val manifest = File("src/main/AndroidManifest.xml").readText()
        listOf("INTERNET", "FOREGROUND_SERVICE", "RECEIVE_BOOT_COMPLETED", "POST_NOTIFICATIONS", "RECORD_AUDIO", "READ_CONTACTS").forEach { assertFalse("불필요 권한 $it", manifest.contains(it)) }
        assertTrue(manifest.contains("android:name=\"distractionOptimized\" android:value=\"true\""))
        assertTrue(File("src/main/res/xml/network_security_config.xml").readText().contains("cleartextTrafficPermitted=\"false\""))
        assertFalse(File("build.gradle.kts").readText().contains("API_KEY"))
    }

    @Test fun logsNeverPrintCoordinatesOrSpeechOutsideDebuggable() {
        kt().forEach { f ->
            code(f).lines().filter { it.contains("HmgLog.") }.forEach { line ->
                assertFalse("${f.name}: 좌표/위치 값을 로그에 남김: $line", Regex("latitude|longitude|\\.lat\\b|\\.lon\\b|origin").containsMatchIn(line))
            }
        }
        // 발화 원문 로그는 debuggable 조건 아래에서만
        val runner = code(File(root, "app/WakeRunner.kt"))
        assertTrue(runner.contains("if (debuggable) HmgLog.i(\"Voice\", \"STT:"))
    }

    @Test fun sdkCallbacksReturnUnit_viaManagedConnection() {
        // 모든 SDK 연결은 core ManagedConnection 위에서만(직접 재연결 루프 금지)
        listOf("vehicle/WakeHvac.kt", "vehicle/SteeringSource.kt", "navi/NaviController.kt").forEach {
            assertTrue("$it: ManagedConnection 사용", code(File(root, it)).contains("ManagedConnection"))
        }
    }

    @Test fun shelterAsset_isLabeledSampleAndNotRealCoordinatesClaim() {
        val t = File("src/main/assets/rest_shelters_sample.json").readText()
        assertTrue(t.contains("\"sample\": true") && t.contains("가상") && t.contains("실제"))
    }
}

class ShelterNoticeTest {
    @org.junit.Test fun fictionalSentenceOnlyForSampleData() {
        org.junit.Assert.assertEquals("직선거리 기준이며 실제 도로 거리·진행 방향은 반영하지 않아요. 아래 항목은 형식 확인용 가상 데이터이며 실제 쉼터가 아니에요.", com.hmgcarapp.wakemate.ui.shelterNotice(true))
        org.junit.Assert.assertEquals("직선거리 기준이며 실제 도로 거리·진행 방향은 반영하지 않아요. 실시간 정보가 아니에요.", com.hmgcarapp.wakemate.ui.shelterNotice(false))
        org.junit.Assert.assertEquals("시연용 가상 샘플 데이터", com.hmgcarapp.wakemate.ui.shelterBadge(true, "2026-11-11"))
        org.junit.Assert.assertEquals("실시간 아님 · 공공데이터 스냅샷 · 데이터 기준일 2026-02-25 · 수집일 2026-11-11 · 한국도로공사", com.hmgcarapp.wakemate.ui.shelterBadge(false, "2026-02-25", "2026-11-11"))
    }

    @org.junit.Test fun distanceSpeechBoundary() {
        org.junit.Assert.assertEquals("바로 근처예요", com.hmgcarapp.wakemate.domain.WakeText.distanceSpeech(0.0))
        org.junit.Assert.assertEquals("바로 근처예요", com.hmgcarapp.wakemate.domain.WakeText.distanceSpeech(0.049))
        org.junit.Assert.assertEquals("약 0.1킬로미터예요", com.hmgcarapp.wakemate.domain.WakeText.distanceSpeech(0.05))
        org.junit.Assert.assertEquals("약 1.2킬로미터예요", com.hmgcarapp.wakemate.domain.WakeText.distanceSpeech(1.2))
    }
}

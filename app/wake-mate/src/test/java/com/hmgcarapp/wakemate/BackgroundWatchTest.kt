package com.hmgcarapp.wakemate

import com.hmgcarapp.core.store.LocalJsonStore
import com.hmgcarapp.wakemate.app.BackgroundWatch
import com.hmgcarapp.wakemate.app.BackgroundWatch.Decision
import com.hmgcarapp.wakemate.app.BackgroundWatch.Inputs
import com.hmgcarapp.wakemate.app.Persisted
import com.hmgcarapp.wakemate.app.Settings
import com.hmgcarapp.wakemate.app.StateCodec
import com.hmgcarapp.wakemate.app.StateStore
import com.hmgcarapp.wakemate.domain.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackgroundWatchTest {
    private val v = BackgroundWatch.NOTICE_VERSION
    private fun inp(enabled: Boolean = true, ver: Int = v, unlocked: Boolean = true, fg: Boolean = true, perm: Boolean = true, notif: Boolean = true) = Inputs(enabled, ver, unlocked, fg, perm, notif)

    @Test fun defaultsAreOffAndNoConsent() {
        assertFalse(Settings().backgroundWatch); assertEquals(0, Settings().backgroundConsentVer)
        assertEquals(Decision.DISABLED, BackgroundWatch.decide(inp(enabled = false, ver = 0)))
    }

    @Test fun truthTable_allCombinations() {
        for (enabled in listOf(false, true)) for (ver in listOf(0, v, v + 1)) for (unlocked in listOf(false, true)) for (fg in listOf(false, true)) for (perm in listOf(false, true)) {
            val expected = when {
                !enabled -> Decision.DISABLED
                ver != v -> Decision.NO_CONSENT
                !unlocked -> Decision.USER_LOCKED
                !fg -> Decision.NOT_FOREGROUND_USER
                !perm -> Decision.NO_PERMISSION
                else -> Decision.START
            }
            val i = inp(enabled, ver, unlocked, fg, perm)
            assertEquals("$i", expected, BackgroundWatch.decide(i))
            // 시작은 오직 모든 조건이 참일 때 한 가지 조합뿐
            assertEquals(expected == Decision.START, enabled && ver == v && unlocked && fg && perm)
        }
    }

    @Test fun bootStartsOnlyOnBootCompletedWithAllConditions() {
        assertTrue(BackgroundWatch.shouldStartOnBoot("android.intent.action.BOOT_COMPLETED", inp()))
        assertFalse(BackgroundWatch.shouldStartOnBoot("android.intent.action.LOCKED_BOOT_COMPLETED", inp()))
        assertFalse(BackgroundWatch.shouldStartOnBoot("android.intent.action.MY_PACKAGE_REPLACED", inp()))
        assertFalse(BackgroundWatch.shouldStartOnBoot(null, inp()))
        assertFalse("설정을 읽지 못하면 시작 안 함", BackgroundWatch.shouldStartOnBoot("android.intent.action.BOOT_COMPLETED", null))
        assertFalse("동의 전", BackgroundWatch.shouldStartOnBoot("android.intent.action.BOOT_COMPLETED", inp(ver = 0)))
        assertFalse("설정 꺼짐", BackgroundWatch.shouldStartOnBoot("android.intent.action.BOOT_COMPLETED", inp(enabled = false)))
        assertFalse("다른 사용자(뒤로 간)", BackgroundWatch.shouldStartOnBoot("android.intent.action.BOOT_COMPLETED", inp(fg = false)))
        assertFalse("권한 없음", BackgroundWatch.shouldStartOnBoot("android.intent.action.BOOT_COMPLETED", inp(perm = false)))
    }

    @Test fun settingsInputs_nullSettingsMeansNull() {
        assertNull(BackgroundWatch.settingsInputs(null, true, true, true))
        assertEquals(inp(), BackgroundWatch.settingsInputs(Settings(backgroundWatch = true, backgroundConsentVer = v), true, true, true))
    }

    @Test fun attempt_swallowsAnyThrowable_andReportsFailure_withoutRetry() {
        var calls = 0
        assertFalse(BackgroundWatch.attempt("t") { calls++; throw SecurityException("while-in-use") })
        assertFalse(BackgroundWatch.attempt("t") { calls++; throw IllegalStateException("x") })
        assertFalse(BackgroundWatch.attempt("t") { calls++; throw OutOfMemoryError() })
        assertEquals(3, calls) // 호출당 정확히 1번 실행
        assertTrue(BackgroundWatch.attempt("t") { calls++ })
    }

    @Test fun settingsCodec_roundTrip_andOldFilesDefaultToOff() {
        val p = Persisted(settings = Settings(backgroundWatch = true, backgroundConsentVer = v))
        assertEquals(p, StateCodec.parse(StateCodec.encode(p).toString()).p)
        val old = StateCodec.parse("""{"v":1,"voiceReply":true,"hvacControl":true,"beep":true}""")
        assertFalse(old.p.settings.backgroundWatch); assertEquals(0, old.p.settings.backgroundConsentVer)
        assertEquals(0, StateCodec.parse("""{"bgConsentVer":-4}""").p.settings.backgroundConsentVer)
    }

    @get:Rule val tmp = TemporaryFolder()

    @Test fun peekSettings_readOnly_andNullWhenMissingOrCorrupt() {
        val store = LocalJsonStore(tmp.newFolder("s"))
        assertNull(StateStore.peekSettings(store))
        store.saveText("wakemate_state", "{bad")
        assertNull(StateStore.peekSettings(store))
        assertTrue("손상 파일을 격리하지 않는다", store.exists("wakemate_state"))
        store.saveText("wakemate_state", StateCodec.encode(Persisted(settings = Settings(backgroundWatch = true, backgroundConsentVer = v))).toString())
        assertEquals(true, StateStore.peekSettings(store)?.backgroundWatch)
    }

    @Test fun notificationText_hasNoPersonalValuesAndCoversStages() {
        val all = (listOf<Stage?>(null) + Stage.entries).map { BackgroundWatch.notificationBody(it) }
        assertEquals(4, all.toSet().size)
        all.forEach { assertFalse(Regex("\\d").containsMatchIn(it)) }
        assertEquals(BackgroundWatch.notificationBody(null), BackgroundWatch.notificationBody(Stage.NONE))
    }

    @Test fun noticeMatchesCode_noLocationCollectionStatement() {
        val n = BackgroundWatch.NOTICE
        assertTrue(n.contains("위치는 수집하지 않고") && n.contains("알림") && n.contains("끄기"))
        assertTrue("팝업 본문은 1~2문장", n.length < 100)
        assertTrue("스위치 부연 설명 문구는 없다", BackgroundWatch::class.java.declaredFields.none { it.name == "SWITCH_HINT" })
    }

    @Test fun noNotificationPermission_doesNotStart() {
        assertEquals(Decision.NO_NOTIFICATION, BackgroundWatch.decide(inp(notif = false)))
        assertEquals(Decision.NO_PERMISSION, BackgroundWatch.decide(inp(perm = false, notif = false)))
        assertFalse(BackgroundWatch.shouldStartOnBoot(BackgroundWatch.ACTION_BOOT_COMPLETED, inp(notif = false)))
        assertTrue(BackgroundWatch.NOTIF_DENIED_HINT.contains("알림") && BackgroundWatch.NOTIF_DENIED_HINT.contains("켤 수 없어요"))
    }

    @Test fun notifHintClearsOnceGrantedOrSwitchedOff() {
        val ui = java.io.File("src/main/java/com/hmgcarapp/wakemate/ui/Screens.kt").readText()
        assertTrue("안내가 떠 있는 동안 권한을 다시 확인한다", ui.contains("LaunchedEffect(notifHint)") && ui.contains("BackgroundWatch.HINT_RECHECK_MS") && ui.contains("if (WakeRuntime.notificationsGranted(ctx)) notifHint = false"))
        assertTrue("켜기 재시도(허용 상태)와 끄기에서 지운다", ui.contains("if (!on) { notifHint = false;") && ui.contains("{ notifHint = false; askBg = true }"))
        assertTrue(BackgroundWatch.HINT_RECHECK_MS in 200L..5_000L)
    }

    @Test fun consentPopup_isShortAndVersionBumped() {
        assertTrue(BackgroundWatch.NOTICE.length < 100)
        assertTrue(BackgroundWatch.NOTICE_VERSION >= 3) // 고지문 변경으로 이전 동의 무효
    }
}

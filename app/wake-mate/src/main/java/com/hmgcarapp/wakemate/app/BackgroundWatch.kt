package com.hmgcarapp.wakemate.app

import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.wakemate.domain.Stage

/**
 * 백그라운드 감시(화면 밖 포그라운드 서비스 + 설정 시 부팅 시작)의 시작 조건과 문구. 순수 로직(안드로이드 의존 없음)이라 단위 테스트한다.
 * 서비스는 위치가 필요 없는 비위치 유형(specialUse)이며, 이 파일·서비스·수신기는 위치를 다루지 않는다.
 */
object BackgroundWatch {
    /** 고지문 버전. 고지문을 바꾸면 올려서 이전 동의를 무효로 만든다. */
    const val NOTICE_VERSION = 2
    const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"

    data class Inputs(
        /** 설정의 '백그라운드 감시' 값 */ val enabled: Boolean,
        /** 저장된 동의 고지문 버전(0 = 동의 없음) */ val consentVersion: Int,
        /** 현재 사용자 저장소 잠금 해제됨 */ val userUnlocked: Boolean,
        /** 현재 사용자가 화면 앞 사용자(전환되어 뒤로 가지 않음) */ val foregroundUser: Boolean,
        /** 차속 권한(CAR_SPEED) 보유 */ val carSpeedGranted: Boolean,
        /** 알림 표시 가능(알림 권한). 없으면 감시 중임이 보이지 않으므로 시작하지 않는다 */ val notificationsGranted: Boolean = true,
    )

    enum class Decision { START, DISABLED, NO_CONSENT, USER_LOCKED, NOT_FOREGROUND_USER, NO_PERMISSION, NO_NOTIFICATION }

    fun decide(i: Inputs): Decision = when {
        !i.enabled -> Decision.DISABLED
        i.consentVersion != NOTICE_VERSION -> Decision.NO_CONSENT
        !i.userUnlocked -> Decision.USER_LOCKED
        !i.foregroundUser -> Decision.NOT_FOREGROUND_USER
        !i.carSpeedGranted -> Decision.NO_PERMISSION
        !i.notificationsGranted -> Decision.NO_NOTIFICATION
        else -> Decision.START
    }

    /** 부팅 수신 시 서비스를 시작할지: BOOT_COMPLETED 이고 모든 조건을 만족할 때만. 설정을 읽지 못했으면(null) 시작하지 않는다. */
    fun shouldStartOnBoot(action: String?, i: Inputs?): Boolean = action == ACTION_BOOT_COMPLETED && i != null && decide(i) == Decision.START

    fun settingsInputs(s: Settings?, userUnlocked: Boolean, foregroundUser: Boolean, carSpeedGranted: Boolean, notificationsGranted: Boolean = true): Inputs? =
        s?.let { Inputs(it.backgroundWatch, it.backgroundConsentVer, userUnlocked, foregroundUser, carSpeedGranted, notificationsGranted) }

    /** 시작 시도를 감싼다: 어떤 예외(SecurityException 등)도 삼키고 로그만 남기며 재시도하지 않는다. 성공이면 true. */
    inline fun attempt(tag: String, block: () -> Unit): Boolean = try { block(); true } catch (t: Throwable) { HmgLog.w(tag, "백그라운드 감시 시작 실패(무동작): ${t.javaClass.simpleName}"); false }

    const val NOTIF_TITLE = "졸음 감시 중"
    const val ACTION_LABEL_OFF = "끄기"
    const val ACTION_LABEL_OPEN = "열기"

    /** 알림 본문: 위치·점수 등 개인 값 없이 단계 이름만. */
    fun notificationBody(stage: Stage?): String = when (stage) {
        null, Stage.NONE -> "차량 신호(차속·조향 등)로 졸음 징후를 살피는 중이에요. 위치는 수집하지 않아요"
        Stage.S1 -> "졸음 주의 단계예요. 졸리면 안전한 곳에 정차해 쉬세요"
        Stage.S2 -> "졸음 경고 단계예요. 가까운 쉼터에서 쉬세요"
        Stage.S3 -> "졸음 위험 단계예요. 즉시 안전한 곳에 정차해 쉬세요"
    }

    // ---- 설정 화면 문구(고지문은 코드 동작과 일치해야 한다: 바꾸면 NOTICE_VERSION 을 올린다) ----
    const val SWITCH_LABEL = "백그라운드 감시"
    const val SWITCH_HINT = "켜면 앱 화면이 다른 화면에 가려져도 졸음 감시를 이어가고, 알림을 계속 표시해요. 기본값은 꺼짐이에요."
    const val POPUP_TITLE = "백그라운드 감시를 켤까요?"
    /** 동의 팝업 상단 요약(전체 고지문은 팝업 안에서 스크롤해 확인한다) */
    const val NOTICE_SUMMARY = "차량 신호만 읽어 화면 밖에서도 졸음 위험을 살펴요. 위치는 수집하지 않아요. 아래 내용을 끝까지 읽고 동의해 주세요."
    /** 알림 권한 없이 켜려 할 때의 안내(켜지 않는다) */
    const val NOTIF_DENIED_HINT = "알림 권한이 꺼져 있어 켤 수 없어요. 알림이 꺼지면 감시 중인지 알 수 없어요. 시스템 설정에서 이 앱의 알림을 허용한 뒤 다시 켜 주세요."
    /** 안내가 떠 있는 동안 알림 권한을 다시 확인하는 주기(허용되면 안내를 지운다) */
    const val HINT_RECHECK_MS = 1000L
    /** 음성 한계 안내(설정 하단): 확인한 범위만, 단정하지 않는다 */
    const val VOICE_LIMIT_NOTE = "앱이 다른 화면에 가려져도 음성 안내가 나오는 것을 확인했지만, 기기·상황에 따라 음성 안내·음성 인식이 멈출 수 있어요. 부팅 직후 자동으로 시작된 감시는 음성 인식(마이크)이 안 될 수 있어요."
    const val CONFIRM_LABEL = "동의하고 켜기"
    val NOTICE: String = listOf(
        "· 화면 밖에서도 차량 신호(차속·조향각·기어)만 읽어 졸음 위험을 계산해요. 위치는 수집하지 않아요.",
        "· 계산 결과와 신호 값은 저장하지 않아요(저장되는 것은 기존 설정·보정값·휴식 기록·누적 주행 시간뿐이에요).",
        "· 서비스가 동작하는 동안 알림이 계속 보이고, 알림의 '끄기'로 언제든 끌 수 있어요.",
        "· 기기를 켤 때 이 설정을 켠 경우에만 자동으로 시작해요. 시동 신호는 아니고 기기 부팅 완료를 기준으로 해요.",
        "· 사용자 전환, 권한 해제, 이 설정을 끄면 바로 멈춰요. 주행 중에 화면을 자동으로 띄우지 않아요.",
        "· 앱이 가려져도 음성 안내가 나오는 것을 확인했지만, 기기·상황에 따라 음성 안내·인식이 멈출 수 있어요. 부팅 직후 자동으로 시작된 감시는 음성 인식(마이크)이 안 될 수 있어요(경고음·공조 조절·알림 문구는 계속돼요).",
        "· 알림 권한이 있어야 켤 수 있어요. 알림이 감시 중임을 알려줘요.",
        "· 이 앱은 운전자 보조 도구이며 졸음 운전을 막을 수 없어요.",
    ).joinToString("\n")
}

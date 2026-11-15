package com.hmgcarapp.wakemate.domain

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** capability 응답에서 얻은 쓰기 가능 여부와 범위. 영역(SeatArea)은 어댑터가 속성별로 보관한다(여기엔 없다). */
data class HvacCaps(
    val recirc: Boolean = false, val fan: Boolean = false, val temp: Boolean = false,
    val fanMin: Int = 1, val fanMax: Int = 1, val tempMin: Float = 16f, val tempMax: Float = 32f,
    val seatVent: Boolean = false, val seatVentMax: Int = 3,
    val ambientColor: Boolean = false,
) { val anyWritable: Boolean get() = recirc || fan || temp || seatVent || ambientColor }

/** 현재 공조 값(읽기 결과). null = 모름. */
data class HvacNow(
    val power: Boolean? = null, val recirc: Boolean? = null, val fan: Int? = null, val tempC: Float? = null,
    val seatVent: Int? = null, val ambientColor: Int? = null,
)

/** 쓸 값. null 인 항목은 쓰지 않는다. */
data class HvacTargets(
    val recirc: Boolean? = null, val fan: Int? = null, val tempC: Float? = null,
    val seatVent: Int? = null, val ambientColor: Int? = null,
) {
    val kinds: List<String> get() = buildList {
        if (recirc != null) add("recirc")
        if (fan != null) add("fan")
        if (tempC != null) add("temp")
        if (seatVent != null) add("seat")
        if (ambientColor != null) add("ambient")
    }
    val isEmpty: Boolean get() = kinds.isEmpty()
}

/** 한 항목의 쓰기 결과. [code] 는 실패 시 오류 코드(없으면 null). */
data class ItemResult(val kind: String, val ok: Boolean, val code: Int?)

/** 2단계 물리 각성 계획(순수): 외기 순환, 풍량 80%, 설정 온도 -2℃, 운전석 시트 통풍 최대, 실내 앰비언트 경고색. 이미 충분하면 쓰지 않는다. */
object HvacPlan {
    const val TEMP_DROP_C = 2f
    const val FAN_FRACTION = 0.8
    /** 경고·위험 단계 실내 앰비언트 라이트 색상(불투명 레드-오렌지 ARGB). */
    const val ALERT_AMBIENT_ARGB = -50401

    /** 1단계 제안 수락: 외기 순환만 바꾼다(풍량·온도는 2단계에서). */
    fun planVent(now: HvacNow, caps: HvacCaps): HvacTargets =
        HvacTargets(recirc = if (caps.recirc && now.recirc != false) false else null)

    /** 공조 조정을 시도할 수 있는지. 외기 전환만 하는 제안(ventOnly)은 외기 순환을 쓸 수 있어야 한다(다른 항목만 쓸 수 있으면 아무것도 못 한다). */
    fun usable(connected: Boolean, caps: HvacCaps, ventOnly: Boolean): Boolean =
        connected && caps.anyWritable && (!ventOnly || caps.recirc)

    fun plan(now: HvacNow, caps: HvacCaps, baseTempC: Float? = null): HvacTargets {
        val recirc = if (caps.recirc && now.recirc != false) false else null
        val fanTarget = ceil(caps.fanMax * FAN_FRACTION).toInt().coerceIn(caps.fanMin, caps.fanMax)
        val fan = if (caps.fan && (now.fan == null || now.fan < fanTarget)) fanTarget else null
        // 기준 온도(에피소드 첫 적용 때 읽은 값)가 있으면 그것에서 2℃만 낮춘다: 반복해도 더 내려가지 않는다
        val base = baseTempC ?: now.tempC
        val temp = if (caps.temp && base != null) {
            val t = (floor((base - TEMP_DROP_C) * 2) / 2f).coerceIn(caps.tempMin, caps.tempMax)
            if (now.tempC == null || t < now.tempC - 0.01f) t else null
        } else null
        val seatTarget = caps.seatVentMax.coerceAtLeast(1)
        val seatVent = if (caps.seatVent && (now.seatVent == null || now.seatVent < seatTarget)) seatTarget else null
        val ambient = if (caps.ambientColor && now.ambientColor != ALERT_AMBIENT_ARGB) ALERT_AMBIENT_ARGB else null
        return HvacTargets(recirc, fan, temp, seatVent, ambient)
    }
}

/** 쓰기 오류 코드 해석(AAOS CAR_SET_PROPERTY_ERROR_CODE_*: 0 UNKNOWN, 1 INVALID_ARG, 2 NOT_AVAILABLE, 3 TRY_AGAIN, 4 TIMEOUT, 5 ACCESS_DENIED). */
object HvacErrors {
    private val CODE = Regex("error\\s*code\\s*[:=]?\\s*(\\d+)", RegexOption.IGNORE_CASE)
    /** Vehicle SDK ErrorCode.ERROR_DRIVING_RESTRICTED 의 id(vehicle-data 의 ErrorCode 열거형). 주행 제한으로 쓰기가 거부됨. */
    const val DRIVING_RESTRICTED = 50001
    private const val DRIVING_RESTRICTED_NAME = "ERROR_DRIVING_RESTRICTED"
    /** Vehicle SDK 의 VehicleException 은 message 에 코드 이름을 담는다(예: ERROR_DRIVING_RESTRICTED). 이름 또는 "error code N" 을 해석한다. */
    fun code(text: String?): Int? {
        if (text == null) return null
        if (text.contains(DRIVING_RESTRICTED_NAME)) return DRIVING_RESTRICTED
        return CODE.find(text)?.groupValues?.get(1)?.toIntOrNull()
    }
    fun codeOf(t: Throwable): Int? {
        var e: Throwable? = t; var d = 0
        while (e != null && d++ < 5) { code(e.message)?.let { return it }; e = e.cause }
        return null
    }
    fun reason(code: Int?): String = when (code) {
        1 -> "값이 올바르지 않아요"
        2 -> "공조가 꺼져 있어 바꿀 수 없어요"
        3 -> "잠시 후 다시 시도해야 해요"
        4 -> "차량 응답이 늦었어요"
        5 -> "공조를 바꿀 권한이 없어요"
        DRIVING_RESTRICTED -> "주행 중에는 차량 정책상 앱이 공조를 바꿀 수 없어요. 창문을 열어 환기해 주세요"
        else -> "알 수 없는 오류예요"
    }
}

/** 쓰기 결과를 사용자에게 정직하게 알리는 문구(결과를 받은 뒤에만 사용). */
object HvacReport {
    private fun okPhrase(k: String) = when (k) {
        "recirc" -> "외기 순환으로 바꿨어요"
        "fan" -> "바람 세기를 올렸어요"
        "temp" -> "설정 온도를 낮췄어요"
        "seat" -> "시트 통풍을 켰어요"
        "ambient" -> "실내 조명을 경고색으로 바꿨어요"
        else -> "변경했어요"
    }
    /** 주제 조사(은/는)까지 포함한 항목 이름(받침에 맞춰 고정). */
    private fun topic(k: String) = when (k) {
        "recirc" -> "외기 순환은"
        "fan" -> "바람 세기는"
        "temp" -> "설정 온도는"
        "seat" -> "시트 통풍은"
        "ambient" -> "실내 조명은"
        else -> "${k}은(는)"
    }

    /** 음성·배너용 한 문단. */
    fun summary(results: List<ItemResult>, planned: HvacTargets, connected: Boolean, ventOnly: Boolean = false): String {
        if (!connected && ventOnly) return "공조에 연결하지 못했거나 이 차량에서는 앱이 외기 전환을 할 수 없어요. 창문을 열어 환기해 주세요"
        if (!connected) return "공조에 연결하지 못했거나 이 차량에서는 앱이 바꿀 수 없어요. 창문을 열어 환기해 주세요"
        if (planned.isEmpty && results.isEmpty()) return "공조는 이미 환기에 알맞은 상태예요"
        if (results.isEmpty()) return "공조 응답을 받지 못했어요. 창문을 열어 환기해 주세요"
        val ok = results.filter { it.ok }
        val bad = results.filter { !it.ok }
        val parts = ArrayList<String>()
        if (ok.isNotEmpty()) parts += ok.joinToString(", ") { okPhrase(it.kind) }
        bad.forEach { parts += "${topic(it.kind)} ${reasonFor(it.kind, it.code)}" }
        return parts.joinToString(". ")
    }

    /** 시트·조명은 공조가 아니므로 주행 제한 문구를 따로 쓴다. */
    private fun reasonFor(kind: String, code: Int?): String =
        if (code == HvacErrors.DRIVING_RESTRICTED && (kind == "seat" || kind == "ambient")) "주행 중에는 차량 정책상 앱이 바꿀 수 없어요" else HvacErrors.reason(code)

    /** 일부/전부 실패했는지. */
    fun hasFailure(results: List<ItemResult>) = results.any { !it.ok }

    fun roundHalf(v: Float): Float = (v * 2).roundToInt() / 2f
}

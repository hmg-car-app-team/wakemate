package com.hmgcarapp.wakemate.domain

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** capability 응답에서 얻은 쓰기 가능 여부와 범위. 영역(SeatArea)은 어댑터가 속성별로 보관한다(여기엔 없다). */
data class HvacCaps(
    val recirc: Boolean = false, val fan: Boolean = false, val temp: Boolean = false,
    val fanMin: Int = 1, val fanMax: Int = 1, val tempMin: Float = 16f, val tempMax: Float = 32f,
) { val anyWritable: Boolean get() = recirc || fan || temp }

/** 현재 공조 값(읽기 결과). null = 모름. */
data class HvacNow(val power: Boolean? = null, val recirc: Boolean? = null, val fan: Int? = null, val tempC: Float? = null)

/** 쓸 값. null 인 항목은 쓰지 않는다. */
data class HvacTargets(val recirc: Boolean? = null, val fan: Int? = null, val tempC: Float? = null) {
    val kinds: List<String> get() = buildList { if (recirc != null) add("recirc"); if (fan != null) add("fan"); if (tempC != null) add("temp") }
    val isEmpty: Boolean get() = kinds.isEmpty()
}

/** 한 항목의 쓰기 결과. [code] 는 실패 시 오류 코드(없으면 null). */
data class ItemResult(val kind: String, val ok: Boolean, val code: Int?)

/** 2단계 공조 계획(순수): 외기 순환으로, 풍량은 최대의 약 80%까지, 설정 온도는 현재보다 2℃ 낮게. 이미 충분하면 쓰지 않는다. */
object HvacPlan {
    const val TEMP_DROP_C = 2f
    const val FAN_FRACTION = 0.8

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
        return HvacTargets(recirc, fan, temp)
    }
}

/** 쓰기 오류 코드 해석(AAOS CAR_SET_PROPERTY_ERROR_CODE_*: 0 UNKNOWN, 1 INVALID_ARG, 2 NOT_AVAILABLE, 3 TRY_AGAIN, 4 TIMEOUT, 5 ACCESS_DENIED). */
object HvacErrors {
    private val CODE = Regex("error\\s*code\\s*[:=]?\\s*(\\d+)", RegexOption.IGNORE_CASE)
    fun code(text: String?): Int? = text?.let { CODE.find(it)?.groupValues?.get(1)?.toIntOrNull() }
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
        else -> "알 수 없는 오류예요"
    }
}

/** 쓰기 결과를 사용자에게 정직하게 알리는 문구(결과를 받은 뒤에만 사용). */
object HvacReport {
    private fun okPhrase(k: String) = when (k) { "recirc" -> "외기 순환으로 바꿨어요"; "fan" -> "바람 세기를 올렸어요"; "temp" -> "설정 온도를 낮췄어요"; else -> "변경했어요" }
    /** 주제 조사(은/는)까지 포함한 항목 이름(받침에 맞춰 고정). */
    private fun topic(k: String) = when (k) { "recirc" -> "외기 순환은"; "fan" -> "바람 세기는"; "temp" -> "설정 온도는"; else -> "${k}은(는)" }

    /** 음성·배너용 한 문단. */
    fun summary(results: List<ItemResult>, planned: HvacTargets, connected: Boolean): String {
        if (!connected) return "공조에 연결하지 못했거나 이 차량에서는 앱이 바꿀 수 없어요. 창문을 열어 환기해 주세요"
        if (planned.isEmpty && results.isEmpty()) return "공조는 이미 환기에 알맞은 상태예요"
        if (results.isEmpty()) return "공조 응답을 받지 못했어요. 창문을 열어 환기해 주세요"
        val ok = results.filter { it.ok }
        val bad = results.filter { !it.ok }
        val parts = ArrayList<String>()
        if (ok.isNotEmpty()) parts += ok.joinToString(", ") { okPhrase(it.kind) }
        bad.forEach { parts += "${topic(it.kind)} ${HvacErrors.reason(it.code)}" }
        return parts.joinToString(". ")
    }

    /** 일부/전부 실패했는지. */
    fun hasFailure(results: List<ItemResult>) = results.any { !it.ok }

    fun roundHalf(v: Float): Float = (v * 2).roundToInt() / 2f
}

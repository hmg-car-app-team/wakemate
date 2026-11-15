package com.hmgcarapp.wakemate.domain

import java.util.Locale

enum class StimulusState { CHECKING, UNAVAILABLE, UNSUPPORTED, IDLE, ACTIVE, FAILED }

/** 자극 종류. [short] 는 경고 카드 칩에 쓰는 짧은 이름. */
enum class StimulusKind(val short: String) { AIR("공조"), SEAT("시트"), WHEEL("열선"), LIGHT("무드등") }

data class StimulusItem(val title: String, val state: StimulusState, val label: String, val detail: String, val kind: StimulusKind = StimulusKind.AIR) {
    val active: Boolean get() = state == StimulusState.ACTIVE
    /** 카드 칩 이름. 무드등을 못 바꾸고 경고음만 울렸으면 '경고음'. */
    val chipLabel: String get() = if (kind == StimulusKind.LIGHT && label == "경고음") "경고음" else kind.short
}

/**
 * 물리 자극 카드 4장(공조 환기 · 시트 통풍 · 핸들 열선 · 무드등과 경고음)의 상태(순수).
 * '작동'은 차량이 쓰기 성공을 알려 준 항목과 실제로 울린 경고음뿐이다. 위험 단계나 설정만으로 작동이라고 하지 않는다.
 */
object StimulusView {
    data class Input(
        val capsKnown: Boolean,
        /** 차량 SDK에 닿지 못해 지원 여부를 알 수 없는 상태(오류가 있고 capability 응답이 없음). */
        val unavailable: Boolean,
        val caps: HvacCaps,
        val now: HvacNow,
        val last: List<ItemResult>,
        /** 진행 중이거나 방금 끝난 깨우기 단계(연습 제외). 경고음은 2단계 이상 깨우기에서만 울린다. */
        val alertStage: Stage?,
        val autoControl: Boolean,
        val beep: Boolean,
    )

    fun of(i: Input): List<StimulusItem> = listOf(
        air(i).copy(kind = StimulusKind.AIR),
        seat(i).copy(kind = StimulusKind.SEAT),
        wheel(i).copy(kind = StimulusKind.WHEEL),
        lightAndSound(i).copy(kind = StimulusKind.LIGHT),
    )

    private fun results(i: Input, vararg kinds: String) = i.last.filter { it.kind in kinds }

    /** 쓰기 결과가 없을 때: 지원 여부와 설정에 따른 대기 상태. */
    private fun waiting(i: Input, title: String, supported: Boolean, detail: String, unsupportedDetail: String): StimulusItem = when {
        !i.capsKnown && i.unavailable -> StimulusItem(title, StimulusState.UNAVAILABLE, "연결 안 됨", unsupportedDetail)
        !i.capsKnown -> StimulusItem(title, StimulusState.CHECKING, "확인 중", detail)
        !supported -> StimulusItem(title, StimulusState.UNSUPPORTED, "미지원", unsupportedDetail)
        !i.autoControl -> StimulusItem(title, StimulusState.IDLE, "자동 조절 꺼짐", detail)
        else -> StimulusItem(title, StimulusState.IDLE, "대기", detail)
    }

    private fun air(i: Input): StimulusItem {
        val title = "공조 환기"
        val read = listOfNotNull(
            when (i.now.recirc) { false -> "외기"; true -> "내기 순환"; null -> null },
            i.now.fan?.let { "풍량 ${it}단" },
            i.now.tempC?.let { String.format(Locale.KOREA, "%.1f℃", it) },
        ).joinToString(" · ")
        val detail = read.ifEmpty { "경고 단계에서 외기 전환 · 풍량 올림 · 온도 2℃ 낮춤" }
        val rs = results(i, "recirc", "fan", "temp")
        return when {
            rs.any { it.ok } -> StimulusItem(title, StimulusState.ACTIVE, "작동", detail)
            rs.isNotEmpty() -> StimulusItem(title, StimulusState.FAILED, "실패", detail)
            else -> waiting(i, title, i.caps.recirc || i.caps.fan || i.caps.temp, detail, "이 차량에서는 앱이 공조를 바꿀 수 없어요")
        }
    }

    private fun seat(i: Input): StimulusItem {
        val title = "시트 통풍"
        val lv = i.now.seatVent
        val detail = if (lv != null && lv > 0) "운전석 통풍 ${lv}단" else "경고 단계에서 운전석 통풍 최대"
        val rs = results(i, "seat")
        return when {
            rs.any { it.ok } -> StimulusItem(title, StimulusState.ACTIVE, "작동", detail)
            rs.isNotEmpty() -> StimulusItem(title, StimulusState.FAILED, "실패", detail)
            else -> waiting(i, title, i.caps.seatVent, detail, "이 차량에서는 앱이 시트 통풍을 바꿀 수 없어요")
        }
    }

    private fun wheel(i: Input): StimulusItem {
        val title = "핸들 열선"
        val lv = i.now.wheelHeat
        val detail = if (lv != null && lv > 0) "핸들 열선 ${lv}단" else "경고 단계에서 핸들 열선 최대"
        val rs = results(i, "wheel")
        return when {
            rs.any { it.ok } -> StimulusItem(title, StimulusState.ACTIVE, "작동", detail)
            rs.isNotEmpty() -> StimulusItem(title, StimulusState.FAILED, "실패", detail)
            else -> waiting(i, title, i.caps.wheelHeat, detail, "이 차량에서는 앱이 핸들 열선을 바꿀 수 없어요")
        }
    }

    private fun lightAndSound(i: Input): StimulusItem {
        val title = "무드등 · 경고음"
        val rs = results(i, "ambient")
        val lightOk = rs.any { it.ok }
        val sound = i.beep && i.alertStage != null && i.alertStage.n >= Stage.S2.n
        return when {
            lightOk -> StimulusItem(title, StimulusState.ACTIVE, "작동", if (sound) "실내 무드등 경고색 · 경고음" else "실내 무드등 경고색")
            sound -> StimulusItem(title, StimulusState.ACTIVE, "경고음", if (rs.isNotEmpty()) "무드등 변경 실패 · 경고음만 울림" else "경고음 울림")
            rs.isNotEmpty() -> StimulusItem(title, StimulusState.FAILED, "실패", "무드등을 바꾸지 못했어요")
            else -> waiting(i, title, i.caps.ambientColor, "경고 단계에서 무드등 경고색 · 경고음",
                if (i.beep) "무드등은 바꿀 수 없어요. 경고음은 울려요" else "무드등은 바꿀 수 없어요")
        }
    }
}

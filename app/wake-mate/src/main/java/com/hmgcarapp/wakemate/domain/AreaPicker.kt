package com.hmgcarapp.wakemate.domain

/**
 * capability 가 돌려준 지원 영역들 중 운전석(ROW_1_LEFT)을 포함하는 영역을 고른다(순수).
 * 속성마다 지원 영역이 달라(에뮬레이터 실측: AC·내기 117=여러 좌석 묶음, 풍량 5=1열 좌+우, 온도 1/4) 고정 영역을 쓰면 `area ID not supported` 가 난다.
 * 우선순위: 운전석만 포함 > 포함하는 영역 중 값이 가장 적은 것 > 목록 앞쪽. 운전석을 포함하는 영역이 없으면 null(미지원).
 */
object AreaPicker {
    const val DRIVER = "ROW_1_LEFT"
    fun pickDriverArea(areas: List<List<String>>): Int? =
        areas.withIndex().filter { DRIVER in it.value.toSet() }
            .minWithOrNull(compareBy({ it.value.toSet().size }, { it.index }))?.index
}

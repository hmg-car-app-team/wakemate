package com.hmgcarapp.wakemate.domain

/** 다음 휴식 권장 문구(순수). 기준: 연속 2시간 주행마다 휴식(일반적 권고이며 개인차가 있다). */
object RestAdvice {
    const val RECOMMEND_MIN = 120

    fun next(drivingMin: Int, stage: Stage): String = when {
        stage.n >= Stage.S2.n -> "졸음 위험이 높아요. 가까운 쉼터나 안전한 곳에서 바로 쉬세요"
        drivingMin >= RECOMMEND_MIN -> "연속 ${drivingMin}분 주행했어요. 지금 쉬는 것을 권해요"
        else -> "${RECOMMEND_MIN - drivingMin}분 안에 한 번 쉬어 가세요(연속 2시간 기준)"
    }
}

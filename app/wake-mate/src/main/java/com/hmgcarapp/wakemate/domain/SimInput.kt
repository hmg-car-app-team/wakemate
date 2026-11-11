package com.hmgcarapp.wakemate.domain

import com.hmgcarapp.core.drive.Gear

/** 시연 broadcast extra 해석(순수). 형식·범위 오류는 null 로 돌려 무시한다. */
object SimInput {
    fun number(raw: Any?): Double? = (raw as? Number)?.toDouble()?.takeIf { it.isFinite() }

    fun pattern(s: String?): SimPattern? = when (s?.lowercase()) { "drowsy" -> SimPattern.DROWSY; "normal" -> SimPattern.NORMAL; else -> null }

    fun isOff(s: String?) = s?.lowercase() == "off"

    /** 0..23 시, 분은 0..59. 범위 밖이면 null. */
    fun minuteOfDay(hour: Any?, minute: Any?): Int? {
        val h = number(hour)?.toInt() ?: return null
        val m = if (minute == null) 0 else (number(minute)?.toInt() ?: return null)
        return if (h in 0..23 && m in 0..59) h * 60 + m else null
    }

    fun minutes(raw: Any?, max: Int = 24 * 60): Int? = number(raw)?.toInt()?.takeIf { it in 0..max }

    fun speed(raw: Any?): Float? = number(raw)?.toFloat()?.takeIf { it in 0f..300f }

    /** 진행 방향(도) 0..360. 범위 밖·비유한·비숫자는 null. */
    fun heading(raw: Any?): Float? = number(raw)?.toFloat()?.takeIf { it in 0f..360f }

    fun steer(raw: Any?): Float? = number(raw)?.toFloat()?.takeIf { it in -540f..540f }

    fun stage(raw: Any?): Stage? = number(raw)?.toInt()?.let { n -> Stage.entries.firstOrNull { it.n == n && n in 1..3 } }

    fun gear(s: String?): Gear? = when (s?.uppercase()) { "P", "PARK" -> Gear.PARK; "D", "DRIVE" -> Gear.DRIVE; "R", "REVERSE" -> Gear.REVERSE; "N", "NEUTRAL" -> Gear.NEUTRAL; else -> null }
}

package com.hmgcarapp.wakemate.app

import com.hmgcarapp.wakemate.domain.Calibration
import org.json.JSONArray
import org.json.JSONObject

data class Settings(
    val voiceReply: Boolean = true, val hvacControl: Boolean = true, val beep: Boolean = true,
    /** 백그라운드 감시(화면 밖 서비스·설정 시 부팅 시작). 기본 꺼짐, 고지문 동의 후에만 true. */
    val backgroundWatch: Boolean = false,
    /** 동의한 고지문 버전(0 = 동의 없음). 끄면 0 으로 돌린다(동의 철회). */
    val backgroundConsentVer: Int = 0,
)

/** 휴식 기록: 운전자 식별 정보 없이 시각과 시간만 저장한다. */
data class RestRecord(val atWallMs: Long, val drivingMin: Int, val restMin: Int, val sim: Boolean = false)

data class QuizStats(val total: Int = 0, val passed: Int = 0, val lastResponseMs: Long? = null)

data class Persisted(
    val settings: Settings = Settings(),
    val calibration: Calibration = Calibration(),
    val rests: List<RestRecord> = emptyList(),
    val quiz: QuizStats = QuizStats(),
    /** 프로세스 재시작 복원용 누적 주행 시간과 그 저장 시각(벽시계: 저장 만료 판단 전용). */
    val drivingMs: Long = 0L,
    val savedAtWallMs: Long = 0L,
)

object StateCodec {
    const val MAX_RESTS = 30
    const val VERSION = 1

    data class Parsed(val p: Persisted, val corrupt: Boolean)

    fun encode(p: Persisted): JSONObject = JSONObject()
        .put("v", VERSION)
        .put("voiceReply", p.settings.voiceReply).put("hvacControl", p.settings.hvacControl).put("beep", p.settings.beep)
        .put("bgWatch", p.settings.backgroundWatch).put("bgConsentVer", p.settings.backgroundConsentVer)
        .put("calOffset", p.calibration.offset).put("calCount", p.calibration.feedbackCount)
        .put("quizTotal", p.quiz.total).put("quizPassed", p.quiz.passed).put("quizLastMs", p.quiz.lastResponseMs ?: -1L)
        .put("drivingMs", p.drivingMs).put("savedAt", p.savedAtWallMs)
        .put("rests", JSONArray().also { a -> p.rests.takeLast(MAX_RESTS).forEach { a.put(JSONObject().put("at", it.atWallMs).put("d", it.drivingMin).put("r", it.restMin).put("s", it.sim)) } })

    /** 읽을 수 없는 JSON 이면 corrupt=true + 기본값. 값은 범위로 보정(sanitize)한다. */
    fun parse(text: String): Parsed = try {
        val o = JSONObject(text)
        val rests = ArrayList<RestRecord>()
        o.optJSONArray("rests")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { r ->
                rests += RestRecord(r.optLong("at", 0L), r.optInt("d", 0).coerceIn(0, 24 * 60), r.optInt("r", 0).coerceIn(0, 24 * 60), r.optBoolean("s", false))
            }
        }
        val last = o.optLong("quizLastMs", -1L)
        Parsed(
            Persisted(
                Settings(o.optBoolean("voiceReply", true), o.optBoolean("hvacControl", true), o.optBoolean("beep", true), o.optBoolean("bgWatch", false), o.optInt("bgConsentVer", 0).coerceAtLeast(0)),
                Calibration(o.optInt("calOffset", 0).coerceIn(0, Calibration.MAX_OFFSET), o.optInt("calCount", 0).coerceAtLeast(0)),
                rests.takeLast(MAX_RESTS),
                QuizStats(o.optInt("quizTotal", 0).coerceAtLeast(0), o.optInt("quizPassed", 0).coerceAtLeast(0), last.takeIf { it >= 0 }),
                o.optLong("drivingMs", 0L).coerceAtLeast(0L), o.optLong("savedAt", 0L).coerceAtLeast(0L),
            ),
            corrupt = false,
        )
    } catch (t: Throwable) { Parsed(Persisted(), corrupt = true) }
}

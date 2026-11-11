package com.hmgcarapp.wakemate

import com.hmgcarapp.core.drive.Gear
import com.hmgcarapp.core.store.LocalJsonStore
import com.hmgcarapp.wakemate.app.Persisted
import com.hmgcarapp.wakemate.app.QuizStats
import com.hmgcarapp.wakemate.app.RestRecord
import com.hmgcarapp.wakemate.app.Settings
import com.hmgcarapp.wakemate.app.StateCodec
import com.hmgcarapp.wakemate.app.StateStore
import com.hmgcarapp.wakemate.app.WriteResult
import com.hmgcarapp.wakemate.domain.Calibration
import com.hmgcarapp.wakemate.domain.RiskEngine
import com.hmgcarapp.wakemate.domain.RiskInputs
import com.hmgcarapp.wakemate.domain.SimInput
import com.hmgcarapp.wakemate.domain.SimPattern
import com.hmgcarapp.wakemate.domain.SimPatterns
import com.hmgcarapp.wakemate.domain.SpeedAnalyzer
import com.hmgcarapp.wakemate.domain.SpeedSample
import com.hmgcarapp.wakemate.domain.Stage
import com.hmgcarapp.wakemate.domain.SteerSample
import com.hmgcarapp.wakemate.domain.SteeringAnalyzer
import com.hmgcarapp.wakemate.domain.Thresholds
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SimAndStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    // ---------------- 시연 패턴 -> 엔진 ----------------
    private fun feed(p: SimPattern, untilMs: Long): Pair<List<SteerSample>, List<SpeedSample>> {
        val steer = (0..untilMs step 500).map { SteerSample(it, SimPatterns.sample(p, it).steerDeg) }
        val speed = (0..untilMs step 1000).map { SpeedSample(it, SimPatterns.sample(p, it).speedKmh) }
        return steer to speed
    }

    @Test fun drowsyPattern_producesRepeatedCorrectionsAndSpeedVariability() {
        val (st, sp) = feed(SimPattern.DROWSY, 60_000)
        assertEquals(5, SteeringAnalyzer.corrections(st, 60_000))
        assertEquals(6.11, SpeedAnalyzer.detrendedSd(sp, 60_000)!!.toDouble(), 0.1) // 진폭 9km/h 사인파의 이론값 6.36 에서 추세 제거분만큼 작다
    }

    @Test fun normalPattern_isQuiet() {
        val (st, sp) = feed(SimPattern.NORMAL, 60_000)
        assertEquals(0, SteeringAnalyzer.corrections(st, 60_000))
        assertTrue(SpeedAnalyzer.detrendedSd(sp, 60_000)!! < 1.0f)
    }

    @Test fun drowsyPatternAtThreeAm_reachesStageOneThenTwoWithRecirc() {
        val (st, sp) = feed(SimPattern.DROWSY, 60_000)
        val corr = SteeringAnalyzer.corrections(st, 60_000); val sd = SpeedAnalyzer.detrendedSd(sp, 60_000)
        // 03:00(20) + 90분 주행(8) + 조향 5회(20) + 속도 6.1km/h(10) = 58 -> 1단계(2단계 경계 60 바로 아래)
        val a = RiskEngine.assess(RiskInputs(180, 90 * 60_000L, null, corr, sd), Thresholds.of(0))
        assertEquals(58, a.score); assertEquals(Stage.S1, a.stage)
        // 내기순환 20분이 더해지면 +8 = 66 -> 2단계
        val b = RiskEngine.assess(RiskInputs(180, 90 * 60_000L, 20 * 60_000L, corr, sd), Thresholds.of(0))
        assertEquals(66, b.score); assertEquals(Stage.S2, b.stage)
        // 같은 신호를 낮 시간대(10시)에 주입하면 졸음 패턴 점수만 남는다(조향 20 + 속도 10 + 주행 8 = 38 -> 정상)
        assertEquals(38, RiskEngine.assess(RiskInputs(600, 90 * 60_000L, null, corr, sd), Thresholds.of(0)).score)
    }

    @Test fun patternSamplesAreDeterministicAndPeriodic() {
        assertEquals(SimPatterns.sample(SimPattern.DROWSY, 7_000).steerDeg, SimPatterns.sample(SimPattern.DROWSY, 7_000 + 12_000).steerDeg, 1e-6f) // 조향 12초 주기
        assertEquals(SimPatterns.sample(SimPattern.DROWSY, 3_000).speedKmh, SimPatterns.sample(SimPattern.DROWSY, 3_000 + 20_000).speedKmh, 1e-3f) // 속도 20초 주기
        assertEquals(8f, SimPatterns.sample(SimPattern.DROWSY, 7_000).steerDeg, 1e-6f)
        assertEquals(0f, SimPatterns.sample(SimPattern.DROWSY, 9_000 - 0).steerDeg, 0.5f)
        assertEquals(80f, SimPatterns.sample(SimPattern.DROWSY, 0).speedKmh, 1e-4f)
    }

    // ---------------- 시연 입력 검증 ----------------
    @Test fun simInput_validation() {
        assertEquals(SimPattern.DROWSY, SimInput.pattern("DROWSY")); assertNull(SimInput.pattern("x")); assertTrue(SimInput.isOff("OFF"))
        assertEquals(180, SimInput.minuteOfDay(3, null)); assertEquals(210, SimInput.minuteOfDay(3, 30))
        assertNull(SimInput.minuteOfDay(24, 0)); assertNull(SimInput.minuteOfDay(3, 60)); assertNull(SimInput.minuteOfDay(-1, null)); assertNull(SimInput.minuteOfDay("3", null))
        assertEquals(90, SimInput.minutes(90)); assertNull(SimInput.minutes(-5)); assertNull(SimInput.minutes(24 * 60 + 1)); assertNull(SimInput.minutes(Double.NaN))
        assertEquals(80f, SimInput.speed(80)); assertNull(SimInput.speed(301)); assertNull(SimInput.speed(-1)); assertNull(SimInput.speed(Float.NaN))
        assertEquals(-7.5f, SimInput.steer(-7.5f)); assertNull(SimInput.steer(541))
        assertNull(SimInput.stage(0)); assertEquals(Stage.S2, SimInput.stage(2)); assertNull(SimInput.stage(4))
        assertEquals(Gear.PARK, SimInput.gear("p")); assertEquals(Gear.DRIVE, SimInput.gear("D")); assertNull(SimInput.gear("X"))
    }

    // ---------------- 저장 코덱 ----------------
    private val full = Persisted(
        Settings(voiceReply = false, hvacControl = true, beep = false), Calibration(9, 3),
        listOf(RestRecord(1_700_000_000_000L, 125, 20, false), RestRecord(1_700_000_100_000L, 30, 15, true)),
        QuizStats(4, 3, 3_200L), 45 * 60_000L, 1_700_000_200_000L,
    )

    @Test fun codec_roundTrip() {
        val r = StateCodec.parse(StateCodec.encode(full).toString())
        assertFalse(r.corrupt); assertEquals(full, r.p)
    }

    @Test fun codec_defaultsForEmptyObject_andCorruptForGarbage() {
        val d = StateCodec.parse("{}")
        assertFalse(d.corrupt); assertEquals(Persisted(), d.p)
        assertTrue(StateCodec.parse("{bad").corrupt); assertEquals(Persisted(), StateCodec.parse("not json").p)
    }

    @Test fun codec_clampsOutOfRangeValues_andCapsRests() {
        val o = JSONObject().put("calOffset", 99).put("calCount", -3).put("quizTotal", -1).put("quizPassed", -2).put("quizLastMs", -1).put("drivingMs", -5).put("savedAt", -1)
        val arr = org.json.JSONArray(); repeat(40) { arr.put(JSONObject().put("at", it.toLong()).put("d", 99999).put("r", -4)) }
        o.put("rests", arr)
        val p = StateCodec.parse(o.toString()).p
        assertEquals(15, p.calibration.offset); assertEquals(0, p.calibration.feedbackCount)
        assertEquals(QuizStats(0, 0, null), p.quiz); assertEquals(0L, p.drivingMs); assertEquals(0L, p.savedAtWallMs)
        assertEquals(StateCodec.MAX_RESTS, p.rests.size); assertEquals(10L, p.rests.first().atWallMs) // 오래된 것부터 잘림
        assertEquals(24 * 60, p.rests[0].drivingMin); assertEquals(0, p.rests[0].restMin)
    }

    @Test fun codec_storesNoLocationOrVoiceFields() {
        val keys = StateCodec.encode(full).keys().asSequence().toSet()
        assertTrue("$keys", keys.none { it.contains("lat", true) || it.contains("lon", true) || it.contains("loc", true) || it.contains("voiceText", true) || it.contains("shelter", true) })
    }

    // ---------------- 상태 저장소 ----------------
    private fun store() = StateStore(LocalJsonStore(tmp.newFolder("s")), "wakemate_state")

    @Test fun store_restoreMissing_thenWriteAndRestore() {
        val st = store()
        val first = st.restore(0)
        assertEquals(Persisted(), first.p); assertFalse(first.corrupt || first.readError)
        assertEquals(WriteResult.WROTE, st.writeLatest { full })
        assertEquals(WriteResult.UNCHANGED, st.writeLatest { full })
        assertEquals(full, st.restore(0).p)
    }

    @Test fun store_corruptFileIsQuarantined_andNextWriteRemovesIt() {
        val dir = tmp.newFolder("c"); val ls = LocalJsonStore(dir)
        File(dir, "wakemate_state.json").writeText("{broken")
        val st = StateStore(ls, "wakemate_state")
        val r = st.restore(0)
        assertTrue(r.corrupt && r.quarantined); assertEquals(Persisted(), r.p)
        assertTrue(File(dir, "wakemate_state.json.corrupt").exists())
        assertEquals(WriteResult.WROTE, st.writeLatest { full })
        assertFalse(File(dir, "wakemate_state.json.corrupt").exists())
    }

    @Test fun store_readError_holdsWritesSoOriginalIsNotOverwritten() {
        val dir = tmp.newFolder("e"); File(dir, "wakemate_state.json").mkdirs() // 파일 대신 디렉터리 -> 읽기 오류
        val st = StateStore(LocalJsonStore(dir), "wakemate_state")
        val r = st.restore(0)
        assertTrue(r.readError); assertFalse(st.writable)
        assertEquals(WriteResult.HELD, st.writeLatest { full })
    }

    @Test fun store_clearAll_deletesEverythingThenRewritesLatest() {
        val dir = tmp.newFolder("d"); val st = StateStore(LocalJsonStore(dir), "wakemate_state")
        st.writeLatest { full }
        File(dir, "wakemate_state.json.corrupt").writeText("x")
        assertTrue(st.clearAll { Persisted(settings = full.settings) })
        assertFalse(File(dir, "wakemate_state.json.corrupt").exists())
        val after = st.restore(0).p
        assertEquals(Persisted(settings = full.settings), after)
        assertTrue(after.rests.isEmpty() && after.calibration == Calibration() && after.drivingMs == 0L)
    }
}

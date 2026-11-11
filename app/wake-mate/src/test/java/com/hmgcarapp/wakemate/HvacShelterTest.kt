package com.hmgcarapp.wakemate

import com.hmgcarapp.wakemate.domain.AreaPicker
import com.hmgcarapp.wakemate.domain.HvacCaps
import com.hmgcarapp.wakemate.domain.HvacErrors
import com.hmgcarapp.wakemate.domain.HvacNow
import com.hmgcarapp.wakemate.domain.HvacPlan
import com.hmgcarapp.wakemate.domain.HvacReport
import com.hmgcarapp.wakemate.domain.HvacTargets
import com.hmgcarapp.wakemate.domain.ItemResult
import com.hmgcarapp.wakemate.domain.LatLon
import com.hmgcarapp.wakemate.domain.Shelter
import com.hmgcarapp.wakemate.domain.ShelterRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HvacShelterTest {
    private val caps = HvacCaps(recirc = true, fan = true, temp = true, fanMin = 1, fanMax = 5, tempMin = 16f, tempMax = 32f)

    // ---- HvacPlan ----
    @Test fun plan_recircOff_fanToEightyPercent_tempMinusTwo() {
        assertEquals(HvacTargets(false, 4, 22.0f), HvacPlan.plan(HvacNow(power = true, recirc = true, fan = 2, tempC = 24f), caps))
    }

    @Test fun plan_skipsItemsAlreadyAdequate() {
        assertEquals(HvacTargets(null, null, 22.0f), HvacPlan.plan(HvacNow(recirc = false, fan = 5, tempC = 24f), caps))
        assertEquals(HvacTargets(null, 4, null), HvacPlan.plan(HvacNow(recirc = false, fan = 3, tempC = 16f), caps)) // 이미 최저 온도
    }

    @Test fun plan_unknownRecircIsWrittenOff_unknownTempIsNotTouched() {
        assertEquals(HvacTargets(false, 4, null), HvacPlan.plan(HvacNow(recirc = null, fan = null, tempC = null), caps))
    }

    @Test fun plan_tempRoundsDownToHalfDegreeAndClampsToMin() {
        assertEquals(22.0f, HvacPlan.plan(HvacNow(tempC = 24.3f), caps).tempC)
        assertEquals(16.0f, HvacPlan.plan(HvacNow(tempC = 17.0f), caps).tempC)   // 15 -> 최저 16 으로 보정
        assertEquals(32.0f, HvacPlan.plan(HvacNow(tempC = 35f), caps).tempC)      // 33 -> 최고 32 로 보정
    }

    @Test fun plan_neverWritesUnsupportedKinds() {
        val none = HvacCaps(recirc = false, fan = false, temp = false)
        assertTrue(HvacPlan.plan(HvacNow(recirc = true, fan = 1, tempC = 25f), none).isEmpty)
        assertEquals(HvacTargets(false, null, null), HvacPlan.plan(HvacNow(recirc = true, fan = 1, tempC = 25f), HvacCaps(recirc = true)))
    }

    @Test fun plan_fanTargetWithinCapabilityBounds() {
        assertEquals(1, HvacPlan.plan(HvacNow(fan = null), HvacCaps(fan = true, fanMin = 1, fanMax = 1)).fan)
        assertNull(HvacPlan.plan(HvacNow(fan = 1), HvacCaps(fan = true, fanMin = 1, fanMax = 1)).fan)
        assertEquals(8, HvacPlan.plan(HvacNow(fan = 1), HvacCaps(fan = true, fanMin = 1, fanMax = 10)).fan)
    }

    @Test fun plan_repeatDoesNotLowerTempBelowBaselineMinusTwo() {
        // 첫 적용: 24 -> 22. 반복 때 현재 22, 기준 24 -> 목표 22 이므로 더 쓰지 않는다
        assertEquals(22.0f, HvacPlan.plan(HvacNow(tempC = 24f), caps, baseTempC = 24f).tempC)
        assertNull(HvacPlan.plan(HvacNow(tempC = 22f), caps, baseTempC = 24f).tempC)
        assertEquals(22.0f, HvacPlan.plan(HvacNow(tempC = 26f), caps, baseTempC = 24f).tempC) // 사용자가 올렸으면 기준에서 다시 맞춘다
        assertEquals(16.0f, HvacPlan.plan(HvacNow(tempC = 20f), caps, baseTempC = 17f).tempC)  // 하한 clamp
    }

    @Test fun locationFreshness_fiveMinutes() {
        assertTrue(com.hmgcarapp.wakemate.domain.LocationFreshness.isFresh(1_000, 301_000))
        assertFalse(com.hmgcarapp.wakemate.domain.LocationFreshness.isFresh(1_000, 301_001))
        assertFalse(com.hmgcarapp.wakemate.domain.LocationFreshness.isFresh(null, 0))
        assertFalse(com.hmgcarapp.wakemate.domain.LocationFreshness.isFresh(5_000, 4_000))
    }

    @Test fun drivePersist_excludesSimulatedMinutes() {
        assertEquals(30 * 60_000L, com.hmgcarapp.wakemate.domain.DrivePersist.persistable(120 * 60_000L, 90 * 60_000L))
        assertEquals(0L, com.hmgcarapp.wakemate.domain.DrivePersist.persistable(10 * 60_000L, 90 * 60_000L))
    }

    @Test fun simInfo_activeCoversDirectInjections() {
        assertFalse(com.hmgcarapp.wakemate.app.SimInfo().active)
        assertTrue(com.hmgcarapp.wakemate.app.SimInfo(injected = true).active)
    }

    // ---- HvacErrors / HvacReport ----
    @Test fun errorCode_parsedFromMessageAndCauseChain() {
        assertEquals(2, HvacErrors.code("Failed to set property: error code: 2"))
        assertEquals(5, HvacErrors.code("ERROR CODE=5"))
        assertNull(HvacErrors.code("something else")); assertNull(HvacErrors.code(null))
        assertEquals(2, HvacErrors.codeOf(RuntimeException("outer", IllegalStateException("error code 2"))))
        assertNull(HvacErrors.codeOf(RuntimeException("no code")))
    }

    @Test fun report_honestAboutFailuresAndNeverClaimsUnconfirmedChanges() {
        val planned = HvacTargets(false, 4, 22f)
        assertEquals(
            "외기 순환으로 바꿨어요. 바람 세기는 공조가 꺼져 있어 바꿀 수 없어요",
            HvacReport.summary(listOf(ItemResult("recirc", true, null), ItemResult("fan", false, 2)), planned, connected = true),
        )
        assertEquals(
            "바람 세기는 차량 응답이 늦었어요",
            HvacReport.summary(listOf(ItemResult("fan", false, 4)), HvacTargets(null, 4, null), true),
        )
        // 응답을 받지 못했으면 '바꿨어요'라고 말하지 않는다
        val noAnswer = HvacReport.summary(emptyList(), planned, connected = true)
        assertEquals("공조 응답을 받지 못했어요. 창문을 열어 환기해 주세요", noAnswer)
        assertEquals("공조는 이미 환기에 알맞은 상태예요", HvacReport.summary(emptyList(), HvacTargets(), true))
        assertTrue(HvacReport.summary(emptyList(), planned, connected = false).contains("연결하지 못했거나"))
    }

    @Test fun report_allSuccessListsEachChange() {
        val r = HvacReport.summary(listOf(ItemResult("recirc", true, null), ItemResult("fan", true, null), ItemResult("temp", true, null)), HvacTargets(false, 4, 22f), true)
        assertEquals("외기 순환으로 바꿨어요, 바람 세기를 올렸어요, 설정 온도를 낮췄어요", r)
        assertTrue(HvacReport.hasFailure(listOf(ItemResult("fan", false, 2))))
    }

    // ---- AreaPicker ----
    @Test fun areaPicker_prefersDriverOnly_thenSmallestContainingDriver() {
        val all5 = listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_CENTER", "ROW_2_RIGHT")
        assertEquals(1, AreaPicker.pickDriverArea(listOf(all5, listOf("ROW_1_LEFT"))))
        assertEquals(1, AreaPicker.pickDriverArea(listOf(all5, listOf("ROW_1_LEFT", "ROW_1_RIGHT"))))
        assertNull(AreaPicker.pickDriverArea(listOf(listOf("ROW_1_RIGHT"), listOf("ROW_2_LEFT"))))
        assertNull(AreaPicker.pickDriverArea(emptyList()))
    }

    // ---- Shelter ----
    private val json = """
        {"notice":"n","sample":true,"items":[
          {"졸음쉼터명":"가","도로노선명":"A로","도로노선방향":"상행","위도":"37.50","경도":"127.00","주차가능면수":"10","화장실유무":"Y"},
          {"졸음쉼터명":"","위도":"37.5","경도":"127.0"},
          {"졸음쉼터명":"좌표오류","위도":"abc","경도":"127.0"},
          {"졸음쉼터명":"범위밖","위도":"91","경도":"127.0"},
          {"졸음쉼터명":"나","위도":"37.60","경도":"127.00","화장실유무":"N"}
        ]}
    """.trimIndent()

    @Test fun parse_skipsMalformedEntries_keepsStandardColumns() {
        val ds = ShelterRepo.parse(json)
        assertEquals(listOf("가", "나"), ds.shelters.map { it.name })
        assertEquals(listOf("S000", "S004"), ds.shelters.map { it.id })
        val a = ds.shelters[0]
        assertEquals(Triple("A로", "상행", 10), Triple(a.roadName, a.direction, a.parkingSlots))
        assertEquals(true, a.restroom); assertEquals(false, ds.shelters[1].restroom); assertNull(ds.shelters[1].parkingSlots)
        assertTrue(ds.sample)
    }

    @Test fun haversine_knownDistances() {
        assertEquals(0.0, ShelterRepo.haversineKm(LatLon(37.5, 127.0), LatLon(37.5, 127.0)), 1e-9)
        assertEquals(111.19, ShelterRepo.haversineKm(LatLon(37.0, 127.0), LatLon(38.0, 127.0)), 0.1)  // 위도 1도
        assertEquals(88.2, ShelterRepo.haversineKm(LatLon(37.5, 127.0), LatLon(37.5, 128.0)), 0.3)   // 경도 1도(37.5도)
    }

    private fun sh(id: String, lat: Double, lon: Double) = Shelter(id, id, "", "", LatLon(lat, lon), null, null)

    @Test fun nearest_sortsByDistance_tieBreaksById_limitsAndFiltersByMax() {
        val o = LatLon(37.5, 127.0)
        val list = listOf(sh("c", 37.52, 127.0), sh("b", 37.51, 127.0), sh("a", 37.51, 127.0), sh("far", 39.0, 127.0))
        val r = ShelterRepo.nearest(o, list, limit = 3)
        assertEquals(listOf("a", "b", "c"), r.map { it.shelter.id })   // a,b 는 같은 위치(같은 거리): id 순
        assertEquals(1.11, r[0].distanceKm, 0.01)
        assertEquals(listOf("a"), ShelterRepo.nearest(o, list, limit = 1).map { it.shelter.id })
        assertTrue(ShelterRepo.nearest(o, listOf(sh("far", 39.0, 127.0)), 3, maxKm = 100.0).isEmpty())
    }

    @Test fun bundledSampleAsset_parsesAndIsLabeledAsFictional() {
        val f = File("src/main/assets/rest_shelters_sample.json")
        assertTrue("assets 파일을 찾을 수 없음: ${f.absolutePath}", f.exists())
        val ds = ShelterRepo.parse(f.readText())
        assertEquals(5, ds.shelters.size)
        assertTrue(ds.sample)
        assertTrue(ds.notice.contains("실제 졸음쉼터가 아니") && ds.notice.contains("data.go.kr"))
        assertTrue(ds.shelters.all { it.name.startsWith("가상") })
    }
}

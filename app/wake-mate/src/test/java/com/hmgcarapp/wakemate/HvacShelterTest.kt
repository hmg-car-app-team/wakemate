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

    @Test fun planVent_onlyOutsideAir() {
        val caps = HvacCaps(true, true, true, 1, 5, 16f, 32f)
        assertEquals(HvacTargets(recirc = false), HvacPlan.planVent(HvacNow(true, true, 2, 24f), caps))
        assertTrue(HvacPlan.planVent(HvacNow(true, false, 2, 24f), caps).isEmpty)
        assertTrue(HvacPlan.planVent(HvacNow(), HvacCaps()).isEmpty)
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

    @Test fun nearest_forwardFilter_boundariesAndNoHeadingFallback() {
        val o = LatLon(37.5, 127.0)
        val north = sh("n", 37.55, 127.0)      // 방위 ≈ 0°
        val south = sh("s", 37.45, 127.0)      // 방위 ≈ 180°
        val all = listOf(north, south)
        assertEquals(listOf("n", "s"), ShelterRepo.nearest(o, all, 3).map { it.shelter.id })                       // 방향 없음: 필터 없음
        assertEquals(listOf("n", "s"), ShelterRepo.nearest(o, all, 3, headingDeg = null).map { it.shelter.id })
        assertEquals(listOf("n"), ShelterRepo.nearest(o, all, 3, headingDeg = 0f).map { it.shelter.id })           // 북진: 남쪽 후방 제외
        assertEquals(listOf("s"), ShelterRepo.nearest(o, all, 3, headingDeg = 180f).map { it.shelter.id })
        assertEquals(listOf("n"), ShelterRepo.nearest(o, all, 3, headingDeg = 360f).map { it.shelter.id })         // 360 = 북
        // 북쪽 시설: heading 100 -> 상대 100°(경계, 포함) / 101 -> 제외 / 260 -> 상대 100°(포함) / 259 -> 제외(래핑)
        assertTrue(ShelterRepo.isAhead(o, north.pos, 99.9f))
        assertFalse(ShelterRepo.isAhead(o, north.pos, 101f))
        assertTrue(ShelterRepo.isAhead(o, north.pos, 260.1f))
        assertFalse(ShelterRepo.isAhead(o, north.pos, 259f))
        // 잘못된 방향값은 방향 정보 없음으로 취급
        for (bad in listOf(Float.NaN, -1f, 361f, Float.POSITIVE_INFINITY)) assertTrue(ShelterRepo.isAhead(o, south.pos, bad))
        // 아주 가까운 시설(0.05km 미만)은 방위가 무의미해 유지
        assertTrue(ShelterRepo.isAhead(o, LatLon(37.50001, 127.0), 180f))
        // 필터 후에도 거리순·limit 유지, spokenName/directionLabel 은 그대로
        val mix = listOf(sh("s1", 37.49, 127.0), sh("n2", 37.53, 127.0), sh("n1", 37.51, 127.0))
        assertEquals(listOf("n1", "n2"), ShelterRepo.nearest(o, mix, 3, headingDeg = 10f).map { it.shelter.id })
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

    @Test fun drivingRestrictedIsRecognizedFromSdkExceptionName() {
        assertEquals(HvacErrors.DRIVING_RESTRICTED, HvacErrors.code("ERROR_DRIVING_RESTRICTED"))
        assertEquals(HvacErrors.DRIVING_RESTRICTED, HvacErrors.codeOf(RuntimeException("wrap", Exception("ERROR_DRIVING_RESTRICTED"))))
        assertEquals(50001, HvacErrors.code("error code: 50001"))
        assertEquals(2, HvacErrors.code("error code: 2"))
    }

    @Test fun drivingRestrictedReportIsHonestAndKeepsOtherPaths() {
        val planned = HvacTargets(recirc = false, fan = 8)
        val r = listOf(ItemResult("recirc", false, HvacErrors.DRIVING_RESTRICTED), ItemResult("fan", true, null))
        val text = HvacReport.summary(r, planned, connected = true)
        assertTrue(text.contains("주행 중") && text.contains("외기 순환은"))
        assertTrue(text.contains("바람 세기를 올렸어요"))
        assertTrue(HvacReport.hasFailure(r))
        assertEquals("알 수 없는 오류예요", HvacErrors.reason(null))
    }
}

/** 실데이터 스냅샷(assets) 파싱·폴백·선택 로직. */
class RealShelterDataTest {
    private fun asset(n: String) = File("src/main/assets/$n").also { assertTrue("없음: ${it.absolutePath}", it.exists()) }.readText()
    private val shelters get() = ShelterRepo.parse(asset("rest_shelters.json"), "S")
    private val rests get() = ShelterRepo.parse(asset("rest_areas.json"), "R")

    @Test fun realAssets_parseAllRows_andAreNotSample() {
        val s = shelters; val r = rests
        assertEquals(253, s.shelters.size); assertEquals(215, r.shelters.size)
        assertTrue(!s.sample && !r.sample)
        assertEquals("2026-02-25", s.asOf); assertEquals("2026-11-11", s.collectedAt); assertEquals("2026-09-17", r.asOf)
        assertEquals("2026-02-25", (s + r).asOf); assertEquals("2026-02-25", (r + s).asOf) // 병합은 오래된 기준일
        assertEquals("미상", (s + r.copy(asOf = "미상")).asOf)
        assertTrue(s.shelters.none { it.isRestArea } && r.shelters.all { it.isRestArea })
        assertEquals(468, (s + r).shelters.map { it.id }.toSet().size) // S/R 접두어로 id 중복 없음
        assertEquals("휴게소", r.shelters[0].kindLabel); assertEquals("졸음쉼터", s.shelters[0].kindLabel)
        assertTrue(r.shelters.all { it.pos.lat in 33.0..39.0 && it.pos.lon in 124.0..132.0 })
    }

    @Test fun nearest_onRealData_findsTheCoLocatedItemFirst_andMixesBothKinds() {
        val all = shelters + rests
        for (t in listOf(all.shelters[10], all.shelters[300])) {
            val n = ShelterRepo.nearest(t.pos, all.shelters, 3)
            assertEquals(t.name, n[0].shelter.name); assertEquals(0.0, n[0].distanceKm, 1e-6)
            assertTrue(n.zipWithNext().all { (a, b) -> a.distanceKm <= b.distanceKm })
        }
        val seoul = ShelterRepo.nearest(LatLon(37.5665, 126.9780), all.shelters, 5)
        assertEquals(5, seoul.size); assertTrue(seoul.all { it.distanceKm < 100.0 })
        assertTrue(ShelterRepo.nearest(LatLon(33.5, 126.5), listOf(all.shelters.first { it.pos.lat > 37 }), 1, 100.0).isEmpty())
    }

    @Test fun parse_restAreaNameColumn_unknownColumnsIgnored_badRowsSkipped_oldSampleFormatStillWorks() {
        val j = """{"items":[
          {"휴게소명":"가(나)","위도":"37.5","경도":"127.0","도로노선방향":"상행","전기차충전소유무":"Y","새열":"x"},
          {"휴게소명":"좌표없음"},{"휴게소명":"범위밖","위도":"10","경도":"127"},{"위도":"37.5","경도":"127.0"},
          {"졸음쉼터명":"옛형식","위도":"37.5","경도":"127.0"}],"_meta":{"collectedAt":"2026-01-02"}}"""
        val d = ShelterRepo.parse(j, "R")
        assertEquals(listOf("가(나)", "옛형식"), d.shelters.map { it.name })
        assertEquals(listOf(true, false), d.shelters.map { it.isRestArea })
        assertEquals("R000", d.shelters[0].id); assertEquals("미상", d.asOf); assertEquals("2026-01-02", d.collectedAt); assertTrue(d.sample) // sample 열이 없으면 샘플로 간주(안전 쪽)
    }

    @Test fun loader_mergesBoth_fallsBackToSample_onMissingOrBroken() {
        fun open(files: Map<String, String>) = { n: String -> (files[n] ?: throw java.io.FileNotFoundException(n)).byteInputStream() }
        val real = mapOf("rest_shelters.json" to asset("rest_shelters.json"), "rest_areas.json" to asset("rest_areas.json"), "rest_shelters_sample.json" to asset("rest_shelters_sample.json"))
        assertEquals(468, com.hmgcarapp.wakemate.app.AppController.loadShelterDataset(open(real))!!.shelters.size)
        assertEquals(253, com.hmgcarapp.wakemate.app.AppController.loadShelterDataset(open(real - "rest_areas.json"))!!.shelters.size)
        val s1 = com.hmgcarapp.wakemate.app.AppController.loadShelterDataset(open(real - "rest_shelters.json"))!!
        assertTrue(s1.sample && s1.shelters.size == 5)
        val s2 = com.hmgcarapp.wakemate.app.AppController.loadShelterDataset(open(real + ("rest_shelters.json" to "{깨짐")))!!
        assertTrue(s2.sample && s2.shelters.size == 5)
        assertNull(com.hmgcarapp.wakemate.app.AppController.loadShelterDataset(open(emptyMap())))
    }

    @Test fun spokenName_resolvesParenthesisAsDestination_andLineShowsDirection() {
        val inch = rests.shelters.first { it.name == "강릉(인천)" }
        assertEquals("강릉 인천 방면", inch.spokenName); assertEquals("강릉(인천)", inch.name)
        assertEquals("고삼호수", rests.shelters.first { it.name == "고삼호수" }.spokenName)
        val t = com.hmgcarapp.wakemate.domain.WakeText.s3(85, com.hmgcarapp.wakemate.domain.NearShelter(inch, 3.0), false, null, false)
        assertTrue(t, t.contains("강릉 인천 방면, 상행, 약 3.0킬로미터예요"))
        val both = rests.shelters.first { it.direction == "양방향" }
        assertTrue(com.hmgcarapp.wakemate.domain.WakeText.s3(85, com.hmgcarapp.wakemate.domain.NearShelter(both, 1.0), false, null, false).contains(", 양방향, 약"))
        val none = rests.shelters.first { it.direction.isEmpty() }
        assertTrue(!com.hmgcarapp.wakemate.domain.WakeText.s3(85, com.hmgcarapp.wakemate.domain.NearShelter(none, 1.0), false, null, false).contains(", ,"))
    }

    @Test fun voice_realData_saysSnapshotNotRealtime_andKind() {
        val n = com.hmgcarapp.wakemate.domain.NearShelter(rests.shelters[0], 2.0)
        val t = com.hmgcarapp.wakemate.domain.WakeText.s3(85, n, false, null, false)
        assertTrue(t, t.contains("가까운 휴게소, 강릉 강릉 방면, 하행, 약 2.0킬로미터예요.") && !t.contains("반영하지 않으니") && !t.contains("공공데이터 스냅샷") && !t.contains("실시간 정보") && !t.contains("시연용") && !t.contains("샘플"))
    }

    @Test fun voice_headingApplied_saysAheadBasis_notIgnored() {
        val n = com.hmgcarapp.wakemate.domain.NearShelter(rests.shelters[0], 2.0)
        val t = com.hmgcarapp.wakemate.domain.WakeText.s3(85, n, false, null, false, headingApplied = true)
        assertTrue(t, t.contains("진행 방향 앞쪽 시설 기준이에요. 도로 방향은 직접 확인하세요.") && !t.contains("반영하지 않으니") && !t.contains("공공데이터 스냅샷"))
    }

    @Test fun usableHeading_onlyWhenDrivingFiniteNonZero() {
        assertNull(ShelterRepo.usableHeading(90f, 0f))            // 정차
        assertNull(ShelterRepo.usableHeading(90f, null))          // 속도 미상
        assertNull(ShelterRepo.usableHeading(0f, 80f))            // 정확히 0.0 은 방위 미확인과 구분 불가
        assertNull(ShelterRepo.usableHeading(Float.NaN, 80f))
        assertNull(ShelterRepo.usableHeading(null, 80f))
        assertNull(ShelterRepo.usableHeading(361f, 80f)); assertNull(ShelterRepo.usableHeading(-1f, 80f))
        assertNull(ShelterRepo.usableHeading(90f, 9.99f))         // 속도 임계 바로 아래
        assertEquals(90f, ShelterRepo.usableHeading(90f, 10f)!!, 0f) // 임계 경계 포함
        assertEquals(360f, ShelterRepo.usableHeading(360f, 80f)!!, 0f)
        assertEquals(0.5f, ShelterRepo.usableHeading(0.5f, 80f)!!, 0f)
    }
}

package com.hmgcarapp.wakemate.domain

import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

/** 졸음쉼터·휴게소(공공데이터포털 한국도로공사 데이터 형식의 일부 필드). [isRestArea] 가 true 면 휴게소, 아니면 졸음쉼터. */
data class Shelter(
    val id: String, val name: String, val roadName: String, val direction: String,
    val pos: LatLon, val parkingSlots: Int?, val restroom: Boolean?,
    val isRestArea: Boolean = false,
) {
    /** 음성·화면 표기용 종류. */
    val kindLabel: String get() = if (isRestArea) "휴게소" else "졸음쉼터"

    /**
     * TTS 용 이름. 휴게소 CSV 는 같은 이름의 상·하행 쌍을 '이름(종점 방면)'으로 구분한다(예: 강릉(강릉)=하행, 강릉(인천)=상행).
     * 괄호를 그대로 읽으면 어색하므로 '(인천)' 을 '인천 방면' 으로 풀어 읽는다. 화면 표기는 [name] 그대로.
     */
    val spokenName: String get() = Regex("""\s*\(([^)]+)\)\s*$""").replace(name) { " ${it.groupValues[1]} 방면" }

    /** 음성·배너용 방향 표기(비어 있으면 빈 문자열, '양방향' 은 그대로, 기점/종점 '/' 는 공백). */
    val directionLabel: String get() = direction.trim().replace("/", " ")
}

/**
 * [asOf] 는 데이터 기준일(`_meta.dataDate`; 없으면 [UNKNOWN_DATE]), [collectedAt] 은 스냅샷 수집일(`_meta.collectedAt`; 없으면 빈 문자열).
 * 병합하면 기준일은 오래된 쪽(둘 중 하나라도 미상이면 미상)을 쓴다.
 */
data class ShelterDataset(val notice: String, val sample: Boolean, val shelters: List<Shelter>, val asOf: String = UNKNOWN_DATE, val collectedAt: String = "") {
    operator fun plus(o: ShelterDataset) = copy(
        shelters = shelters + o.shelters,
        asOf = if (asOf == UNKNOWN_DATE || o.asOf == UNKNOWN_DATE) UNKNOWN_DATE else minOf(asOf, o.asOf),
        collectedAt = maxOf(collectedAt, o.collectedAt),
    )
}

const val UNKNOWN_DATE = "미상"

data class NearShelter(val shelter: Shelter, val distanceKm: Double) {
    /** 쉼터 한 줄(배너·주행 화면 공통): 종류 이름 방향. 가상 샘플 데이터면 이름만. 출처 표기는 붙이지 않는다. */
    fun line(sample: Boolean): String =
        if (sample) shelter.name
        else listOf(shelter.kindLabel, shelter.name, shelter.directionLabel).filter { it.isNotEmpty() }.joinToString(" ")
}

object ShelterRepo {
    /**
     * assets JSON 파싱. 공공데이터포털 '전국졸음쉼터표준데이터'의 열 이름(졸음쉼터명 또는 휴게소명, 도로노선명, 도로노선방향, 위도, 경도, 주차가능면수, 화장실유무)을 그대로 쓴다.
     * 형식이 어긋난 항목(이름 없음, 좌표가 숫자가 아니거나 국내 범위 밖)은 건너뛴다(앱이 죽지 않게). id 는 [idPrefix]+파일 순서로 만든다(휴게소 파일은 "R"). 모르는 열·`_meta` 는 무시한다.
     */
    fun parse(json: String, idPrefix: String = "S"): ShelterDataset {
        val o = JSONObject(json)
        val arr = o.optJSONArray("items")
        val list = ArrayList<Shelter>()
        if (arr != null) for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val lat = e.optString("위도").toDoubleOrNull() ?: Double.NaN
            val lon = e.optString("경도").toDoubleOrNull() ?: Double.NaN
            val shelterName = e.optString("졸음쉼터명", "")
            val restName = e.optString("휴게소명", "")
            val isRest = shelterName.isEmpty() && restName.isNotEmpty()
            val name = if (isRest) restName else shelterName
            if (name.isEmpty() || !lat.isFinite() || !lon.isFinite() || lat !in 33.0..39.0 || lon !in 124.0..132.0) continue
            list += Shelter(
                idPrefix + "%03d".format(i), name, e.optString("도로노선명", ""), e.optString("도로노선방향", ""), LatLon(lat, lon),
                e.optString("주차가능면수").toIntOrNull(),
                when (e.optString("화장실유무")) { "Y" -> true; "N" -> false; else -> null },
                isRest,
            )
        }
        val meta = o.optJSONObject("_meta")
        return ShelterDataset(o.optString("notice", ""), o.optBoolean("sample", true), list, meta?.optString("dataDate", "").orEmpty().ifEmpty { UNKNOWN_DATE }, meta?.optString("collectedAt", "").orEmpty())
    }

    fun haversineKm(a: LatLon, b: LatLon): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.lat - a.lat); val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(h))
    }

    /** 전방 필터 기준: 진행 방향 대비 상대 방위가 이 값(도)을 넘으면 후방으로 보고 제외한다(경계값 포함은 유지). */
    const val FORWARD_HALF_ANGLE_DEG = 100.0

    /** 초기 방위(도, 0=북, 시계방향 0..360). */
    fun bearingDeg(a: LatLon, b: LatLon): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat); val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(p2); val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * 진행 방향 전방 여부. [headingDeg] 가 null·비유한·범위(0..360) 밖이면 방향 정보 없음으로 보고 항상 true(기존 동작).
     * 거의 같은 지점(0.05km 미만)은 방위가 무의미하므로 true.
     */
    fun isAhead(origin: LatLon, target: LatLon, headingDeg: Float?, distanceKm: Double = haversineKm(origin, target)): Boolean {
        if (headingDeg == null || !headingDeg.isFinite() || headingDeg < 0f || headingDeg > 360f) return true
        if (distanceKm < 0.05) return true
        val diff = kotlin.math.abs(((bearingDeg(origin, target) - headingDeg + 540.0) % 360.0) - 180.0)
        return diff <= FORWARD_HALF_ANGLE_DEG
    }

    /** 이 속도(km/h) 이상일 때만 heading 을 전방 필터에 쓴다. */
    const val HEADING_MIN_SPEED_KMH = 10f

    /**
     * 전방 필터에 쓸 수 있는 heading 만 돌려준다. 플랫폼은 방위를 모를 때 0.0(북)을 주므로 정확히 0.0 은 모호해 쓰지 않는다.
     * 주행 중(속도 [HEADING_MIN_SPEED_KMH] 이상)이고 유한값이며 0 초과 360 이하일 때만 유효, 아니면 null(필터 없음).
     */
    fun usableHeading(heading: Float?, speedKmh: Float?): Float? =
        heading?.takeIf { it.isFinite() && it > 0f && it <= 360f && speedKmh != null && speedKmh.isFinite() && speedKmh >= HEADING_MIN_SPEED_KMH }

    /** 가까운 순 [limit] 개(직선거리 근사; 실제 도로 거리는 반영하지 않는다. [headingDeg] 가 있으면 상대 방위 ±100° 밖 후방 시설은 제외, 없으면 필터 없음). 동거리면 id 순. */
    fun nearest(origin: LatLon, shelters: List<Shelter>, limit: Int = 3, maxKm: Double = 100.0, headingDeg: Float? = null): List<NearShelter> =
        shelters.map { NearShelter(it, haversineKm(origin, it.pos)) }.filter { it.distanceKm <= maxKm }
            .filter { isAhead(origin, it.shelter.pos, headingDeg, it.distanceKm) }
            .sortedWith(compareBy({ it.distanceKm }, { it.shelter.id })).take(limit)
}

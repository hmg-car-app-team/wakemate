package com.hmgcarapp.wakemate.domain

import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

/** 졸음쉼터(공공데이터포털 '졸음쉼터 표준데이터' 형식의 일부 필드). */
data class Shelter(
    val id: String, val name: String, val roadName: String, val direction: String,
    val pos: LatLon, val parkingSlots: Int?, val restroom: Boolean?,
)

data class ShelterDataset(val notice: String, val sample: Boolean, val shelters: List<Shelter>)

data class NearShelter(val shelter: Shelter, val distanceKm: Double)

object ShelterRepo {
    /**
     * assets JSON 파싱. 공공데이터포털 '전국졸음쉼터표준데이터'의 열 이름(졸음쉼터명, 도로노선명, 도로노선방향, 위도, 경도, 주차가능면수, 화장실유무)을 그대로 쓴다.
     * 형식이 어긋난 항목(이름 없음, 좌표가 숫자가 아니거나 국내 범위 밖)은 건너뛴다(앱이 죽지 않게). id 는 파일 순서로 만든다.
     */
    fun parse(json: String): ShelterDataset {
        val o = JSONObject(json)
        val arr = o.optJSONArray("items")
        val list = ArrayList<Shelter>()
        if (arr != null) for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val lat = e.optString("위도").toDoubleOrNull() ?: Double.NaN
            val lon = e.optString("경도").toDoubleOrNull() ?: Double.NaN
            val name = e.optString("졸음쉼터명", "")
            if (name.isEmpty() || !lat.isFinite() || !lon.isFinite() || lat !in 33.0..39.0 || lon !in 124.0..132.0) continue
            list += Shelter(
                "S%03d".format(i), name, e.optString("도로노선명", ""), e.optString("도로노선방향", ""), LatLon(lat, lon),
                e.optString("주차가능면수").toIntOrNull(),
                when (e.optString("화장실유무")) { "Y" -> true; "N" -> false; else -> null },
            )
        }
        return ShelterDataset(o.optString("notice", ""), o.optBoolean("sample", true), list)
    }

    fun haversineKm(a: LatLon, b: LatLon): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.lat - a.lat); val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(h))
    }

    /** 가까운 순 [limit] 개(직선거리 근사; 실제 도로 거리·진행 방향은 반영하지 않는다). 동거리면 id 순. */
    fun nearest(origin: LatLon, shelters: List<Shelter>, limit: Int = 3, maxKm: Double = 100.0): List<NearShelter> =
        shelters.map { NearShelter(it, haversineKm(origin, it.pos)) }.filter { it.distanceKm <= maxKm }
            .sortedWith(compareBy({ it.distanceKm }, { it.shelter.id })).take(limit)
}

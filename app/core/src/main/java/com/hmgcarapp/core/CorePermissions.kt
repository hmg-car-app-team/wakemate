package com.hmgcarapp.core

import android.app.Activity
import android.content.pm.PackageManager
import com.hmgcarapp.core.log.HmgLog

/**
 * 런타임 권한 (에뮬레이터 실측: 매니페스트 선언만으론 부족 - 아래는 install 직후 granted=false 이며 requestPermissions 로 요청해야 한다).
 *  - android.car.permission.CAR_SPEED / CAR_ENERGY, pleos.car.permission.FUSED_LOCATION : 요청 시 시스템 다이얼로그 없이 승인됨(probe 실측)
 *  - ACCESS_FINE/COARSE_LOCATION : "앱 사용 중에만 허용" 다이얼로그 발생
 * 나머지(CAR_EXTERIOR_ENVIRONMENT, CAR_POWERTRAIN, CAR_INFO, pleos.car.permission.CAR_DRIVE/CLIMATE/ENERGY/INFO, TTS_SERVICE, STT_SERVICE)는 설치 시 자동 grant.
 * 포그라운드 서비스를 시작하기 전에 Activity 에서 반드시 먼저 호출할 것.
 */
object CorePermissions {
    val RUNTIME = arrayOf(
        "android.car.permission.CAR_SPEED",
        "android.car.permission.CAR_ENERGY",
        "pleos.car.permission.FUSED_LOCATION",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.POST_NOTIFICATIONS",
    )

    fun missing(a: Activity): List<String> = RUNTIME.filter { a.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    /** 미승인 항목만 요청. 요청한 개수를 반환. */
    fun requestMissing(a: Activity, requestCode: Int = 1): Int {
        val m = missing(a)
        HmgLog.i("Permissions", "미승인: $m")
        if (m.isNotEmpty()) a.requestPermissions(m.toTypedArray(), requestCode)
        return m.size
    }
}

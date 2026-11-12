package com.hmgcarapp.wakemate

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.pleos.designsystem.restriction.PleosUxRestrictionsProvider
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme
import com.hmgcarapp.wakemate.app.AppController
import com.hmgcarapp.wakemate.app.WakeRuntime
import com.hmgcarapp.wakemate.ui.WakeMateApp

class MainActivity : ComponentActivity() {
    private var controller: AppController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HmgLog.i("wakemate", "onCreate")
        // 런타임 권한 중 이 앱이 매니페스트에 선언한 것만 요청한다(차속·위치).
        val declared = runCatching {
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toSet().orEmpty()
        }.getOrDefault(emptySet())
        val wanted = listOf("android.car.permission.CAR_SPEED", "pleos.car.permission.FUSED_LOCATION", Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        val toRequest = wanted.filter { it in declared && checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (toRequest.isNotEmpty()) requestPermissions(toRequest.toTypedArray(), 1)
        WakeRuntime.activityCreated()
        val c = WakeRuntime.acquire(this)
        controller = c
        setContent {
            PleosTheme {
                PleosUxRestrictionsProvider { WakeMateApp(c) }
            }
        }
    }

    override fun onStart() { super.onStart(); controller?.setScreenVisible(true); controller?.onAppStart() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        controller?.onAppStart()
    }

    override fun onStop() { controller?.setScreenVisible(false); controller?.saveNow(); super.onStop() }

    override fun onDestroy() { controller = null; WakeRuntime.activityDestroyed(); super.onDestroy() } // 서비스가 없을 때만 런타임이 컨트롤러를 해제한다
}

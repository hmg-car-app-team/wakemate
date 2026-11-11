package com.hmgcarapp.wakemate

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hmgcarapp.core.CarSignals
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.pleos.designsystem.restriction.PleosUxRestrictionsProvider
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme
import com.hmgcarapp.wakemate.app.AppController
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
        val wanted = listOf("android.car.permission.CAR_SPEED", "pleos.car.permission.FUSED_LOCATION", Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val toRequest = wanted.filter { it in declared && checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (toRequest.isNotEmpty()) requestPermissions(toRequest.toTypedArray(), 1)
        val c = AppController(this, CarSignals.create(this))
        controller = c
        setContent {
            PleosTheme {
                PleosUxRestrictionsProvider { WakeMateApp(c) }
            }
        }
    }

    override fun onStart() { super.onStart(); controller?.onAppStart() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        controller?.onAppStart()
    }

    override fun onStop() { controller?.saveNow(); super.onStop() }

    override fun onDestroy() { controller?.release(); controller = null; super.onDestroy() }
}

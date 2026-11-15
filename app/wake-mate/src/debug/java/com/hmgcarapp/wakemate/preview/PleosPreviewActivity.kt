package com.hmgcarapp.wakemate.preview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hmgcarapp.pleos.designsystem.component.PleosAmbientEdgeGlow
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme
import com.hmgcarapp.wakemate.R
import com.hmgcarapp.wakemate.app.AppController
import com.hmgcarapp.wakemate.app.WakeRuntime
import com.hmgcarapp.wakemate.ui.WakeAlertCard
import com.hmgcarapp.wakemate.ui.activeStageOf

/**
 * 시연 전용(debug 빌드만). Pleos 홈 화면 이미지를 배경으로 깔고, 그 위에 앱의 실제 컴포넌트
 * (가장자리 단계 색 빛 + 반투명 경고 카드)를 얹는다. 카드·빛은 앱의 실제 상태(위험지수·단계·쉼터·작동 장치)를 따른다.
 * 시연 신호는 SimReceiver(adb broadcast)로 넣는다. 실행: adb shell am start -n com.hmgcarapp.wakemate/.preview.PleosPreviewActivity
 */
class PleosPreviewActivity : ComponentActivity() {
    private var controller: AppController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        WakeRuntime.activityCreated()
        val c = WakeRuntime.acquire(this)
        controller = c
        // 배경이 라이트 테마 화면이라 카드도 라이트로 맞춘다
        setContent { PleosTheme(darkTheme = false) { PleosHomePreview(c) } }
    }

    override fun onStart() { super.onStart(); controller?.setScreenVisible(true); controller?.onAppStart() }

    override fun onStop() { controller?.setScreenVisible(false); controller?.saveNow(); super.onStop() }

    override fun onDestroy() { controller = null; WakeRuntime.activityDestroyed(); super.onDestroy() }
}

/** 배경 이미지 안 왼쪽 주행 섹션의 위치(이미지 대비 비율): 가로 0~33.5%, 세로 7~91%. */
private const val DRIVE_SECTION_WIDTH = 0.335f
private const val DRIVE_SECTION_TOP = 0.07f
private const val DRIVE_SECTION_HEIGHT = 0.84f

@Composable
private fun PleosHomePreview(c: AppController) {
    val s by c.state.collectAsState()
    val stage = activeStageOf(s)
    val bg: ImageBitmap = ImageBitmap.imageResource(R.drawable.pleos_home_light)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Image(
            bitmap = bg,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
            filterQuality = FilterQuality.High,
        )
        // 화면 가장자리(베젤)부터 번지는 단계 색 빛: 배경 위, 카드 아래
        PleosAmbientEdgeGlow(stageLevel = if (s.loading) 0 else stage.n, modifier = Modifier.fillMaxSize()) { }
        // 왼쪽 주행 섹션 안에 반투명 경고 카드
        Box(
            Modifier
                .offset(y = maxHeight * DRIVE_SECTION_TOP)
                .width(maxWidth * DRIVE_SECTION_WIDTH)
                .height(maxHeight * DRIVE_SECTION_HEIGHT),
        ) {
            WakeAlertCard(s, Modifier.align(Alignment.Center).fillMaxWidth(0.9f))
        }
    }
}

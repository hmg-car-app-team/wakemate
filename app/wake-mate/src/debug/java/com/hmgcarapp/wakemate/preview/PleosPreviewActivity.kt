package com.hmgcarapp.wakemate.preview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hmgcarapp.core.drive.Gear
import com.hmgcarapp.core.location.GeoPoint
import com.hmgcarapp.pleos.designsystem.component.PleosAmbientEdgeGlow
import com.hmgcarapp.pleos.designsystem.theme.AstaSans
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme
import com.hmgcarapp.wakemate.R
import com.hmgcarapp.wakemate.app.AppController
import com.hmgcarapp.wakemate.app.UiState
import com.hmgcarapp.wakemate.app.WakeRuntime
import com.hmgcarapp.wakemate.domain.SimPattern
import com.hmgcarapp.wakemate.ui.WakeAlertCard
import com.hmgcarapp.wakemate.ui.activeStageOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.Locale
import kotlin.math.abs

/**
 * 시연 전용(debug 빌드만). Pleos 홈 화면 이미지를 배경으로 깔고, 그 위에 앱의 실제 컴포넌트
 * (가장자리 단계 색 빛 + 반투명 경고 카드)와 차량 응답(차속·운전석 온도·풍량·시트 통풍 단계)을 얹는다. 모두 앱의 실제 상태를 따른다.
 *
 * 앱 목록의 '깨워드림 시연'으로 열면 시연 시나리오가 자동으로 돈다(약 2분 30초):
 * 가상 차량을 켜고 새벽 3시·연속 주행 60분에서 졸음 패턴으로 달린다 → 약 25초: 주의(음성)
 * → 75초에 연속 주행 120분: 경고(공조·시트 통풍·핸들 열선·무드등) → 145초에 180분·내기순환 20분: 위험(가까운 쉼터).
 * 깨우기는 최소 60초 간격이라 단계마다 약 1분씩 보인다.
 * 자동 시연 없이 열기: adb shell am start -n com.hmgcarapp.wakemate/.preview.PleosPreviewActivity --ez autoplay false
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
        val autoplay = savedInstanceState == null && intent.getBooleanExtra(EXTRA_AUTOPLAY, true)
        // 배경이 라이트 테마 화면이라 카드도 라이트로 맞춘다
        setContent { PleosTheme(darkTheme = false) { PleosHomePreview(c, autoplay) } }
    }

    override fun onStart() { super.onStart(); controller?.setScreenVisible(true); controller?.onAppStart() }

    override fun onStop() { controller?.setScreenVisible(false); controller?.saveNow(); super.onStop() }

    override fun onDestroy() { controller = null; WakeRuntime.activityDestroyed(); super.onDestroy() }

    companion object {
        const val EXTRA_AUTOPLAY = "autoplay"
    }
}

// ---------------- 시연 시나리오 ----------------
private const val DEMO_HOUR = 3
private const val DEMO_DRIVE_START_MIN = 60
private const val DEMO_DRIVE_STEP_MIN = 60
private const val DEMO_STEP2_MS = 75_000L
private const val DEMO_STEP3_MS = 145_000L
private const val DEMO_RECIRC_MIN = 20
private const val DEMO_TICK_MS = 5_000L
/** 서울시청 부근. 진행 방향은 넣지 않는다(가장 가까운 쉼터를 거리만으로 고른다). */
private const val DEMO_LAT = 37.5665
private const val DEMO_LON = 126.978

private fun demoLocation() = GeoPoint(DEMO_LAT, DEMO_LON, null, System.currentTimeMillis())

/** 저장 상태 복원이 끝난 뒤(누적 주행 시간을 덮어쓰지 않게) 시연 신호를 넣고, 시간에 따라 연속 주행 시간을 늘려 단계를 올린다. */
private suspend fun runDemo(c: AppController) {
    c.state.first { !it.loading }
    c.simReset()
    c.simVirtualCar(true)
    c.simMinute(DEMO_HOUR * 60)
    c.simAddDrive(DEMO_DRIVE_START_MIN)
    c.simPattern(SimPattern.DROWSY)
    c.simHub(true, null, Gear.DRIVE, demoLocation())
    var elapsed = 0L
    while (true) {
        delay(DEMO_TICK_MS)
        elapsed += DEMO_TICK_MS
        c.simHub(null, null, null, demoLocation()) // 위치를 계속 새로 고친다(쉼터 계산이 늘 최신 위치를 쓰게)
        if (elapsed == DEMO_STEP2_MS) c.simAddDrive(DEMO_DRIVE_STEP_MIN)
        if (elapsed == DEMO_STEP3_MS) { c.simAddDrive(DEMO_DRIVE_STEP_MIN); c.simRecirc(DEMO_RECIRC_MIN) }
    }
}

// ---------------- 배경 이미지 안 위치(이미지 픽셀, 2560×1440) ----------------
private const val IMG_W = 2560f
private const val IMG_H = 1440f
/** 왼쪽 주행 섹션: 가로 0~33.5%, 세로 7~91%. */
private const val DRIVE_SECTION_WIDTH = 0.335f
private const val DRIVE_SECTION_TOP = 0.07f
private const val DRIVE_SECTION_HEIGHT = 0.84f
/** 이미지에 찍힌 값(이 값과 다를 때만 덮어 그린다). */
private const val BAKED_TEMP = 23.5f
private const val BAKED_FAN = 3

/** 차속 "50": 숫자 x 24..146, y 100..177. */
private val SPEED_PATCH = Rect(10f, 90f, 172f, 190f)
private const val SPEED_X = 24f
private const val SPEED_BASE = 178f
private const val SPEED_PX = 108f
/** 운전석 온도 "23.5°": x 224..338, y 1339..1380. */
private val TEMP_PATCH = Rect(214f, 1330f, 350f, 1388f)
private const val TEMP_X = 224f
private const val TEMP_BASE = 1379.5f
private const val TEMP_PX = 57f
private const val TEMP_SMALL_PX = 39f
/** 도(°) 표시: 이미지에서 지름 약 10px 고리(가로 328..338, 세로 1339..1348). */
private const val DEG_CY = 1343.5f
private const val DEG_R = 4.2f
private const val DEG_STROKE = 2.2f
private const val DEG_GAP = 7f
/** 풍량 "3"(x 1824..1845, y 1350..1380)과 "AUTO"(x 1787..1842, y 1392..1404). */
private val FAN_PATCH = Rect(1819f, 1343f, 1856f, 1388f)
private val AUTO_PATCH = Rect(1780f, 1389f, 1850f, 1409f)
private const val FAN_X = 1824f
private const val FAN_BASE = 1381f
private const val FAN_PX = 43f
/** 운전석 시트 통풍 아이콘(x 710..789, 아래 끝 1399) 밑에 단계 막대. */
private const val SEAT_CX = 750f
private const val SEAT_BAR_TOP = 1407f
private const val SEAT_BAR_W = 18f
private const val SEAT_BAR_H = 6f
private const val SEAT_BAR_GAP = 6f
/** 글자 잉크 왼쪽 여백(글꼴 크기 대비): 숫자 기준 약 0.06em. */
private const val INK_LEFT = 0.06f

@Composable
private fun PleosHomePreview(c: AppController, autoplay: Boolean) {
    val s by c.state.collectAsState()
    val stage = activeStageOf(s)
    val bg: ImageBitmap = ImageBitmap.imageResource(R.drawable.pleos_home_light)
    LaunchedEffect(autoplay) { if (autoplay) runDemo(c) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Image(
            bitmap = bg,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
            filterQuality = FilterQuality.High,
        )
        // 차량 응답을 배경의 해당 자리에 그대로 갱신한다(가장자리 빛 아래에 두어 같은 빛을 받게)
        VehicleReadouts(s)
        // 화면 가장자리(베젤)부터 번지는 단계 색 빛: 배경 위, 카드 아래
        PleosAmbientEdgeGlow(stageLevel = if (s.loading) 0 else stage.n, modifier = Modifier.fillMaxSize()) { }
        // 왼쪽 주행 섹션 안에 반투명 경고 카드(음성 대화 결과 줄은 숨김)
        Box(
            Modifier
                .offset(y = maxHeight * DRIVE_SECTION_TOP)
                .width(maxWidth * DRIVE_SECTION_WIDTH)
                .height(maxHeight * DRIVE_SECTION_HEIGHT),
        ) {
            WakeAlertCard(s, Modifier.align(Alignment.Center).fillMaxWidth(0.9f), showStatus = false)
        }
    }
}

/** 배경 이미지 위 차속·운전석 온도·풍량·시트 통풍 단계. 이미지와 값이 같으면 그리지 않는다. */
@Composable
private fun VehicleReadouts(s: UiState) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val ink = PleosTheme.colors.textDarkPrimary
    val on = PleosTheme.colors.regulationBlue // 통풍(냉방) 단계는 파랑
    val off = PleosTheme.colors.basicAlphaDark100
    val paper = Color.White // 배경 이미지의 바탕색(#FFFFFF)
    val now = s.hvac.now
    Canvas(Modifier.fillMaxSize()) {
        val sx = size.width / IMG_W
        val sy = size.height / IMG_H
        fun patch(r: Rect) = drawRect(paper, Offset(r.left * sx, r.top * sy), Size(r.width * sx, r.height * sy))
        fun px(v: Float): TextUnit = with(density) { (v * sy).toSp() }

        s.speedKmh?.let { v ->
            patch(SPEED_PATCH)
            val style = TextStyle(fontFamily = AstaSans, fontWeight = FontWeight.Bold, fontSize = px(SPEED_PX), color = ink)
            drawAt(measurer, AnnotatedString(v.toString()), style, (SPEED_X - SPEED_PX * INK_LEFT) * sx, SPEED_BASE * sy)
        }
        now.tempC?.takeIf { abs(it - BAKED_TEMP) > 0.01f }?.let { t ->
            patch(TEMP_PATCH)
            val whole = t.toInt()
            val tenth = Math.round((t - whole) * 10)
            val text = buildAnnotatedString {
                append(whole.toString())
                withStyle(SpanStyle(fontSize = px(TEMP_SMALL_PX))) { append(String.format(Locale.US, ".%d", tenth)) }
            }
            val style = TextStyle(fontFamily = AstaSans, fontWeight = FontWeight.SemiBold, fontSize = px(TEMP_PX), color = ink)
            val right = drawAt(measurer, text, style, (TEMP_X - TEMP_PX * INK_LEFT) * sx, TEMP_BASE * sy)
            drawCircle(ink, radius = DEG_R * sy, center = Offset(right + DEG_GAP * sx, DEG_CY * sy), style = Stroke(DEG_STROKE * sy))
        }
        now.fan?.takeIf { it != BAKED_FAN }?.let { f ->
            patch(FAN_PATCH); patch(AUTO_PATCH) // 앱이 풍량을 정하면 자동(AUTO)이 풀린다
            val style = TextStyle(fontFamily = AstaSans, fontWeight = FontWeight.SemiBold, fontSize = px(FAN_PX), color = ink)
            drawAt(measurer, AnnotatedString(f.toString()), style, (FAN_X - FAN_PX * INK_LEFT) * sx, FAN_BASE * sy)
        }
        now.seatVent?.takeIf { it > 0 }?.let { lv ->
            val total = 3
            val w = (SEAT_BAR_W * total + SEAT_BAR_GAP * (total - 1)) * sx
            val x0 = SEAT_CX * sx - w / 2f
            for (i in 0 until total) {
                drawRoundRect(
                    color = if (i < lv) on else off,
                    topLeft = Offset(x0 + i * (SEAT_BAR_W + SEAT_BAR_GAP) * sx, SEAT_BAR_TOP * sy),
                    size = Size(SEAT_BAR_W * sx, SEAT_BAR_H * sy),
                    cornerRadius = CornerRadius(SEAT_BAR_H * sy / 2f),
                )
            }
        }
    }
}

/** 기준선(baseline) 위치에 맞춰 글자를 그리고, 글자 오른쪽 끝 x 를 돌려준다. */
private fun DrawScope.drawAt(measurer: TextMeasurer, text: AnnotatedString, style: TextStyle, x: Float, baseline: Float): Float {
    val layout = measurer.measure(text, style)
    drawText(layout, topLeft = Offset(x, baseline - layout.firstBaseline))
    return x + layout.size.width
}

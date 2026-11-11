package com.hmgcarapp.pleos.designsystem.theme

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 가이드 Display Layout 표. 앱 창(Application window) 크기는 dp입니다.
 * 앱은 한 화면에 1개 또는 2개가 뜨므로 Landscape·Portrait 두 레이아웃을 모두 지원해야 합니다.
 * formFactor는 공식 Figma 변수의 모드(connect-s / l / w / waterfall)입니다.
 */
enum class PleosDisplay(
    val inch: Float,
    val dpi: Int,
    val resolutionPx: Pair<Int, Int>,
    val appLandscape: Pair<Dp, Dp>,
    val appPortrait: Pair<Dp, Dp>,
    val formFactor: PleosFormFactor,
) {
    ConnectS17(17f, 160, 2560 to 1440, 1700.dp to 1184.dp, 840.dp to 1184.dp, PleosFormFactor.ConnectS),
    ConnectS14_6(14.6f, 200, 2048 to 1152, 1360.dp to 948.dp, 672.dp to 948.dp, PleosFormFactor.ConnectL),
    ConnectL12_9(12.9f, 160, 1920 to 1080, 1274.dp to 855.dp, 628.dp to 855.dp, PleosFormFactor.ConnectL),
    ConnectWStandard(24.6f, 160, 3840 to 850, 1640.dp to 810.dp, 800.dp to 810.dp, PleosFormFactor.ConnectW),
    ConnectWExtended(24.6f, 160, 3840 to 1440, 1640.dp to 1400.dp, 800.dp to 1400.dp, PleosFormFactor.ConnectW),
    ConnectWF(18.5f, 200, 3072 to 816, 1256.dp to 800.dp, 648.dp to 800.dp, PleosFormFactor.ConnectWaterfall),
}

/**
 * 디스플레이 해상도(px)와 밀도로 폼팩터를 고릅니다.
 * - 긴 변 3000px 이상(3840·3072): Wide 계열. 200dpi면 Waterfall, 아니면 Connect-W
 *   (W-Extended 3840×1440은 비율이 2.67:1이라 비율만으로는 구분되지 않습니다)
 * - 16:9이고 가로 2560px 이상: Connect-S(17")
 * - 그 밖의 16:9(1920·2048px): Connect-L 크기(Connect-S-14.6도 공식 값이 Connect-L과 같음)
 */
fun pleosFormFactorFor(widthPx: Int, heightPx: Int, densityDpi: Int): PleosFormFactor {
    val long = maxOf(widthPx, heightPx)
    return when {
        long >= 3000 -> if (densityDpi >= 200) PleosFormFactor.ConnectWaterfall else PleosFormFactor.ConnectW
        long >= 2560 -> PleosFormFactor.ConnectS
        else -> PleosFormFactor.ConnectL
    }
}

/** 현재 기기의 폼팩터. 앱 창이 아니라 전체 디스플레이 기준으로 판단합니다. */
@Composable
fun rememberPleosFormFactor(): PleosFormFactor {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(configuration) { detectFormFactor(context) }
}

private fun detectFormFactor(context: Context): PleosFormFactor {
    val metrics = context.resources.displayMetrics
    val (w, h) = if (Build.VERSION.SDK_INT >= 30) {
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        bounds.width() to bounds.height()
    } else {
        metrics.widthPixels to metrics.heightPixels
    }
    return pleosFormFactorFor(w, h, metrics.densityDpi)
}

/** 앱 창 형태. 판단 기준은 창 너비입니다(Portrait 창은 최대 840dp). */
enum class PleosWindowMode { Portrait, Landscape }

/** Portrait 창 중 가장 넓은 값(Connect-S-17, 840dp)을 경계로 씁니다. */
val PortraitMaxWidth: Dp = 840.dp

fun pleosWindowMode(width: Dp): PleosWindowMode =
    if (width <= PortraitMaxWidth) PleosWindowMode.Portrait else PleosWindowMode.Landscape

/** 간격. 공식 Figma Number 변수(Common/4~48)와 같습니다. */
object PleosSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val s = 12.dp
    val m = 16.dp
    val ml = 20.dp
    val l = 24.dp
    val xl = 32.dp
    val xxl = 40.dp
    val xxxl = 48.dp
}

/** Connect-S 기준 고정 크기. 폼팩터별 값은 PleosTheme.dimensions를 쓰세요. */
object PleosSize {
    val ToastMaxWidth = 792.dp
    val ToastMessageMaxWidth = 712.dp
    val PopupLargeWidth = 1200.dp
    val PopupMediumWidth = 808.dp
    val IconGrid = 48.dp

    /** 공식 가장 작은 버튼(Small) 높이와 같습니다. */
    val MinTouchTarget = 64.dp
}

/** 모서리 반경. 공식 Radius 변수(xs 0, m 16, l 24, circle)와 컴포넌트 반경 단계(12, 32, 48)입니다. */
object PleosRadius {
    val xs = 0.dp
    val s = 12.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
}

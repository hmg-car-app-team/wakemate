package com.hmgcarapp.pleos.designsystem.restriction

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.hmgcarapp.pleos.designsystem.R
import androidx.compose.ui.unit.dp
import com.hmgcarapp.pleos.designsystem.theme.PleosRadius
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme

/**
 * CarUxRestrictions 값을 Compose에서 쓰기 쉽게 감싼 것입니다.
 * 플래그 값은 android.car.drivingstate.CarUxRestrictions 상수와 같습니다.
 */
@Immutable
data class PleosUxRestrictions(
    val requiresDistractionOptimization: Boolean,
    val activeRestrictions: Int,
) {
    fun has(flag: Int): Boolean = requiresDistractionOptimization && (activeRestrictions and flag) != 0

    val noDialpad get() = has(NO_DIALPAD)
    val noFiltering get() = has(NO_FILTERING)
    val limitStringLength get() = has(LIMIT_STRING_LENGTH)
    val noKeyboard get() = has(NO_KEYBOARD)
    val noVideo get() = has(NO_VIDEO)
    val limitContent get() = has(LIMIT_CONTENT)
    val noSetup get() = has(NO_SETUP)
    val noTextMessage get() = has(NO_TEXT_MESSAGE)
    val noVoiceTranscription get() = has(NO_VOICE_TRANSCRIPTION)

    companion object {
        const val NO_DIALPAD = 1
        const val NO_FILTERING = 2
        const val LIMIT_STRING_LENGTH = 4
        const val NO_KEYBOARD = 8
        const val NO_VIDEO = 16
        const val LIMIT_CONTENT = 32
        const val NO_SETUP = 64
        const val NO_TEXT_MESSAGE = 128
        const val NO_VOICE_TRANSCRIPTION = 256

        /** 주차 상태(제한 없음) */
        val Parked = PleosUxRestrictions(false, 0)

        /** Pleos 문서의 "그외 지역" 주행 상태 근사치: 도어·시트·설정, 동영상, QR, 대용량 텍스트 제한 */
        val DrivingRestOfWorld = PleosUxRestrictions(true, NO_SETUP or NO_VIDEO or LIMIT_CONTENT or LIMIT_STRING_LENGTH)

        /** 북미 주행 상태 근사치: 키보드·다이얼패드·검색까지 제한 */
        val DrivingNorthAmerica = PleosUxRestrictions(
            true,
            NO_DIALPAD or NO_FILTERING or LIMIT_STRING_LENGTH or NO_KEYBOARD or NO_VIDEO or LIMIT_CONTENT or NO_SETUP,
        )
    }
}

val LocalPleosUxRestrictions = staticCompositionLocalOf { PleosUxRestrictions.Parked }

/**
 * 차량(FEATURE_AUTOMOTIVE)에서는 CarUxRestrictionsManager를 구독하고,
 * 일반 기기에서는 항상 Parked를 돌려줍니다.
 */
@Composable
fun rememberCarUxRestrictions(): PleosUxRestrictions {
    val context = LocalContext.current
    var state by remember { mutableStateOf(PleosUxRestrictions.Parked) }
    DisposableEffect(context) {
        val source = if (context.isAutomotive()) CarUxRestrictionsSource(context) { state = it } else null
        source?.start()
        onDispose { source?.stop() }
    }
    return state
}

private fun Context.isAutomotive(): Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)

/**
 * 앱 최상단(PleosTheme 안쪽)에서 감쌉니다.
 * override를 넘기면 실제 차량 상태 대신 그 값을 씁니다(에뮬레이터 없이 주행 상태 시연용).
 */
@Composable
fun PleosUxRestrictionsProvider(
    override: PleosUxRestrictions? = null,
    content: @Composable () -> Unit,
) {
    val live = rememberCarUxRestrictions()
    CompositionLocalProvider(LocalPleosUxRestrictions provides (override ?: live), content = content)
}

/**
 * flag가 활성화된 주행 상태면 content 대신 restricted를 보여줍니다.
 * 예: RestrictedWhileDriving(PleosUxRestrictions.NO_SETUP) { SeatSettings() }
 */
@Composable
fun RestrictedWhileDriving(
    flag: Int,
    restricted: @Composable () -> Unit = { DrivingRestrictedNotice() },
    content: @Composable () -> Unit,
) {
    if (LocalPleosUxRestrictions.current.has(flag)) restricted() else content()
}

/** 기본 안내. 짧은 문장으로, 정차 후 이용하도록 안내합니다. */
@Composable
fun DrivingRestrictedNotice(
    message: String = stringResource(R.string.pleos_driving_restricted),
    modifier: Modifier = Modifier,
) {
    val colors = PleosTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PleosRadius.l))
            .background(colors.surfaceHigh)
            .padding(32.dp),
    ) {
        Text(message, style = PleosTheme.typography.bodyLarge, color = colors.textDarkSecondary)
    }
}

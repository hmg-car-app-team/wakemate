package com.hmgcarapp.wakemate.app

import android.media.AudioManager
import android.media.ToneGenerator
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.wakemate.domain.Stage
import kotlinx.coroutines.delay

/** 경고음. 표준 ToneGenerator(권한 불필요)를 쓴다. 실패해도 예외 없이 false. */
interface Beeper {
    /** 경고음을 내고 끝날 때까지 기다린다. 반환: 소리를 시작했는지(기기가 거부하면 false). */
    suspend fun beep(stage: Stage): Boolean
}

class ToneBeeper : Beeper {
    override suspend fun beep(stage: Stage): Boolean {
        val gen = try { ToneGenerator(AudioManager.STREAM_ALARM, 80) } catch (t: Throwable) {
            HmgLog.w("Beeper", "ToneGenerator 생성 실패: ${t.javaClass.simpleName}"); return false
        }
        return try {
            val n = if (stage.n >= 3) 3 else 2
            repeat(n) { gen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300); delay(450) }
            true
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            HmgLog.w("Beeper", "경고음 실패: ${t.javaClass.simpleName}"); false
        } finally { runCatching { gen.release() } }
    }
}

class FakeBeeper : Beeper {
    val calls = mutableListOf<Stage>()
    override suspend fun beep(stage: Stage): Boolean { calls += stage; return true }
}

package com.hmgcarapp.core.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit
import android.content.Context
import com.hmgcarapp.core.log.HmgLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode

/** 음성 엔진 모드. 앱 등록(Client ID/Secret) 불필요한 ON_DEVICE 가 기본. */
enum class VoiceMode { ON_DEVICE, HYBRID, SERVER }

/**
 * 권한(매니페스트): pleos.car.permission.TTS_SERVICE / pleos.car.permission.STT_SERVICE
 * (+ STT 마이크는 차량 마이크를 SDK 가 사용). 서버 모드는 registerApp(clientId, secret) 필요 -> 본 래퍼는 ON_DEVICE 전제.
 */
interface Speaker {
    /** 텍스트를 읽는다. 실패해도 예외 없음(false). 반환은 발화 요청이 접수됐는지. */
    fun speak(text: String): Boolean

    /**
     * 발화하고 **끝날 때까지**(onDone/onStop/onError) 기다린다. [timeoutMs] 는 상한(이벤트가 안 오는 환경 폴백).
     * [timeoutMs] 기본값([AUTO_TIMEOUT])은 문장 길이 비례([speechTimeoutMs]). 명시하면 그 값을 쓴다.
     * 반환: 발화가 접수되어 이벤트로 끝났으면 true, 접수 실패/타임아웃(이때 stop() 호출)/stop()·release()/엔진 오류면 false. 기본 구현은 [speak] 만 호출(대기 없음).
     */
    suspend fun speakAndAwait(text: String, timeoutMs: Long = AUTO_TIMEOUT): Boolean = speak(text)

    fun stop()
    fun release()
}

/** [Speaker.speakAndAwait] 의 timeoutMs 기본 센티넬: 문장 길이로 계산한다. */
const val AUTO_TIMEOUT = -1L

/** 최소 6000ms + 글자당 250ms, 상한 25000ms. */
fun speechTimeoutMs(text: String): Long = (6000L + 250L * text.length).coerceAtMost(25_000L)

sealed interface ListenResult {
    data class Text(val text: String) : ListenResult
    data class Failure(val reason: String) : ListenResult
    data object Timeout : ListenResult
}

/** 1회 인식만 지원한다. **상시 청취(항상 켜진 마이크) 금지**. */
interface Listener {
    /** request() 1회 -> 최종 결과 하나를 돌려주고 stop() 한다. 코루틴 취소 시에도 stop(). */
    suspend fun listenOnce(timeoutMs: Long = 10_000L): ListenResult
    fun release()
}

/** TTS 엔진 최소 포트(테스트에서 가짜 주입용). */
internal interface TtsPort {
    /** 발화 접수 여부. */
    fun speak(text: String): Boolean
    fun stop()
    fun release()
}

/**
 * 발화 세대(sequence) 관리. 엔진 이벤트는 id 가 없으므로, 우리가 stop()/교체로 끊은 발화의 늦은 종료 이벤트(stale)는
 * 새 발화의 onStart 전에 도착하면 무시한다. 한계: 엔진이 stop 후 종료 이벤트를 안 보내고 onStart 도 안 보내면
 * 첫 정상 종료 이벤트 1건이 무시될 수 있다(그 경우 타임아웃 상한이 폴백).
 */
internal class SpeechCoordinator(private val port: TtsPort?) {
    private val lock = Any()
    private var gen = 0L
    private var done: CompletableDeferred<Boolean>? = null
    private var pendingText: String? = null
    private var initOk = false
    private var initFailed = port == null
    private var released = false
    private var engineBusy = false
    private var stale = false

    fun onInitSuccess() {
        val p = synchronized(lock) { if (released) return; initOk = true; pendingText.also { pendingText = null } }
        if (p != null) dispatch(p)
    }

    fun onInitFailure() {
        val d = synchronized(lock) { initFailed = true; pendingText = null; done.also { done = null } }
        d?.complete(false)
    }

    fun onStart() = synchronized(lock) { stale = false }

    fun onDone() = onEnd(true)
    fun onStop() = onEnd(true)
    fun onError() = onEnd(false)

    private fun onEnd(ok: Boolean) {
        val d = synchronized(lock) {
            if (stale) { stale = false; return } // 이전(끊은) 발화의 늦은 종료 이벤트
            engineBusy = false
            done.also { done = null }
        }
        d?.complete(ok)
    }

    /** 새 세대를 시작한다. 이전 발화는 정리(대기자 false 완료, 엔진 stop). 반환: (세대 id, 이전 발화 때문에 엔진 stop 필요 여부) */
    private fun begin(d: CompletableDeferred<Boolean>?): Pair<Long, Boolean> {
        val prev: CompletableDeferred<Boolean>?
        val needStop: Boolean
        val g: Long
        synchronized(lock) {
            prev = done; needStop = engineBusy
            if (needStop) { stale = true; engineBusy = false }
            pendingText = null
            gen++; g = gen; done = d
        }
        prev?.complete(false)
        return g to needStop
    }

    private fun dispatch(text: String): Boolean {
        val engine = port ?: return false
        synchronized(lock) { engineBusy = true }
        val ok = try { engine.speak(text) } catch (t: Throwable) { HmgLog.w("Speaker", "speak 실패: $t"); false }
        if (!ok) synchronized(lock) { engineBusy = false }
        return ok
    }

    private fun accept(text: String, g: Long): Boolean {
        val send: Boolean = synchronized(lock) {
            if (released || initFailed || g != gen) return false
            if (!initOk) { pendingText = text; false } else true
        }
        return if (send) dispatch(text) else true
    }

    fun speak(text: String): Boolean {
        if (port == null || synchronized(lock) { released || initFailed }) return false
        val (g, needStop) = begin(null)
        if (needStop) runCatching { port?.stop() }
        return accept(text, g)
    }

    suspend fun speakAndAwait(text: String, timeoutMs: Long): Boolean {
        if (port == null || synchronized(lock) { released || initFailed }) return false
        val d = CompletableDeferred<Boolean>()
        val (g, needStop) = begin(d)
        try {
            if (needStop) runCatching { port?.stop() }
            if (!accept(text, g)) return false
            val limit = if (timeoutMs == AUTO_TIMEOUT) speechTimeoutMs(text) else timeoutMs
            return withTimeoutOrNull(limit) { d.await() } ?: false
        } finally {
            // 타임아웃·취소·접수 실패 등으로 아직 끝나지 않았고 아직 우리 세대면 엔진을 멈추고 상태를 정리한다.
            if (!d.isCompleted) {
                var stopIt = false
                synchronized(lock) {
                    if (g == gen) {
                        done = null; pendingText = null; stopIt = true
                        if (engineBusy) { stale = true; engineBusy = false }
                    }
                }
                if (stopIt) runCatching { port?.stop() }
            }
        }
    }

    fun stop() {
        val d: CompletableDeferred<Boolean>?
        val needStop: Boolean
        synchronized(lock) {
            d = done; done = null; pendingText = null; gen++
            needStop = engineBusy
            if (needStop) { stale = true; engineBusy = false }
        }
        d?.complete(false)
        // 유휴 엔진에 stop 을 보내면 onStop 이벤트가 새 발화를 조기 종료시킬 수 있어 호출하지 않는다.
        if (needStop) runCatching { port?.stop() }
    }

    fun release() {
        val d: CompletableDeferred<Boolean>?
        synchronized(lock) {
            if (released) return
            released = true; pendingText = null; gen++
            d = done; done = null
        }
        d?.complete(false)
        runCatching { port?.stop() }
        runCatching { port?.release() }
    }
}

class PleosSpeaker(context: Context, mode: VoiceMode = VoiceMode.ON_DEVICE, locale: Locale = Locale.KOREA) : Speaker {
    private lateinit var core: SpeechCoordinator

    private val eventListener = object : EventListener {
        override fun onStart() { core.onStart() }
        override fun onReady() = Unit
        override fun onDone() { core.onDone() }
        override fun onStop() { core.onStop() }
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onError(errorType: TtsError) { HmgLog.w("Speaker", "TTS 오류: $errorType"); core.onError() }
    }

    private val tts: TextToSpeech? = try {
        TextToSpeech(context.applicationContext, mode.toTts())
    } catch (t: Throwable) { HmgLog.w("Speaker", "TTS 생성 실패: $t"); null }

    init {
        val engine = tts
        core = SpeechCoordinator(engine?.let { e ->
            object : TtsPort {
                override fun speak(text: String): Boolean = e.speak(text, true) != null
                override fun stop() { e.stop() }
                override fun release() { runCatching { e.removeEventListener(eventListener) }; e.release() }
            }
        })
        if (engine != null) {
            try {
                engine.initialize(object : TtsInit {
                    override fun onSuccess() {
                        HmgLog.i("Speaker", "TTS 초기화 성공($mode)")
                        runCatching { engine.setLocale(locale) }
                        core.onInitSuccess()
                    }
                    override fun onFailure(errorType: TtsError) { HmgLog.w("Speaker", "TTS 초기화 실패: $errorType"); core.onInitFailure() }
                })
                engine.addEventListener(eventListener)
            } catch (t: Throwable) { HmgLog.w("Speaker", "TTS 초기화 예외: $t"); core.onInitFailure() }
        }
    }

    override fun speak(text: String): Boolean = core.speak(text)
    override suspend fun speakAndAwait(text: String, timeoutMs: Long): Boolean = core.speakAndAwait(text, timeoutMs)
    override fun stop() = core.stop()
    override fun release() = core.release()

    private fun VoiceMode.toTts() = when (this) { VoiceMode.ON_DEVICE -> TtsMode.ON_DEVICE; VoiceMode.HYBRID -> TtsMode.HYBRID; VoiceMode.SERVER -> TtsMode.SERVER }
}

internal const val INIT_WAIT_MS = 5_000L

/** STT 엔진 최소 포트(테스트에서 가짜 주입용). */
internal interface SttPort {
    fun addListener(l: ResultListener)
    fun removeListener(l: ResultListener)
    fun setLocale(locale: Locale)
    fun request()
    fun stop()
}

/**
 * 1회 인식. 정상 종료·타임아웃·취소·예외 어느 경로든 finally 에서 리스너 제거와 stop() 을 보장한다.
 */
internal suspend fun listenOnceOn(
    engine: SttPort, locale: Locale, timeoutMs: Long,
    /** STT 초기화 완료 대기(최대 [INIT_WAIT_MS]). null 이면 대기 안 함. 반환 false/타임아웃 = 초기화 실패. */
    awaitReady: (suspend () -> Boolean)? = null,
): ListenResult {
    if (awaitReady != null) {
        val ready = withTimeoutOrNull(INIT_WAIT_MS) { awaitReady() } ?: false
        if (!ready) return ListenResult.Failure("STT 초기화 실패")
    }
    var registered: ResultListener? = null
    try {
        val r = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<ListenResult> { cont ->
                // 콜백(onUpdated/onError)이 서로 다른 스레드에서 겹쳐도 resume 은 정확히 한 번(isActive 검사만으로는 경합 가능, r7R5-9)
                val done = java.util.concurrent.atomic.AtomicBoolean(false)
                fun finish(r: ListenResult) { if (done.compareAndSet(false, true)) runCatching { cont.resume(r) } }
                val l = object : ResultListener {
                    override fun onUpdated(stt: String, completed: Boolean) { if (completed) finish(ListenResult.Text(stt)) }
                    override fun onUpdatedEpdData(on: Long, off: Long) = Unit
                    override fun onStartedRecognition() = Unit
                    override fun onEndedRecognition() = Unit
                    override fun onError(errorType: SttError) { finish(ListenResult.Failure("STT 오류: $errorType")) }
                    override fun onReady() = Unit
                    override fun onUpdatedRms(rms: Double) = Unit
                }
                registered = l
                try {
                    engine.addListener(l)
                    runCatching { engine.setLocale(locale) }
                    engine.request()
                } catch (t: Throwable) { finish(ListenResult.Failure("STT 요청 실패: ${t.javaClass.simpleName}")) }
            }
        }
        return r ?: ListenResult.Timeout
    } finally {
        registered?.let { l -> runCatching { engine.removeListener(l) } }
        runCatching { engine.stop() }
    }
}

class PleosListener(context: Context, mode: VoiceMode = VoiceMode.ON_DEVICE, private val locale: Locale = Locale.KOREA) : Listener {
    private val ready = CompletableDeferred<Boolean>()

    private val stt: SpeechToText? = try {
        SpeechToText(context.applicationContext, mode.toStt()).also {
            it.initialize(object : SttInit {
                override fun onSuccess() { HmgLog.i("Listener", "STT 초기화 성공($mode)"); ready.complete(true); Unit }
                override fun onFailure(errorType: SttError) { HmgLog.w("Listener", "STT 초기화 실패: $errorType"); ready.complete(false); Unit }
            })
        }
    } catch (t: Throwable) { HmgLog.w("Listener", "STT 생성 실패: $t"); ready.complete(false); null }

    override suspend fun listenOnce(timeoutMs: Long): ListenResult {
        val engine = stt ?: return ListenResult.Failure("STT 사용 불가")
        return listenOnceOn(object : SttPort {
            override fun addListener(l: ResultListener) = engine.addListener(l)
            override fun removeListener(l: ResultListener) = engine.removeListener(l)
            override fun setLocale(locale: Locale) { engine.setLocale(locale) }
            override fun request() { engine.request() }
            override fun stop() { engine.stop() }
        }, locale, timeoutMs, awaitReady = { ready.await() })
    }

    override fun release() { runCatching { stt?.release() } }

    private fun VoiceMode.toStt() = when (this) { VoiceMode.ON_DEVICE -> SttMode.ON_DEVICE; VoiceMode.HYBRID -> SttMode.HYBRID; VoiceMode.SERVER -> SttMode.SERVER }
}

/** 테스트/에뮬레이터용 가짜. */
class FakeSpeaker : Speaker {
    val spoken = mutableListOf<String>()
    override fun speak(text: String): Boolean { spoken += text; return true }
    override fun stop() = Unit
    override fun release() = Unit
}

class FakeListener(private val script: ArrayDeque<ListenResult> = ArrayDeque()) : Listener {
    fun enqueue(r: ListenResult) = script.addLast(r)
    override suspend fun listenOnce(timeoutMs: Long): ListenResult = script.removeFirstOrNull() ?: ListenResult.Timeout
    override fun release() = Unit
}

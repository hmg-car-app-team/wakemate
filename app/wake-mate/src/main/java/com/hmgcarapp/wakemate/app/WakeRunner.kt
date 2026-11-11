package com.hmgcarapp.wakemate.app

import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.core.voice.ListenResult
import com.hmgcarapp.core.voice.Listener
import com.hmgcarapp.core.voice.Speaker
import com.hmgcarapp.wakemate.domain.Heard
import com.hmgcarapp.wakemate.domain.HvacPlan
import com.hmgcarapp.wakemate.domain.HvacTargets
import com.hmgcarapp.wakemate.domain.ItemResult
import com.hmgcarapp.wakemate.domain.LocationFreshness
import com.hmgcarapp.wakemate.domain.NaviPort
import com.hmgcarapp.wakemate.domain.NaviStatus
import com.hmgcarapp.wakemate.domain.Quiz
import com.hmgcarapp.wakemate.domain.RouteAdvisor
import com.hmgcarapp.wakemate.domain.ShelterRepo
import com.hmgcarapp.wakemate.domain.WakeEffect
import com.hmgcarapp.wakemate.domain.WakeEvent
import com.hmgcarapp.wakemate.vehicle.WakeHvac
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * [com.hmgcarapp.wakemate.domain.WakeFlow] 의 효과를 SDK(TTS·STT·내비·공조·경고음)로 실행하고 결과를 이벤트로 되먹인다.
 * 상태 전이는 하지 않는다(여기서 읽는 것은 현재 화면 상태 스냅샷뿐). 모든 호출은 메인 스레드.
 * 코루틴은 LAZY 로 만들어 슬롯에 넣은 뒤 시작한다(즉시 실행 디스패처에서 본문이 동기로 다음 효과를 일으켜도 새 job 을 잃지 않게).
 */
class WakeRunner(
    private val scope: CoroutineScope,
    private val speaker: Speaker,
    private val listener: Listener,
    private val navi: NaviPort,
    private val advisor: RouteAdvisor,
    private val hvac: WakeHvac,
    private val beeper: Beeper,
    private val ui: () -> UiState,
    private val monoClock: () -> Long,
    private val debuggable: Boolean,
    private val dispatch: (WakeEvent) -> Unit,
    private val onNotSleepy: () -> Unit,
    private val onQuiz: (Quiz.Outcome, Long) -> Unit,
) {
    private var alertJob: Job? = null
    private var expiryJob: Job? = null
    private var guidanceJob: Job? = null
    private var beepJob: Job? = null
    private var hvacJob: Job? = null

    fun run(fx: WakeEffect) {
        when (fx) {
            WakeEffect.CancelVoice -> cancelVoice()
            is WakeEffect.Beep -> {
                if (ui().p.settings.beep) { val j = launchLazy { beeper.beep(fx.stage) }; beepJob = j; j.start() }
            }
            is WakeEffect.Speak -> alert(fx.id) {
                beepJob?.join() // 경고음이 끝난 뒤에 말한다
                // false 는 TTS 실패뿐 아니라 시간 초과·교체일 수도 있다: 흐름은 계속 진행한다
                dispatch(WakeEvent.VoiceSpoken(fx.id, speaker.speakAndAwait(fx.text)))
            }
            is WakeEffect.Listen -> alert(fx.id) {
                val t0 = monoClock()
                val heard = when (val r = listener.listenOnce(LISTEN_MS)) {
                    is ListenResult.Text -> { if (debuggable) HmgLog.i("Voice", "STT: ${r.text}"); Heard.Text(r.text) } // 발화 원문은 debuggable 에서만 로그
                    is ListenResult.Failure -> { HmgLog.w("Voice", "STT 실패: ${r.reason}"); Heard.Failure }
                    ListenResult.Timeout -> Heard.Timeout
                }
                dispatch(WakeEvent.VoiceHeard(fx.id, fx.purpose, heard, monoClock() - t0))
            }
            is WakeEffect.PlanShelter -> alert(fx.id) {
                beepJob?.join()
                val plan = advisor.plan()
                val s = ui()
                val ds = s.dataset
                val origin = s.origin?.takeIf { LocationFreshness.isFresh(s.originAtMs, monoClock()) }
                val near = origin?.let { o -> ds?.let { ShelterRepo.nearest(o, it.shelters, 1).firstOrNull() } }
                dispatch(WakeEvent.ShelterPlanned(fx.id, near, if (near != null) plan else null, ds?.sample ?: true, locationKnown = origin != null))
            }
            is WakeEffect.ApplyHvac -> {
                hvacJob?.cancel()
                val j = launchLazy { guarded(fx.id) { applyHvac(fx.id) } }; hvacJob = j; j.start()
            }
            is WakeEffect.StartNavi -> {
                val body: suspend () -> Unit = {
                    val sh = ui().dataset?.shelters?.firstOrNull { it.id == fx.shelterId }
                    val res: NaviStatus? = if (sh == null) null else {
                        val reqId = navi.routeToShelter(sh, fx.action)
                        navi.awaitResult(reqId)
                    }
                    dispatch(WakeEvent.NaviResult(fx.id, res))
                }
                if (fx.id != null) alert(fx.id) { body() } else launchInto({ guidanceJob = it }) { guarded(null) { body() } }
            }
            is WakeEffect.SpeakResult -> alert(fx.id) { speaker.speakAndAwait(fx.text); dispatch(WakeEvent.ResultSpoken(fx.id)) }
            is WakeEffect.ScheduleExpiry -> { expiryJob?.cancel(); expiryJob = scope.launch { delay(fx.delayMs); dispatch(WakeEvent.ExpiryElapsed(fx.id)) } }
            is WakeEffect.ClearNaviFinished -> if (fx.reqId == null) navi.clearFinished() else navi.clearFinished(fx.reqId)
            WakeEffect.ApplyNotSleepy -> onNotSleepy()
            is WakeEffect.RecordQuiz -> onQuiz(fx.outcome, fx.responseMs)
            is WakeEffect.Warn -> HmgLog.w("Wake", fx.text)
        }
    }

    /** 공조 조정: 현재 값·capability 로 계획을 세워 쓰고, 성공 콜백을 받은 항목만 성공으로 보고한다. */
    private suspend fun applyHvac(id: Long) {
        val snap = hvac.snapshot()
        val usable = snap.connected && snap.caps.anyWritable
        val base = ui().wake.baseTempC ?: snap.now.tempC
        val targets = if (usable) HvacPlan.plan(snap.now, snap.caps, base) else HvacTargets()
        val results: List<ItemResult> = if (!usable || targets.isEmpty) emptyList() else {
            withTimeoutOrNull(HVAC_WAIT_MS) {
                suspendCancellableCoroutine<List<ItemResult>> { cont ->
                    val n = hvac.apply(targets) { r -> runCatching { cont.resume(r) } }
                    if (n == 0) runCatching { cont.resume(emptyList()) }
                }
            } ?: emptyList()
        }
        dispatch(WakeEvent.HvacApplied(id, results, targets, usable, base))
    }

    /**
     * 사용자 조작(정차 시만): 실행 직전에 경로 상태를 다시 확인해 현재 상태 기준으로 동작을 정한다.
     * 확인 불가(UNKNOWN)면 기존 경로를 덮어쓰기 전에 확인 팝업을 띄운다.
     */
    fun guide(shelterId: String) {
        if (guidanceJob?.isActive == true) return
        launchInto({ guidanceJob = it }) {
            val plan = advisor.plan()
            if (ui().drive == DriveState.DRIVING) return@launchInto // 재확인 중 주행이 시작되면 중단
            if (plan.canOverwrite) dispatch(WakeEvent.ReplaceAsked(shelterId)) else dispatch(WakeEvent.ManualNavi(shelterId, plan.action))
        }
    }

    fun cancelVoice() { alertJob?.cancel(); alertJob = null; expiryJob?.cancel(); expiryJob = null; beepJob?.cancel(); beepJob = null; speaker.stop() }

    /** 새 음성 단계는 이전 단계(예: 듣는 중)를 끊고 시작한다(시연 주입이 듣는 중 단계를 대체할 수 있게). */
    private fun alert(id: Long, block: suspend () -> Unit) { alertJob?.cancel(); launchInto({ alertJob = it }) { guarded(id) { block() } } }

    private fun launchLazy(block: suspend () -> Unit): Job = scope.launch(start = CoroutineStart.LAZY) { block() }

    private fun launchInto(slot: (Job) -> Unit, block: suspend () -> Unit) {
        val j = launchLazy(block)
        slot(j); j.start()
    }

    /** 흐름이 예외로 끝나도 배너가 '말하는 중'에 고착되지 않게 실패 이벤트로 닫는다(취소·VM 치명 오류는 삼키지 않는다). */
    private suspend inline fun guarded(id: Long?, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            HmgLog.w("Alert", "깨우기 흐름 예외: ${e.javaClass.simpleName}")
            if (id != null) dispatch(WakeEvent.AlertFailed(id))
        }
    }

    companion object {
        const val LISTEN_MS = 8000L
        /** 어댑터의 쓰기 무응답 판정(4초, 3초 주기 점검 → 최대 약 7초)보다 길게 둬 '응답 못 받음'과 늦은 성공이 엇갈리지 않게 한다. */
        const val HVAC_WAIT_MS = 9000L
    }
}

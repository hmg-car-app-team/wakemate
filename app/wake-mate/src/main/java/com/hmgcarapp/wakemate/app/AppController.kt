package com.hmgcarapp.wakemate.app

import android.content.BroadcastReceiver
import android.content.Context
import android.os.SystemClock
import com.hmgcarapp.core.CarSignals
import com.hmgcarapp.core.drive.DriveSnapshot
import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.core.drive.Gear
import com.hmgcarapp.core.location.GeoPoint
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.core.sim.SimulationHub
import com.hmgcarapp.core.store.LocalJsonStore
import com.hmgcarapp.core.voice.Listener
import com.hmgcarapp.core.voice.PleosListener
import com.hmgcarapp.core.voice.PleosSpeaker
import com.hmgcarapp.core.voice.Speaker
import com.hmgcarapp.wakemate.domain.Calibration
import com.hmgcarapp.wakemate.domain.DriveClock
import com.hmgcarapp.wakemate.domain.DriveRestore
import com.hmgcarapp.wakemate.domain.LatLon
import com.hmgcarapp.wakemate.domain.LocationFreshness
import com.hmgcarapp.wakemate.domain.DrivePersist
import com.hmgcarapp.wakemate.domain.Quiz
import com.hmgcarapp.wakemate.domain.RecircClock
import com.hmgcarapp.wakemate.domain.RestEvent
import com.hmgcarapp.wakemate.domain.RiskEngine
import com.hmgcarapp.wakemate.domain.RiskInputs
import com.hmgcarapp.wakemate.domain.RouteAdvisor
import com.hmgcarapp.wakemate.domain.ShelterDataset
import com.hmgcarapp.wakemate.domain.ShelterRepo
import com.hmgcarapp.wakemate.domain.SimPattern
import com.hmgcarapp.wakemate.domain.SimPatterns
import com.hmgcarapp.wakemate.domain.SpeedAnalyzer
import com.hmgcarapp.wakemate.domain.SpeedSample
import com.hmgcarapp.wakemate.domain.Stage
import com.hmgcarapp.wakemate.domain.SteerSample
import com.hmgcarapp.wakemate.domain.SteeringAnalyzer
import com.hmgcarapp.wakemate.domain.WakeEffect
import com.hmgcarapp.wakemate.domain.WakeEvent
import com.hmgcarapp.wakemate.domain.WakeFlow
import com.hmgcarapp.wakemate.navi.NaviController
import com.hmgcarapp.wakemate.vehicle.PleosSteeringSource
import com.hmgcarapp.wakemate.vehicle.PleosWakeHvac
import com.hmgcarapp.wakemate.vehicle.SteeringSource
import com.hmgcarapp.wakemate.vehicle.WakeHvac
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 화면 상태의 단일 소유자이자 SDK/IO 배선. Activity 수명. 모든 상태 변경은 메인 스레드(Dispatchers.Main.immediate).
 * 깨우기 상태 전이는 [WakeFlow] 가 하고([dispatch]), 효과 실행은 [WakeRunner] 가 한다.
 * 시계: 지속시간·간격·쿨다운은 [monoClock](단조), 하루 중 시각(시간대 위험)·저장 만료만 [wallClock].
 */
class AppController(
    private val context: Context,
    val signals: CarSignals,
    private val steering: SteeringSource = PleosSteeringSource(context),
    private val hvac: WakeHvac = PleosWakeHvac(context),
    private val speaker: Speaker = PleosSpeaker(context),
    private val listener: Listener = PleosListener(context),
    private val beeper: Beeper = ToneBeeper(),
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monoClock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, t -> HmgLog.w("App", "코루틴 예외(무시): $t") })
    private val stateStore = StateStore(LocalJsonStore.forApp(context))
    val debuggable: Boolean = SimulationHub.isDebuggable(context)
    val navi = NaviController(context, scope, debuggable)

    private val _state = MutableStateFlow(UiState(debuggable = debuggable))
    val state: StateFlow<UiState> = _state

    private val runner = WakeRunner(
        scope, speaker, listener, navi, RouteAdvisor(navi), hvac, beeper, { _state.value }, monoClock, debuggable,
        ::dispatch, onNotSleepy = { applyNotSleepy(byVoice = true) }, onQuiz = ::recordQuiz,
    )
    private val driveClock = DriveClock()
    private val recircClock = RecircClock()
    private val steerBuf = ArrayDeque<SteerSample>()
    private val speedBuf = ArrayDeque<SpeedSample>()
    private var lastSpeed: Float? = null
    private var lastAngle: Float? = null
    private var lastAngleAt = 0L
    private var coreDrive = DriveState.DRIVING
    private var uiDrive: DriveState? = null
    private var simJob: Job? = null
    private var saveJob: Job? = null
    private var simReceiver: BroadcastReceiver? = null
    private val restored: Boolean get() = !_state.value.loading

    init {
        // 시연 상태는 저장하지 않는다: 앱 시작은 항상 실신호.
        signals.hub.setDriveOverride(null); signals.hub.setEnabled(false)
        navi.connect()
        scope.launch { loadShelters() }
        scope.launch { restore() }
        hvac.onState = { snap -> scope.launch { onHvac(snap) } }
        scope.launch { hvac.run() }
        scope.launch {
            signals.driveState.catch { HmgLog.w("Drive", "flow 실패 $it") }.collect { d: DriveSnapshot -> coreDrive = d.state; applyDrive() }
        }
        scope.launch {
            signals.speedKmh.catch { HmgLog.w("Speed", "flow 실패 $it") }.collect { r ->
                lastSpeed = r.value
                r.value?.let { v -> addSpeed(SpeedSample(monoClock(), v)) }
            }
        }
        scope.launch {
            steering.angles().catch { HmgLog.w("Steering", "flow 실패 $it") }.collect { a -> if (_state.value.sim.pattern == null) { lastAngle = a; lastAngleAt = monoClock() } } // 패턴 시연 중에는 실제 값을 쓰지 않는다
        }
        scope.launch {
            // 위치는 가까운 쉼터 계산에만 쓰고 저장·로그하지 않는다.
            signals.location.catch { HmgLog.w("Location", "flow 실패 $it") }.collect { g: GeoPoint ->
                _state.update { it.copy(origin = LatLon(g.latitude, g.longitude), originAtMs = monoClock(), originHeading = g.heading) }
                refreshNearby()
            }
        }
        scope.launch { while (true) { delay(STEER_SAMPLE_MS); sampleSteering() } }
        scope.launch { while (true) { delay(TICK_MS); tick() } }
        if (debuggable) simReceiver = SimReceiver.register(context, this, monoClock)
    }

    // ---------- 이벤트 배선 ----------
    /** 이벤트를 [WakeFlow] 에 넣고 나온 효과를 실행한다(효과는 상태 갱신 람다 밖에서: 람다는 재시도될 수 있다). */
    internal fun dispatch(e: WakeEvent) {
        var fx: List<WakeEffect> = emptyList()
        _state.update { st -> WakeFlow.reduce(st.wake, e).let { (w, f) -> fx = f; st.copy(wake = w) } }
        fx.forEach { runner.run(it) }
    }

    private fun applyDrive() {
        val d = if (coreDrive == DriveState.DRIVING || uiDrive == DriveState.DRIVING) DriveState.DRIVING else DriveState.PARKED
        if (_state.value.drive == d) return
        _state.update { it.copy(drive = d) }
        dispatch(WakeEvent.DriveChanged(d == DriveState.DRIVING))
    }

    /** 화면 쪽 UX 제한(provider)까지 반영한 주행 판정. 사용자 조작이 아닌 시스템 상태 반영. */
    fun onUiDriveState(d: DriveState) { uiDrive = d; applyDrive() }

    private suspend fun loadShelters() {
        val ds = withContext(Dispatchers.IO) { loadShelterDataset(context.assets::open) }
        _state.update { it.copy(dataset = ds) }
        refreshNearby()
    }

    private fun refreshNearby() {
        _state.update { st ->
            val o = st.origin; val ds = st.dataset
            st.copy(nearby = if (o != null && ds != null) ShelterRepo.nearest(o, ds.shelters, 3, headingDeg = st.originHeading) else emptyList())
        }
    }

    private fun onHvac(snap: com.hmgcarapp.wakemate.vehicle.HvacSnapshot) {
        // 읽기 가능한 첫 값(null→값)은 시작 시각으로만 쓰고 사용자 조작으로 보지 않는다.
        recircClock.onState(snap.now.recirc, monoClock())
        _state.update { it.copy(hvac = snap) }
    }

    private fun addSpeed(s: SpeedSample) {
        speedBuf.addLast(s)
        while (speedBuf.isNotEmpty() && s.tMs - speedBuf.first().tMs > BUF_KEEP_MS) speedBuf.removeFirst()
    }

    /** 고정 주기 조향 표집: 40km/h 이상 주행 중이고 최근 값이 있을 때만(정차·저속 조향은 제외). */
    private fun sampleSteering() {
        val now = monoClock()
        val a = lastAngle ?: return
        val sp = lastSpeed ?: return
        if (_state.value.drive != DriveState.DRIVING || sp < SpeedAnalyzer.MIN_KMH || now - lastAngleAt > ANGLE_FRESH_MS) return
        steerBuf.addLast(SteerSample(now, a))
        while (steerBuf.isNotEmpty() && now - steerBuf.first().tMs > BUF_KEEP_MS) steerBuf.removeFirst()
    }

    // ---------- 위험지수 ----------
    private fun minuteOfDay(): Int {
        _state.value.sim.minuteOfDay?.let { return it }
        val c = Calendar.getInstance().apply { timeInMillis = wallClock() }
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    private fun tick() {
        if (!restored) return
        val now = monoClock()
        if (!LocationFreshness.isFresh(_state.value.originAtMs, now) && _state.value.origin != null) _state.update { it.copy(origin = null, nearby = emptyList()) } // 오래된 위치는 미확인
        val st0 = _state.value
        val driving = st0.drive == DriveState.DRIVING
        driveClock.tick(now, driving)?.let { handleRest(it) }
        if (driveClock.drivingMs == 0L && _state.value.sim.extraDriveMin != 0) simUpdate { it.copy(extraDriveMin = 0) } // 누적이 0 이면 시연 가산분 정리
        val sim = st0.sim
        val recircMs = recircClock.durationMs(now)?.let { it + sim.extraRecircMin * 60_000L } ?: (if (sim.extraRecircMin > 0) sim.extraRecircMin * 60_000L else null)
        val steerList = steerBuf.toList(); val speedList = speedBuf.toList()
        val corr = SteeringAnalyzer.corrections(steerList, now)
        val sd = SpeedAnalyzer.detrendedSd(speedList, now)
        val risk = RiskEngine.assess(RiskInputs(minuteOfDay(), driveClock.drivingMs, recircMs, corr, sd), st0.p.calibration.thresholds)
        _state.update { it.copy(risk = risk, drivingMin = (driveClock.drivingMs / 60_000L).toInt(), recircMin = recircMs?.let { m -> (m / 60_000L).toInt() }, steerReady = corr != null) }
        dispatch(WakeEvent.RiskUpdated(now, risk.score, risk.stage))
        if (now - lastPersistTick >= PERSIST_EVERY_MS) { lastPersistTick = now; scheduleSave() }
    }
    private var lastPersistTick = 0L

    private fun handleRest(ev: RestEvent) {
        val rec = RestRecord(wallClock(), (ev.drivingMsBefore / 60_000L).toInt(), (ev.restMs / 60_000L).toInt(), sim = _state.value.sim.extraDriveMin > 0) // 시연 가산분이 섞였으면 시연 기록
        completeRest(rec)
    }

    private fun completeRest(rec: RestRecord) {
        steerBuf.clear(); speedBuf.clear()
        simUpdate { it.copy(extraDriveMin = 0) } // 휴식으로 누적이 0 이 되면 시연 가산분도 함께 정리
        updateP { it.copy(rests = (it.rests + rec).takeLast(StateCodec.MAX_RESTS)) }
        dispatch(WakeEvent.Rested)
        dispatch(WakeEvent.Notify("휴식을 기록했어요. 주행 시간을 처음부터 다시 셉니다"))
    }

    // ---------- 사용자 조작 (상태를 바꾸는 공개 메서드는 모두 DRIVING 가드) ----------
    private inline fun parkedOnly(block: () -> Unit) { if (_state.value.drive == DriveState.DRIVING) return; block() }
    private fun updateP(f: (Persisted) -> Persisted) {
        if (!restored) return
        _state.update { it.copy(p = f(it.p)) }
        scheduleSave()
    }

    fun setPage(p: Page) = parkedOnly { _state.update { it.copy(page = p) } }
    fun setVoiceReply(on: Boolean) = parkedOnly { updateP { it.copy(settings = it.settings.copy(voiceReply = on)) }; pushOptions() }
    fun setHvacControl(on: Boolean) = parkedOnly { updateP { it.copy(settings = it.settings.copy(hvacControl = on)) }; pushOptions() }
    fun setBeep(on: Boolean) = parkedOnly { updateP { it.copy(settings = it.settings.copy(beep = on)) } }
    private fun pushOptions() { val s = _state.value.p.settings; dispatch(WakeEvent.Options(s.voiceReply, s.hvacControl)) }

    /** 터치로 '졸리지 않아요'(정차 시). 음성으로는 깨우기 질문(외기 제안이 아닌 경우)에 '괜찮아'라고 답한다. 첫 1단계 외기 제안에서 단독 '괜찮아요'는 거절이라 보정되지 않는다. */
    fun notSleepy() = parkedOnly { applyNotSleepy(byVoice = false) }

    /** 보정 적용(음성 응답 경로는 시스템 주도라 가드 없음: 졸음 경고 응답은 주행 중에도 받아야 한다). */
    private fun applyNotSleepy(byVoice: Boolean) {
        if (!restored) return
        updateP { it.copy(calibration = it.calibration.afterNotSleepy()) }
        if (!byVoice) dispatch(WakeEvent.Notify("기준을 조금 높였어요(개인 보정 ${_state.value.p.calibration.offset})"))
    }

    fun resetCalibration() = parkedOnly { updateP { it.copy(calibration = Calibration()) } }
    fun answerVent(accept: Boolean) = parkedOnly { dispatch(if (accept) WakeEvent.VentAccept else WakeEvent.VentDecline) }
    fun startQuizPractice() = parkedOnly { dispatch(WakeEvent.StartQuizPractice) }
    fun guide(shelterId: String) = parkedOnly { runner.guide(shelterId) }
    fun confirmReplace() = parkedOnly { dispatch(WakeEvent.ReplaceConfirmed) }
    fun cancelReplace() = dispatch(WakeEvent.ReplaceCancelled)
    fun dismissAlert() = dispatch(WakeEvent.Dismiss)
    fun clearMessage() = dispatch(WakeEvent.MessageConsumed)

    private fun recordQuiz(o: Quiz.Outcome, ms: Long) {
        updateP { it.copy(quiz = it.quiz.copy(total = it.quiz.total + 1, passed = it.quiz.passed + if (Quiz.alert(o)) 1 else 0, lastResponseMs = if (o == Quiz.Outcome.NO_ANSWER || o == Quiz.Outcome.WRONG) it.quiz.lastResponseMs else ms)) }
    }

    /** 저장된 모든 기록(설정 제외 아님: 보정·휴식·퀴즈·누적 주행)을 지우고 설정만 기본값으로 다시 기록한다. */
    fun clearAllData() = parkedOnly {
        if (!restored) return@parkedOnly
        saveJob?.cancel()
        driveClock.reset()
        _state.update { it.copy(p = Persisted(settings = it.p.settings)) }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { stateStore.clearAll { persistedCopy() } }
            _state.update { it.copy(saveHeld = !stateStore.writable, corruptKept = false) }
            dispatch(WakeEvent.Notify(if (ok) "저장된 기록을 모두 지웠어요" else "삭제하지 못했어요. 잠시 후 다시 시도해 주세요"))
        }
    }

    // ---------- 시연(SimReceiver 용: 시스템 주도, 주행 가드 없음, debuggable 전용) ----------
    internal fun simUpdate(f: (SimInfo) -> SimInfo) { if (debuggable) _state.update { it.copy(sim = f(it.sim)) } }

    internal fun simPattern(p: SimPattern?) {
        simJob?.cancel(); simJob = null
        if (p == null) { signals.hub.reset(); simUpdate { it.copy(pattern = null, hubOn = false) }; return }
        val h = signals.hub
        h.setEnabled(true); h.setGear(Gear.DRIVE)
        simUpdate { it.copy(pattern = p, hubOn = true) }
        val t0 = monoClock()
        simJob = scope.launch {
            while (true) {
                val t = monoClock() - t0
                val s = SimPatterns.sample(p, t)
                h.setSpeedKmh(s.speedKmh)
                lastAngle = s.steerDeg; lastAngleAt = monoClock() // 표본은 sampleSteering 이 고정 주기로 취한다
                delay(SIM_STEP_MS)
            }
        }
    }

    internal fun simHub(enabled: Boolean?, speed: Float?, gear: Gear?, loc: GeoPoint?) {
        val h = signals.hub
        enabled?.let { h.setEnabled(it); simUpdate { s -> s.copy(hubOn = it) } }
        speed?.let { h.setSpeedKmh(it) }
        gear?.let { h.setGear(it) }
        loc?.let { h.setLocation(it) }
    }

    internal fun simSteer(deg: Float) { lastAngle = deg; lastAngleAt = monoClock(); simUpdate { it.copy(injected = true) } }
    internal fun simMinute(m: Int?) = simUpdate { it.copy(minuteOfDay = m) }
    internal fun simAddDrive(min: Int) { driveClock.addSimulated(min * 60_000L); simUpdate { it.copy(extraDriveMin = it.extraDriveMin + min) } }
    internal fun simRecirc(min: Int) = simUpdate { it.copy(extraRecircMin = min) }
    internal fun simStage(stage: Stage) { simUpdate { it.copy(injected = true) }; dispatch(WakeEvent.ForceStage(stage)) }
    internal fun simHeard(text: String) { simUpdate { it.copy(injected = true) }; dispatch(WakeEvent.SimHeard(text)) }
    internal fun simRest() {
        val min = (driveClock.drivingMs / 60_000L).toInt()
        driveClock.reset()
        simUpdate { it.copy(injected = true) }
        completeRest(RestRecord(wallClock(), min, 15, sim = true))
    }
    internal fun simReset() {
        simPattern(null); simUpdate { SimInfo() }
        driveClock.reset(); steerBuf.clear(); speedBuf.clear()
        dispatch(WakeEvent.Rested)
    }

    // ---------- 저장/복원 ----------
    private suspend fun restore() {
        var res = StateStore.Restored(Persisted(), corrupt = false, quarantined = false, readError = false)
        try {
            res = withContext(Dispatchers.IO) { stateStore.restore(wallClock()) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            HmgLog.w("Store", "복원 실패 -> 기본값, 이번 세션 저장 보류: $t")
            stateStore.holdWrites()
            res = res.copy(readError = true)
        } finally {
            applyRestored(res)
        }
    }

    private fun applyRestored(res: StateStore.Restored) {
        if (res.corrupt) HmgLog.w("Store", "저장 파일 손상 -> 기본값으로 시작(보존=${res.quarantined})")
        if (res.readError) HmgLog.w("Store", "저장 파일 읽기 오류 -> 기본값으로 동작, 이번 세션 저장 보류(원본 유지)")
        // 누적 주행 시간은 저장 뒤 15분 넘게 지났으면 휴식한 것으로 보고 버린다(벽시계는 이 저장 만료에만 사용)
        driveClock.restore(DriveRestore.restoredDrivingMs(res.p.drivingMs, res.p.savedAtWallMs, wallClock()))
        _state.update { it.copy(p = res.p, loading = false, saveHeld = res.readError, corruptKept = res.corrupt && res.quarantined) }
        pushOptions()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300)
            if (!restored) return@launch
            onWriteResult(withContext(Dispatchers.IO + NonCancellable) { stateStore.writeLatest { persistedCopy() } })
        }
    }

    private fun onWriteResult(r: WriteResult) {
        when (r) {
            WriteResult.WROTE -> _state.update { if (it.corruptKept) it.copy(corruptKept = false) else it }
            WriteResult.FAILED -> { _state.update { it.copy(saveHeld = true) }; dispatch(WakeEvent.Notify("저장하지 못해 이번에는 저장하지 않아요")) }
            WriteResult.UNCHANGED, WriteResult.HELD -> Unit
        }
    }

    /** 디스크에 쓸 사본: 현재 누적 주행 시간과 저장 시각(벽시계)을 붙인다. 위치·좌표는 포함하지 않는다. */
    private fun persistedCopy(): Persisted = _state.value.p.copy(drivingMs = DrivePersist.persistable(driveClock.drivingMs, _state.value.sim.extraDriveMin * 60_000L), savedAtWallMs = wallClock())

    fun saveNow() {
        if (!restored) return
        saveJob?.cancel()
        onWriteResult(stateStore.writeLatest { persistedCopy() })
    }

    /** 앱 시작/재개 시 공조 연결 재시도·내비 재연결. */
    fun onAppStart() { hvac.retryNow(); navi.connect() }

    fun release() {
        dispatch(WakeEvent.AppReleased)
        saveNow()
        simReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        simJob?.cancel()
        runner.cancelVoice()
        hvac.close(); navi.release(); speaker.release(); listener.release()
        scope.cancel()
    }

    companion object {
        const val SHELTER_ASSET = "rest_shelters.json"
        const val REST_AREA_ASSET = "rest_areas.json"
        const val SHELTER_SAMPLE_ASSET = "rest_shelters_sample.json"

        /**
         * 졸음쉼터(필수)+휴게소(선택) 실데이터를 합친다. 졸음쉼터 파일이 없거나 파싱 실패·0건이면 가상 샘플로 폴백하고,
         * 휴게소 파일만 실패하면 졸음쉼터만 쓴다. 모두 실패하면 null(화면에 '불러오지 못했어요').
         */
        fun loadShelterDataset(open: (String) -> java.io.InputStream): ShelterDataset? {
            fun read(name: String, prefix: String): ShelterDataset? =
                try { open(name).bufferedReader(Charsets.UTF_8).use { ShelterRepo.parse(it.readText(), prefix) }.takeIf { it.shelters.isNotEmpty() } }
                catch (t: Throwable) { HmgLog.w("Shelter", "쉼터 데이터 읽기 실패: ${t.javaClass.simpleName}"); null }
            val real = read(SHELTER_ASSET, "S")
            if (real != null) return read(REST_AREA_ASSET, "R")?.let { real + it } ?: real
            return read(SHELTER_SAMPLE_ASSET, "S")
        }
        const val TICK_MS = 1_000L
        const val STEER_SAMPLE_MS = 500L
        const val ANGLE_FRESH_MS = 5_000L
        const val BUF_KEEP_MS = 120_000L
        const val PERSIST_EVERY_MS = 30_000L
        const val SIM_STEP_MS = 100L
    }
}

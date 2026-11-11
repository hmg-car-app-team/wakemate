package com.hmgcarapp.wakemate.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import com.hmgcarapp.core.connection.ConnectionPolicy
import com.hmgcarapp.core.connection.ManagedConnection
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.wakemate.domain.NaviAction
import com.hmgcarapp.wakemate.domain.NaviPort
import com.hmgcarapp.wakemate.domain.NaviReducer
import com.hmgcarapp.wakemate.domain.NaviStatus
import com.hmgcarapp.wakemate.domain.NaviText
import com.hmgcarapp.wakemate.domain.RouteMode
import com.hmgcarapp.wakemate.domain.RouteStateProvider
import com.hmgcarapp.wakemate.domain.Shelter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * NaviHelper 3.0.5 래퍼. 결과는 이벤트 콜백으로만 오므로 타임아웃(6초) 안에 확인 이벤트가 없으면 "응답 없음"으로 기록한다.
 * 사용 API: initialize, getRouteStateInfo(활성 경로 확인), addWaypoint(활성 경로에 경유지), requestRoute(후보를 직접 안내),
 * requestRouteWithWaypoints(시연 목적지가 설정된 경우만). 이벤트는 요청이 Pending 일 때만 상태에 반영한다.
 * SDK 이벤트에는 요청 ID 가 없으므로, 요청 처리 중에는 새 요청을 받지 않고(겹침 방지) 타임아웃은 요청 번호로 대조한다.
 * 한계: 타임아웃 뒤 매우 늦게 도착한 이전 요청의 이벤트가 곧바로 시작한 새 요청에 귀속될 수 있다(SDK 한계).
 * [debuggable] 이 아니면 쉼터 이름을 로그에 남기지 않는다.
 */
class NaviController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val debuggable: Boolean = false,
) : RouteStateProvider, NaviPort {
    private val _status = MutableStateFlow<NaviStatus>(NaviStatus.Idle)
    val status: StateFlow<NaviStatus> = _status
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    // SDK 콜백 스레드와 공유
    @Volatile private var routeQuery: CompletableDeferred<Boolean?>? = null
    @Volatile private var timeoutJob: Job? = null
    /** release() 뒤에는 connect() 를 하지 않는다(해제된 컨트롤러가 SDK 를 다시 붙잡지 않게). */
    @Volatile private var released = false
    private val requests = NaviReducer.Sequencer()
    /** 경로 상태 질의는 한 번에 하나만(겹치면 먼저 건 질의의 deferred 가 덮여 무응답이 되던 문제). */
    private val queryMutex = Mutex()

    private fun note(s: String) { HmgLog.i("Navi", s); _log.update { (it + s).takeLast(12) } }
    private fun name(s: String) = if (debuggable) s else "(비공개)"

    private val listener = object : NaviHelperEventListener {
        override fun onRoutingCompleted() { note("onRoutingCompleted"); conn.onResponseNow(); transition { NaviReducer.onSuccess(it, NaviText.ROUTE_STARTED) } }
        override fun onWaypointChanged(info: WaypointChangedInfo) { note("onWaypointChanged"); conn.onResponseNow(); transition { NaviReducer.onSuccess(it, NaviText.WAYPOINT_ADDED) } }
        override fun onDestinationChanged() { note("onDestinationChanged") }
        override fun onError(code: NaviErrorCode) { note("onError $code"); conn.onResponseNow(); transition { NaviReducer.onError(it, code.name) } }
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            note("onRouteStateInfo isRouting=${routeStateInfo.isRouting}")
            routeQuery?.complete(routeStateInfo.isRouting)
        }
    }

    /** 이전 요청의 낡은 결과를 지운다(처리 중인 요청은 유지). */
    override fun clearFinished() { _status.update { NaviReducer.settle(it) } }

    /** 요청 [id] 의 낡은 결과만 지운다(다른 요청의 결과·처리 중 요청은 유지). */
    override fun clearFinished(id: Long) { _status.update { NaviReducer.settleFor(it, id) } }

    /** 상태 전이를 적용하고 Pending 을 벗어났으면 타임아웃을 취소한다. */
    private fun transition(f: (NaviStatus) -> NaviStatus) {
        var leftPending = false
        _status.update { before -> val after = f(before); leftPending = after !== before && after !is NaviStatus.Pending; after }
        if (leftPending) timeoutJob?.cancel()
    }

    private fun fail(msg: String, id: Long = 0L) { timeoutJob?.cancel(); _status.value = NaviStatus.Failed(msg, id) }

    /**
     * helper 연결([ManagedConnection]): 준비(initialize 성공 콜백) 전까지 [NaviConnectRules.STALL_MS] 이상 콜백이 없거나 오류 상태면
     * 폐기 후 다시 만든다(백오프 없이 즉시). 요청 타임아웃이 연속 2회면 무응답 사망으로 보고 같은 경로로 교체한다.
     */
    private val conn: ManagedConnection<NaviHelper> = ManagedConnection(
        tag = "Navi",
        create = { NaviHelper(context.applicationContext) },
        open = { h, gen ->
            h.initialize(
                {
                    // 해제/재연결 뒤 늦게 도착한 콜백이면 무시(해제된 helper 에 리스너 등록 금지)
                    // 리스너를 먼저 붙인 뒤에 준비 완료로 표시한다(요청이 리스너 없이 나가지 않게)
                    conn.ifCurrent(gen, stale = { note("늦은 연결 콜백 무시") }) {
                        try { h.addListener(listener); conn.markReady(gen); note("NaviHelper 연결됨") }
                        catch (t: Throwable) { conn.fail(gen); note("리스너 등록 실패: ${t.javaClass.simpleName}") }
                    }
                    Unit
                },
                { t -> conn.ifCurrent(gen) { conn.fail(gen); note("NaviHelper 연결 실패: ${t.javaClass.simpleName}") }; Unit },
            )
        },
        dispose = { h ->
            runCatching { h.removeListener(listener) }
            runCatching { h.release() }
            routeQuery?.complete(null)
        },
        isConnectionFailure = { true },
        clock = android.os.SystemClock::elapsedRealtime,
        policy = NaviConnectRules.POLICY,
        onCreateFailed = { t -> note("NaviHelper 생성 실패: ${t.javaClass.simpleName}") },
        log = { note(it) },
    )

    /** 이미 연결됐거나 연결 중이면 아무것도 하지 않는다(중복 방지). 연결 실패 상태면 정리 후 다시 시도한다. */
    fun connect() {
        if (released) return
        conn.connect()
    }

    /**
     * 요청 예외·연속 타임아웃으로 helper 를 버린 직후 곧바로 새로 연결을 시작한다(r7R5-2): 다음 요청이 연결 대기(최대 2초)부터 시작하지 않게.
     * 콜백 없이 정체한 연결은 [connect] 가 10초 뒤 다시 재생성하므로 반복 폭주는 없다(요청이 있을 때만 호출되는 경로).
     */
    private fun discardAndReconnect() { conn.invalidate(); connect() }

    fun release() {
        released = true
        conn.shutdown()
    }

    /** 연결돼 있지 않으면 재연결을 시도하고 최대 [waitMs] 기다린다. */
    private suspend fun ensureConnected(waitMs: Long = 2000L): Boolean {
        if (released) return false
        if (conn.readyConn() != null) return true
        connect()
        var waited = 0L
        while (conn.current() != null && !conn.isReady() && !conn.isLost() && waited < waitMs) { delay(100); waited += 100 }
        return conn.readyConn() != null
    }

    /** 활성 안내 경로 여부(getRouteStateInfo). 미연결·예외·무응답은 UNKNOWN. 최대 ≈4초(연결 대기 2초 + 응답 대기 2초). */
    override suspend fun routeMode(): RouteMode = queryMutex.withLock {
        if (!ensureConnected()) return@withLock RouteMode.UNKNOWN
        val h = conn.readyConn()?.value ?: return@withLock RouteMode.UNKNOWN
        val d = CompletableDeferred<Boolean?>()
        routeQuery = d
        try {
            h.getRouteStateInfo()
            when (withTimeoutOrNull(2000L) { d.await() }) {
                true -> RouteMode.ACTIVE
                false -> RouteMode.NONE
                null -> RouteMode.UNKNOWN
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            note("경로 상태 질의 예외: ${t.javaClass.simpleName}"); discardAndReconnect(); RouteMode.UNKNOWN
        } finally {
            if (routeQuery === d) routeQuery = null
        }
    }

    /**
     * [action] 에 따라 후보를 내비에 넘긴다. 요청을 보낸 시점에 반환한다(결과는 [awaitResult]).
     * 처리 중인 요청이 있으면 새 요청을 보내지 않고 [NaviReducer.IGNORED] 를 반환한다. 아니면 이 요청의 번호를 반환한다.
     */
    override suspend fun routeToShelter(c: Shelter, action: NaviAction): Long {
        if (!NaviReducer.canStart(_status.value)) { note("이전 요청 처리 중 - 새 요청 무시"); return NaviReducer.IGNORED }
        val failId = requests.next()
        if (!ensureConnected()) {
            note("미연결: 연결 대기 중/서비스 없음")
            if (!NaviReducer.canStart(_status.value)) return NaviReducer.IGNORED
            _status.update { NaviReducer.failUnlessPending(it, NaviText.NOT_CONNECTED, failId) }
            return failId
        }
        if (!NaviReducer.canStart(_status.value)) { note("이전 요청 처리 중 - 새 요청 무시"); return NaviReducer.IGNORED }
        val h = conn.readyConn()?.value ?: run { fail(NaviText.NOT_CONNECTED, failId); return failId }
        val wp = RequestWaypointInfo(latitude = c.pos.lat, longitude = c.pos.lon, poiName = c.name, waypointIndex = WaypointIndex.FIRST, poiId = c.id, poiSubId = "0", address = c.roadName.ifEmpty { c.name })
        val id = failId
        try {
            when (action) {
                NaviAction.ADD_WAYPOINT -> {
                    note("addWaypoint via=${name(c.name)}")
                    _status.update { NaviStatus.Pending(NaviText.PENDING_WAYPOINT, id) }
                    h.addWaypoint(wp)
                }
                NaviAction.REQUEST_ROUTE -> {
                    note("requestRoute to=${name(c.name)}")
                    _status.update { NaviStatus.Pending(NaviText.PENDING_ROUTE, id) }
                    h.requestRoute(RouteInfo(latitude = c.pos.lat, longitude = c.pos.lon, poiName = c.name, poiId = c.id, poiSubId = "0", address = c.roadName.ifEmpty { c.name }, routeOption = RouteOption.RECOMMENDED))
                }
            }
            timeoutJob?.cancel()
            timeoutJob = scope.launch {
                delay(6000)
                if ((_status.value as? NaviStatus.Pending)?.id == id) {
                    note("6초 내 응답 이벤트 없음"); transition { NaviReducer.onTimeout(it, id) }
                    if (conn.onSilentCycle()) { note("연속 타임아웃 - helper 재생성"); discardAndReconnect() }
                }
            }
        } catch (t: Throwable) {
            note("요청 예외: ${t.javaClass.simpleName}"); discardAndReconnect(); fail(NaviText.REQUEST_FAILED, id)
        }
        return id
    }

    /**
     * **요청 [id] 자신의** 결과(성공/실패)를 최대 [timeoutMs] 기다린다. 무시된 요청([NaviReducer.IGNORED])은 '처리 중' 실패를,
     * 시간 안에 못 받으면 null. 다른 요청의 이전 결과를 자기 것으로 받지 않는다.
     */
    override suspend fun awaitResult(id: Long, timeoutMs: Long): NaviStatus? {
        if (id == NaviReducer.IGNORED) return NaviStatus.Failed(NaviText.BUSY)
        return withTimeoutOrNull(timeoutMs) { _status.first { NaviReducer.resultFor(it, id) != null } }
    }
}

/** 내비 연결 상수. 연결 재생성 판단은 core `ManagedConnection` 이 한다. */
object NaviConnectRules {
    const val STALL_MS = 10_000L
    const val TIMEOUTS_TO_DISCARD = 2
    val POLICY = com.hmgcarapp.core.connection.ConnectionPolicy(
        backoffStartMs = 0L, backoffMaxMs = 0L, deathWaitsBackoff = false,
        readyTimeoutMs = STALL_MS, silenceCycles = TIMEOUTS_TO_DISCARD,
    )
}

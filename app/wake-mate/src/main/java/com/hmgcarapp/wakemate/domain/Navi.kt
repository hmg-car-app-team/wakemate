package com.hmgcarapp.wakemate.domain

import kotlinx.coroutines.withTimeoutOrNull

/** 내비에 활성 안내 경로가 있는지. UNKNOWN = 확인 실패/무응답(기존 경로를 덮어쓸 수 있다고 본다). */
enum class RouteMode { ACTIVE, NONE, UNKNOWN }

/** 쉼터를 내비에 넘기는 방법. */
enum class NaviAction { ADD_WAYPOINT, REQUEST_ROUTE }

fun interface RouteStateProvider { suspend fun routeMode(): RouteMode }

data class RoutePlan(val mode: RouteMode, val action: NaviAction, val ask: String) {
    /** 확인 불가라 기존 경로를 덮어쓸 수 있는 요청인지(단음절 음성 수락 금지·수동 확인 팝업 대상). */
    val canOverwrite: Boolean get() = mode == RouteMode.UNKNOWN && action == NaviAction.REQUEST_ROUTE
}

object RoutePlans {
    const val ASK_VIA = "경유지로 추가할까요?"
    const val ASK_START = "안내를 시작할까요?"
    const val ASK_REPLACE = "현재 경로를 바꿀까요? 바꾸려면 '바꿔줘'라고 말씀해 주세요"

    fun decide(mode: RouteMode): RoutePlan = when (mode) {
        RouteMode.ACTIVE -> RoutePlan(mode, NaviAction.ADD_WAYPOINT, ASK_VIA)
        RouteMode.NONE -> RoutePlan(mode, NaviAction.REQUEST_ROUTE, ASK_START)
        RouteMode.UNKNOWN -> RoutePlan(mode, NaviAction.REQUEST_ROUTE, ASK_REPLACE)
    }

    fun buttonLabel(plan: RoutePlan?): String = when {
        plan == null -> "쉼터로 안내"
        plan.action == NaviAction.ADD_WAYPOINT -> "경유지로 추가"
        plan.canOverwrite -> "현재 경로를 바꿔 안내"
        else -> "쉼터로 안내 시작"
    }
}

/** 활성 경로를 확인한다. 실패·예외·시간 초과는 모두 UNKNOWN. */
class RouteAdvisor(private val provider: RouteStateProvider, private val timeoutMs: Long = 2000L) {
    suspend fun plan(): RoutePlan {
        val mode = try {
            withTimeoutOrNull(timeoutMs) { provider.routeMode() } ?: RouteMode.UNKNOWN
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            RouteMode.UNKNOWN
        }
        return RoutePlans.decide(mode)
    }
}

/** 경로/경유지 요청 진행 상태(성공한 척 금지). */
sealed interface NaviStatus {
    data object Idle : NaviStatus
    data class Pending(val what: String, val id: Long = 0L) : NaviStatus
    data class Success(val what: String, val id: Long = 0L) : NaviStatus
    data class Failed(val reason: String, val id: Long = 0L) : NaviStatus
}

/** 내비 이벤트 -> 상태 전이(순수). 요청이 Pending 일 때만 상태를 바꾼다. */
object NaviReducer {
    const val IGNORED = 0L
    fun onSuccess(cur: NaviStatus, msg: String): NaviStatus = if (cur is NaviStatus.Pending) NaviStatus.Success(msg, cur.id) else cur
    fun onError(cur: NaviStatus, codeName: String): NaviStatus = if (cur is NaviStatus.Pending) NaviStatus.Failed(NaviText.errorMessage(codeName), cur.id) else cur
    fun onTimeout(cur: NaviStatus, id: Long? = null): NaviStatus =
        if (cur is NaviStatus.Pending && (id == null || cur.id == id)) NaviStatus.Failed(NaviText.NO_RESPONSE, cur.id) else cur
    fun failUnlessPending(cur: NaviStatus, msg: String, id: Long): NaviStatus = if (cur is NaviStatus.Pending) cur else NaviStatus.Failed(msg, id)
    fun settle(cur: NaviStatus): NaviStatus = if (cur is NaviStatus.Pending) cur else NaviStatus.Idle
    fun settleFor(cur: NaviStatus, id: Long): NaviStatus = when {
        id == IGNORED -> cur
        cur is NaviStatus.Success && cur.id == id -> NaviStatus.Idle
        cur is NaviStatus.Failed && cur.id == id -> NaviStatus.Idle
        else -> cur
    }
    fun canStart(cur: NaviStatus): Boolean = cur !is NaviStatus.Pending
    fun resultFor(cur: NaviStatus, id: Long): NaviStatus? = when {
        id == IGNORED -> null
        cur is NaviStatus.Success && cur.id == id -> cur
        cur is NaviStatus.Failed && cur.id == id -> cur
        else -> null
    }

    class Sequencer { private var last = 0L; @Synchronized fun next(): Long = ++last }
}

object NaviText {
    const val NOT_CONNECTED = "요청에 실패했어요"
    /** 무응답은 사용자 문구가 없다(빈 문자열 = 표시·발화 안 함). 확정 실패만 [REQUEST_FAILED]/[NOT_CONNECTED]. */
    const val NO_RESPONSE = ""
    const val REQUEST_FAILED = "요청에 실패했어요"
    const val ROUTE_STARTED = "쉼터 안내를 시작했어요"
    const val WAYPOINT_ADDED = "쉼터를 경유지로 추가했어요"
    const val PENDING_ROUTE = "경로 요청 중"
    const val PENDING_WAYPOINT = "경유지 추가 요청 중"
    const val BUSY = "이전 요청을 처리 중이에요"

    /** 결과를 받지 못한 무응답(시간 초과·결과 없음)인지. 내비가 실제로 안내를 시작했을 수 있어 실패로 표시하지 않는다. */
    fun isNoResponse(result: NaviStatus?): Boolean = result == null || (result is NaviStatus.Failed && result.reason.isBlank())

    fun spoken(result: NaviStatus?): String = when (result) {
        is NaviStatus.Success -> result.what
        is NaviStatus.Failed -> result.reason
        else -> NO_RESPONSE
    }

    /** 원인 코드와 무관하게 한 문구로 통일(원인은 로그에만 남는다). */
    @Suppress("UNUSED_PARAMETER")
    fun errorMessage(codeName: String): String = REQUEST_FAILED

    fun shortWord(st: NaviStatus): String? = when (st) {
        NaviStatus.Idle -> null
        is NaviStatus.Pending -> "요청 중"
        is NaviStatus.Success -> "요청 완료"
        is NaviStatus.Failed -> if (st.reason.isBlank()) "결과 미확인" else "요청 실패"
    }
}

/** 흐름이 쓰는 내비 요청 포트(실제 구현은 NaviController; 테스트는 가짜). */
interface NaviPort {
    suspend fun routeToShelter(s: Shelter, action: NaviAction): Long
    suspend fun awaitResult(id: Long, timeoutMs: Long = 8000L): NaviStatus?
    fun clearFinished()
    fun clearFinished(id: Long)
}

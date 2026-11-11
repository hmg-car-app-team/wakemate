package com.hmgcarapp.core.connection

import com.hmgcarapp.core.log.HmgLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 연결 정책. 모든 시간은 ms, 시계는 [ManagedConnection] 에 주입한 단조 시계 기준.
 *
 * @property backoffStartMs 생성 실패·사망 뒤 재시도 대기 시작값(실패마다 2배, [backoffMaxMs] 상한).
 * @property stableMs 연결이 이만큼 유지된 뒤 사망하면 백오프를 처음으로 되돌린다(연결-즉시끊김 반복 시 폭주 방지).
 * @property silenceCycles 응답 없는 주기가 연속 이만큼이면 연결 사망으로 본다(0=사용 안 함).
 * @property readyTimeoutMs 생성 뒤 [ManagedConnection.markReady] 가 이 시간 안에 없으면 정체로 보고 교체(0=생성 즉시 준비됨).
 * @property deathWaitsBackoff true 면 사망 뒤 백오프만큼 기다렸다가 재생성, false 면 즉시(백오프는 처음으로).
 * @property minReplaceIntervalMs 사망한 연결을 자동으로 교체하는 최소 간격(수동 재시도는 무시).
 * @property minAttemptGapMs 생성 시도(성공·실패 모두) 사이 최소 간격(강제·수동 시도는 무시).
 */
data class ConnectionPolicy(
    val backoffStartMs: Long = 2_000L,
    val backoffMaxMs: Long = 30_000L,
    val stableMs: Long = 10_000L,
    val silenceCycles: Int = 0,
    val readyTimeoutMs: Long = 0L,
    val deathWaitsBackoff: Boolean = true,
    val minReplaceIntervalMs: Long = 0L,
    val minAttemptGapMs: Long = 0L,
)

private val DEFAULT_FAILURE: (Throwable) -> Boolean = { isConnectionFailure(it) }

/** 살아 있는 연결 한 개와 그 세대 번호. */
class Conn<T>(val gen: Int, val value: T)

/**
 * SDK 연결·복구의 단일 구현. 어댑터는 연결을 만드는 법([create]/[open])과 버리는 법([dispose])만 알려주고,
 * 세대 필터·백오프·안정 후 리셋·사망 감지(연결 계열 예외 / 무응답 연속 N회 / 수동 재시도 / 준비 정체)·교체 간격은 여기서만 처리한다.
 *
 * - **세대**: 생성 시도마다 번호가 새로 붙고, 폐기·실패 시 무효화된다. [callback]/[ifCurrent] 로 감싼 콜백은 낡은 세대면 자동 무시된다.
 * - **상태는 연결 단위**: 사망 표시는 연결(세대)에 붙고 교체하면 모두 사라진다. 단, 한 연결 안에서 여러 하위 요청(예: 연료/EV/주행거리)이
 *   섞일 때를 위해 [onFailure]/[onResponse] 에 `scope` 키를 줄 수 있다: 실패 표시는 같은 scope 의 정상 응답으로만 풀린다.
 * - 스레드 안전. [create]/[open]/[dispose] 는 락 밖에서 호출한다(느린 바인더 호출이 다른 스레드를 막지 않게). 동시에 둘 이상 생성하지 않는다.
 * - SDK 콜백 람다는 항상 Unit 을 반환하도록 감싼다(ChannelResult 등을 반환값으로 흘려 NPE 가 나던 문제).
 */
class ManagedConnection<T : Any>(
    private val tag: String,
    private val create: () -> T,
    private val dispose: (T) -> Unit,
    private val isConnectionFailure: (Throwable) -> Boolean = DEFAULT_FAILURE,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val policy: ConnectionPolicy = ConnectionPolicy(),
    /** [create] 직후 호출(초기화·구독 등). 던지면 생성 실패로 보고 [dispose] 후 백오프. 인자: 연결, 세대 번호. */
    private val open: (T, Int) -> Unit = { _, _ -> },
    private val onCreateFailed: (Throwable) -> Unit = {},
    private val log: (String) -> Unit = { HmgLog.w(tag, it) },
) {
    private val lock = Any()
    private var counter = 0
    private var activeGen = 0
    private var conn: Conn<T>? = null
    private var creating = false
    private var shut = false

    private val failScopes = HashSet<Any>()
    private var silenceDead = false
    private var stallLost = false
    private var silentCycles = 0
    private var ready = false
    private var connectedAt = 0L

    private var attemptAt: Long? = null
    private var retryAt = Long.MIN_VALUE
    private var scheduledWait = 0L
    private var lastReplaceAt: Long? = null
    private var backoff = policy.backoffStartMs
    private var manualPending = false
    private var manualHadSilence = false

    /** 사망·수동 재시도 때 신호(코루틴 감시 루프용, 합쳐짐). */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private object NoScope

    // ---------------------------------------------------------------- 조회

    /** 설치된 연결(사망 표시가 있어도 교체 전까지 유지). 부작용 없음. */
    fun current(): Conn<T>? = synchronized(lock) { conn }

    /** 준비 완료([markReady] 또는 [ConnectionPolicy.readyTimeoutMs]==0)이고 사망 표시가 없는 연결. */
    fun readyConn(): Conn<T>? = synchronized(lock) { conn?.takeIf { ready && !lostLocked() } }

    fun isReady(): Boolean = synchronized(lock) { conn != null && ready }

    /** 현재 연결에 사망 표시(연결 실패·무응답·정체)가 있는지. */
    fun isLost(): Boolean = synchronized(lock) { lostLocked() }

    fun isCurrent(gen: Int): Boolean = synchronized(lock) { gen != 0 && gen == activeGen }

    /** 다음 생성 시도까지 남은 시간(ms). 연결이 있으면 0. */
    fun retryDelayMs(): Long = synchronized(lock) { if (conn != null || shut || retryAt == Long.MIN_VALUE) 0L else (retryAt - clock()).coerceAtLeast(0L) }

    private fun lostLocked() = failScopes.isNotEmpty() || silenceDead || stallLost

    // ---------------------------------------------------------------- 콜백 래퍼

    /** 낡은 세대의 콜백을 무시하는 래퍼. 반환값은 항상 Unit. */
    fun <A> callback(gen: Int, f: (A) -> Unit): (A) -> Unit = { a -> if (isCurrent(gen)) f(a); Unit }

    /** 현재 세대일 때만 [f] 실행, 아니면 [stale]. */
    fun ifCurrent(gen: Int, stale: () -> Unit = {}, f: () -> Unit) { if (isCurrent(gen)) f() else stale() }

    // ---------------------------------------------------------------- 사망 감지 입력

    /** 연결 계열 예외면 현재 세대의 [scope] 사망 표시를 켠다(낡은 세대·다른 종류의 오류는 무시). 표시했으면 true. */
    fun onFailure(gen: Int, t: Throwable, scope: Any? = null): Boolean {
        if (!isConnectionFailure(t)) return false
        return fail(gen, scope)
    }

    /** 오류 종류와 무관하게 현재 세대에 사망 표시(연결 시도 자체의 실패 콜백용). */
    fun fail(gen: Int, scope: Any? = null): Boolean {
        synchronized(lock) {
            if (gen == 0 || gen != activeGen) return false
            failScopes += scope ?: NoScope
        }
        wake.trySend(Unit)
        return true
    }

    /** 현재 연결을 사망으로 표시(세대 무관: 요청 예외 등으로 폐기해야 할 때). */
    fun invalidate() {
        synchronized(lock) { if (conn == null && !creating) return; failScopes += NoScope }
        wake.trySend(Unit)
    }

    /** 정상 응답: 같은 세대의 무응답 카운트와 같은 [scope] 의 연결 실패 표시를 지운다(다른 scope·낡은 세대의 응답은 무시). */
    fun onResponse(gen: Int, scope: Any? = null) {
        synchronized(lock) {
            if (gen == 0 || gen != activeGen) return
            clearOnResponseLocked(scope)
        }
    }

    /** 오류 응답처럼 '서비스가 살아 있다'는 신호만 알린다: 무응답 카운트만 0으로(연결 실패 표시는 그대로). */
    fun onAlive(gen: Int) { synchronized(lock) { if (gen != 0 && gen == activeGen) silentCycles = 0 } }

    /** [onResponse] 의 현재 세대 버전(SDK 콜백에 세대 정보가 없는 경우). */
    fun onResponseNow(scope: Any? = null) { synchronized(lock) { clearOnResponseLocked(scope) } }

    private fun clearOnResponseLocked(scope: Any?) {
        silentCycles = 0; silenceDead = false
        failScopes -= (scope ?: NoScope)
    }

    /** 응답 없는 주기 1회를 센다. 연속 [ConnectionPolicy.silenceCycles] 회가 되면 사망 표시를 켜고 true. */
    fun onSilentCycle(): Boolean {
        synchronized(lock) {
            if (policy.silenceCycles <= 0 || conn == null) return false
            silentCycles++
            if (silentCycles < policy.silenceCycles) return false
            silentCycles = 0; silenceDead = true
        }
        wake.trySend(Unit)
        return true
    }

    /** 수동 재시도(앱 재개 등): 사망한 연결은 간격과 무관하게 즉시 교체, 건강한 연결은 유지. 직전에 무응답이 있었으면 사망으로 본다. */
    fun manualRetry() {
        synchronized(lock) {
            manualPending = true
            if (silentCycles > 0) manualHadSilence = true
        }
        wake.trySend(Unit)
    }

    /** 생성 뒤 연결 준비가 끝났음(예: 비동기 initialize 성공 콜백). 낡은 세대는 무시. */
    fun markReady(gen: Int) {
        synchronized(lock) {
            if (gen == 0 || gen != activeGen) return
            ready = true; stallLost = false
        }
    }

    // ---------------------------------------------------------------- 연결 얻기·교체·해제

    private class Plan<T>(val dispose: T?, val existing: Conn<T>?, val createGen: Int)

    /**
     * 연결을 돌려준다. 없고 시도가 허용되면 생성하고, 사망 표시가 있으면 (교체 간격이 허락할 때) 먼저 교체한다.
     * 지금 시도할 수 없거나 실패하면 null. [force] 면 백오프·시도 간격을 건너뛴다.
     */
    fun connect(force: Boolean = false): Conn<T>? {
        val now = clock()
        val plan = synchronized(lock) { planLocked(now, force) }
        plan.dispose?.let { disposeQuietly(it) }
        plan.existing?.let { return it }
        if (plan.createGen == 0) return null
        return doCreate(plan.createGen)
    }

    /** 사망한 연결이 있으면 지금 폐기하고 재시도 시각을 정한다(새로 만들지는 않는다). */
    fun reap() {
        val now = clock()
        val d = synchronized(lock) {
            val manual = takeManualLocked()
            reapLocked(now, manual)
        }
        d?.let { disposeQuietly(it) }
    }

    private fun takeManualLocked(): Boolean {
        val manual = manualPending; manualPending = false
        if (manual && manualHadSilence) silenceDead = true
        manualHadSilence = false
        return manual
    }

    private fun planLocked(now: Long, force: Boolean): Plan<T> {
        if (shut) return Plan(null, null, 0)
        val manual = takeManualLocked()
        val dead = reapLocked(now, manual)
        conn?.let { return Plan(dead, it, 0) }
        if (creating) return Plan(dead, null, 0)
        val gapOk = policy.minAttemptGapMs <= 0 || attemptAt.let { it == null || now - it >= policy.minAttemptGapMs }
        if (!(force || manual || (now >= retryAt && gapOk))) return Plan(dead, null, 0)
        creating = true
        val gen = ++counter
        activeGen = gen
        attemptAt = now
        failScopes.clear(); silenceDead = false; stallLost = false; silentCycles = 0
        ready = policy.readyTimeoutMs <= 0
        wake.tryReceive()
        return Plan(dead, null, gen)
    }

    private fun reapLocked(now: Long, manual: Boolean): T? {
        val c = conn ?: return null
        if (!ready && policy.readyTimeoutMs > 0 && !stallLost) {
            val el = now - connectedAt
            if (el < 0 || el >= policy.readyTimeoutMs) { stallLost = true; log("연결 콜백 ${policy.readyTimeoutMs / 1000}초 무응답 - 재생성") }
        }
        if (!lostLocked()) return null
        val last = lastReplaceAt
        if (!manual && last != null && now - last < policy.minReplaceIntervalMs) return null
        // 교체: 세대를 무효화해 이전 연결의 늦은 콜백을 모두 버린다
        conn = null
        activeGen = ++counter
        failScopes.clear(); silenceDead = false; stallLost = false; silentCycles = 0
        lastReplaceAt = now
        if (manual || !policy.deathWaitsBackoff) {
            backoff = policy.backoffStartMs; retryAt = Long.MIN_VALUE; scheduledWait = 0L
        } else {
            if (now - connectedAt >= policy.stableMs) backoff = policy.backoffStartMs
            scheduleRetryLocked(now)
        }
        log("연결 재생성 요청")
        return c.value
    }

    private fun scheduleRetryLocked(now: Long) {
        scheduledWait = backoff
        retryAt = now + backoff
        backoff = (backoff * 2).coerceAtMost(policy.backoffMaxMs)
    }

    private fun doCreate(gen: Int): Conn<T>? {
        var v: T? = null
        try {
            val created = create(); v = created
            open(created, gen)
        } catch (t: Throwable) {
            v?.let { disposeQuietly(it) }
            synchronized(lock) {
                creating = false
                if (activeGen == gen) activeGen = ++counter
                if (t !is CancellationException) scheduleRetryLocked(clock())
            }
            if (t is CancellationException) throw t
            log("연결 생성 실패: ${t.javaClass.simpleName}")
            onCreateFailed(t)
            return null
        }
        val installed = synchronized(lock) {
            creating = false
            if (shut || activeGen != gen) null else Conn(gen, v).also { conn = it; connectedAt = clock() }
        }
        if (installed == null) { disposeQuietly(v); return null }   // 생성 중 폐기됨: 만든 연결은 버린다
        return installed
    }

    /** 연결을 지금 버린다(다시 [connect] 할 수 있다). 이전 연결의 늦은 콜백은 무시된다. */
    fun disconnect() { detach(terminal = false) }

    /** [disconnect] + 이후 [connect] 불가. */
    fun shutdown() { detach(terminal = true) }

    private fun detach(terminal: Boolean) {
        val c = synchronized(lock) {
            if (terminal) shut = true
            val old = conn
            conn = null
            activeGen = ++counter
            failScopes.clear(); silenceDead = false; stallLost = false; silentCycles = 0
            old
        }
        c?.let { disposeQuietly(it.value) }
    }

    private fun disposeQuietly(v: T) { try { dispose(v) } catch (t: Throwable) { if (t is CancellationException) throw t } }

    // ---------------------------------------------------------------- 코루틴 감시 루프

    /**
     * 푸시형 연결(콜백으로 데이터가 오는 소스)용 루프: 연결이 없으면 (백오프 대기 -> [beforeAttempt] -> 생성), 있으면 사망 표시가 설 때까지 대기.
     * 사망하면 즉시 폐기하고 백오프 뒤 다시 만든다. 취소되면 연결을 해제한다.
     *
     * **계약(r8R5-2)**: `minReplaceIntervalMs=0` 정책 전용이다. 사망했으나 교체할 수 없는 상태(교체 간격 미경과)이거나 외부에서
     * [shutdown] 된 경우 이 루프에는 양보(대기) 지점이 없어 `connect(force=true)` 를 연속 호출하므로, 그런 정책/사용에서는 쓰지 말 것.
     * (현재 사용처: LocationSource·기어 채널(Sources.kt)이며 둘 다 이 정책이다. 코드 로직은 바꾸지 않는다.)
     */
    suspend fun supervise(sleep: suspend (Long) -> Unit = { delay(it) }, beforeAttempt: suspend () -> Unit = {}) {
        try {
            while (currentCoroutineContext().isActive) {
                reap()
                if (current() == null) {
                    val wait = synchronized(lock) { scheduledWait }
                    if (wait > 0) sleep(wait)
                    if (!currentCoroutineContext().isActive) break
                    beforeAttempt()
                    if (!currentCoroutineContext().isActive) break
                    connect(force = true)
                    continue
                }
                while (current() != null && !isLost() && currentCoroutineContext().isActive) wake.receive()
            }
        } finally {
            shutdown()
        }
    }
}

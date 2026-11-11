package com.hmgcarapp.core

import com.hmgcarapp.core.connection.ConnectionPolicy
import com.hmgcarapp.core.connection.ManagedConnection
import com.hmgcarapp.core.connection.isConnectionFailure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** ManagedConnection 단위 테스트(가짜 시계·가짜 연결, 실 SDK/실 시간 없음). */
@OptIn(ExperimentalCoroutinesApi::class)
class ManagedConnectionTests {
    private class Fake(val id: Int)

    /** 가짜 환경: 수동 시계, 생성/폐기 기록, 생성 실패 스위치. */
    private class Env(val policy: ConnectionPolicy = ConnectionPolicy()) {
        var now = 0L
        var failCreate = 0              // 앞으로 이 횟수만큼 생성(open)이 실패
        val created = mutableListOf<Fake>()
        val disposed = mutableListOf<Int>()
        val failed = mutableListOf<Throwable>()
        var openHook: (Fake, Int) -> Unit = { _, _ -> }
        val conn = ManagedConnection(
            tag = "T", create = { Fake(created.size + 1).also { created += it } }, dispose = { disposed += it.id },
            clock = { now }, policy = policy,
            open = { f, g -> if (failCreate > 0) { failCreate--; throw IllegalStateException("init fail") }; openHook(f, g) },
            onCreateFailed = { failed += it }, log = { },
        )
    }
    private val dead = RuntimeException("service disconnected")

    // ---- 연결 실패 -> 백오프(2,4,...,30초) -> 재연결 ----
    @Test fun createFailure_backsOff_2_4_8_16_30_30_thenReconnects() {
        val e = Env(); e.failCreate = 6
        val waits = mutableListOf<Long>()
        assertNull(e.conn.connect())                      // 첫 시도는 즉시, 실패
        repeat(5) {
            waits += e.conn.retryDelayMs()
            assertNull("대기 중에는 시도하지 않는다", e.conn.connect()); e.now += e.conn.retryDelayMs() - 1
            assertNull("1ms 전", e.conn.connect()); e.now += 1
            assertNull(e.conn.connect())                  // 시각 도달: 시도(실패)
        }
        waits += e.conn.retryDelayMs()
        assertEquals(listOf(2000L, 4000L, 8000L, 16_000L, 30_000L, 30_000L), waits)
        e.now += 30_000
        assertNotNull("실패가 끝나면 재연결", e.conn.connect())
        assertEquals(7, e.created.size)
        assertEquals("실패한 시도의 연결은 모두 폐기", (1..6).toList(), e.disposed)
        assertEquals(6, e.failed.size)
    }

    @Test fun connectionFailure_disposesFirst_thenReconnectsAfterBackoff() {
        val e = Env()
        val c1 = e.conn.connect()!!
        assertTrue(e.conn.onFailure(c1.gen, dead))
        assertNull("사망 직후엔 백오프 대기", e.conn.connect())
        assertEquals(listOf(1), e.disposed)               // 폐기가 먼저
        assertEquals(2000L, e.conn.retryDelayMs())
        e.now += 1_999; assertNull(e.conn.connect())
        e.now += 1; val c2 = e.conn.connect()!!
        assertEquals(2, c2.value.id); assertTrue(c2.gen != c1.gen)
    }

    @Test fun nonConnectionError_doesNotKillConnection() {
        val e = Env(); val c = e.conn.connect()!!
        assertFalse(e.conn.onFailure(c.gen, IllegalArgumentException("bad value")))
        assertFalse(e.conn.isLost()); assertEquals(c, e.conn.connect())
        assertTrue(isConnectionFailure(RuntimeException("Service connection lost")))
        assertTrue(isConnectionFailure(RuntimeException("서비스 연결이 끊겼어요")))
        assertFalse(isConnectionFailure(RuntimeException("service unavailable for this query")))
        assertFalse(isConnectionFailure(RuntimeException("bind address in use")))
    }

    // ---- 낡은 세대 콜백 무시 ----
    @Test fun staleGenerationCallbacks_areIgnored_andCannotKillTheNewConnection() {
        val e = Env()
        val got = mutableListOf<Int>()
        var cb1: ((Int) -> Unit)? = null
        e.openHook = { f, g -> if (f.id == 1) cb1 = e.conn.callback(g) { v: Int -> got += v } }
        val c1 = e.conn.connect()!!
        cb1!!(1)
        assertEquals(listOf(1), got)
        e.conn.onFailure(c1.gen, dead); e.conn.reap(); e.now += 2000
        val c2 = e.conn.connect()!!
        cb1!!(2)                                          // 폐기된 세대의 늦은 데이터
        assertEquals("무시", listOf(1), got)
        assertFalse("늦은 연결 오류가 새 연결을 죽이지 않는다", e.conn.onFailure(c1.gen, dead))
        assertFalse(e.conn.isLost()); assertTrue(e.conn.isCurrent(c2.gen)); assertFalse(e.conn.isCurrent(c1.gen))
        e.conn.onResponse(c1.gen)                         // 낡은 세대 응답도 무시(예외 없음)
        assertEquals(2, e.created.size)
    }

    @Test fun callbackWrapper_returnsUnit_andRunsOnlyForCurrentGeneration() {
        val e = Env(); val c = e.conn.connect()!!
        var n = 0
        val f: (String) -> Unit = e.conn.callback(c.gen) { n++ ; "non-unit-ignored" }
        @Suppress("UNCHECKED_CAST") val r = (f as (Any?) -> Any?)("x")
        assertEquals(Unit, r); assertEquals(1, n)
        e.conn.disconnect(); f("y"); assertEquals(1, n)
    }

    // ---- 무응답 N회 -> 교체 ----
    @Test fun silentCycles_replaceConnection_onlyAfterNConsecutive_andAnyResponseResets() {
        val e = Env(ConnectionPolicy(silenceCycles = 3, deathWaitsBackoff = false))
        val c1 = e.conn.connect()!!
        assertFalse(e.conn.onSilentCycle()); assertFalse(e.conn.onSilentCycle())
        e.conn.onResponse(c1.gen)                         // 응답이 오면 0부터
        assertFalse(e.conn.onSilentCycle()); assertFalse(e.conn.onSilentCycle())
        assertEquals("2회까지는 유지", c1, e.conn.connect())
        assertTrue("3회째 사망", e.conn.onSilentCycle())
        val c2 = e.conn.connect()!!
        assertEquals(2, c2.value.id); assertEquals(listOf(1), e.disposed)
        assertFalse("교체되면 카운트는 새로", e.conn.onSilentCycle())
    }

    @Test fun navigationStyle_twoConsecutiveSilentCycles_replace_andCountRestartsAfterReplacement() {
        val e = Env(ConnectionPolicy(silenceCycles = 2, deathWaitsBackoff = false, backoffStartMs = 0, backoffMaxMs = 0))
        val c1 = e.conn.connect()!!
        assertFalse(e.conn.onSilentCycle()); assertTrue(e.conn.onSilentCycle())
        assertEquals(2, e.conn.connect()!!.value.id)
        assertFalse("교체 뒤 다시 센다", e.conn.onSilentCycle())
        e.conn.onResponseNow()                           // 결과 이벤트가 오면 0
        assertFalse(e.conn.onSilentCycle()); assertTrue(e.conn.onSilentCycle())
        assertNotNull(c1)
    }

    @Test fun errorCallbackCountsAsAlive_withoutClearingConnectionFailure() {
        val e = Env(ConnectionPolicy(silenceCycles = 3, deathWaitsBackoff = false))
        val c = e.conn.connect()!!
        e.conn.onSilentCycle(); e.conn.onSilentCycle()
        e.conn.onFailure(c.gen, dead, "fuel"); e.conn.onAlive(c.gen)
        assertFalse("오류 응답으로 무응답 카운트는 0", e.conn.onSilentCycle())
        assertTrue("연결 실패 표시는 남는다", e.conn.isLost())
        assertFalse(isConnectionFailure(RuntimeException("연결 정보 형식 오류")))
    }

    // ---- 수동 재시도 ----
    @Test fun manualRetry_replacesDeadConnectionAtOnce_bypassingIntervalAndBackoff_butKeepsHealthyOne() {
        val e = Env(ConnectionPolicy(deathWaitsBackoff = false, minReplaceIntervalMs = 60_000))
        val c1 = e.conn.connect()!!
        e.conn.onFailure(c1.gen, dead)
        assertEquals("처음 사망은 즉시 교체", 2, e.conn.connect()!!.value.id)
        val c2 = e.conn.current()!!; e.now += 10_000
        e.conn.onFailure(c2.gen, dead)
        assertEquals("60초 이내 자동 교체 금지(사망 연결 유지)", c2, e.conn.connect())
        e.now = 59_999; assertEquals(c2, e.conn.connect())
        e.conn.manualRetry()
        assertEquals("수동 재시도는 즉시 교체", 3, e.conn.connect()!!.value.id)
        // 건강한 연결은 수동 재시도로 교체하지 않는다
        e.conn.manualRetry(); assertEquals(3, e.conn.connect()!!.value.id); assertEquals(3, e.created.size)
    }

    @Test fun manualRetry_afterSilence_replacesEvenIfAResponseArrivedMeanwhile() {
        val e = Env(ConnectionPolicy(silenceCycles = 3, deathWaitsBackoff = false))
        val c1 = e.conn.connect()!!
        e.conn.onSilentCycle()
        e.conn.manualRetry(); e.conn.manualRetry()        // 두 번 눌러도 플래그가 지워지지 않는다
        e.conn.onResponse(c1.gen)
        assertEquals(2, e.conn.connect()!!.value.id)
    }

    // ---- 안정 후 백오프 리셋 ----
    @Test fun backoff_resetsOnlyAfterStable10s_boundary9999_10000() {
        fun secondWait(aliveMs: Long): Long {
            val e = Env()
            val c1 = e.conn.connect()!!; e.conn.onFailure(c1.gen, dead); e.conn.connect()   // 1차 사망 -> 2초 대기
            e.now += 2000; val c2 = e.conn.connect()!!
            e.now += aliveMs; e.conn.onFailure(c2.gen, dead); e.conn.connect()
            return e.conn.retryDelayMs()
        }
        assertEquals(4000L, secondWait(9_999))
        assertEquals(2000L, secondWait(10_000))
    }

    @Test fun createSuccess_doesNotResetBackoffByItself() {
        val e = Env(); e.failCreate = 1
        e.conn.connect(); e.now += 2000                   // 실패 1회(다음 대기 4초)
        val c = e.conn.connect()!!                        // 성공 직후 바로 사망
        e.conn.onFailure(c.gen, dead); e.conn.connect()
        assertEquals(4000L, e.conn.retryDelayMs())
    }

    // ---- 정상 응답은 같은 연결의 연결 실패 표시만 해제 ----
    @Test fun response_clearsOnlyTheSameScopeOfTheSameConnection() {
        val e = Env(ConnectionPolicy(deathWaitsBackoff = false, minReplaceIntervalMs = 60_000))
        val c1 = e.conn.connect()!!
        e.conn.connect(); e.conn.onFailure(c1.gen, dead, scope = "fuel")
        e.conn.onResponse(c1.gen, scope = "ev")           // 다른 scope 의 정상 응답
        assertTrue("다른 scope 응답은 끊김을 지우지 못한다", e.conn.isLost())
        e.conn.onResponse(c1.gen, scope = "fuel")
        assertFalse("같은 scope 의 정상 응답은 해제", e.conn.isLost())
        // 낡은 세대의 응답/오류는 새 연결 상태를 바꾸지 못한다
        e.conn.onFailure(c1.gen, dead, "fuel")
        e.now = 100_000; val c2 = e.conn.connect()!!
        assertFalse(e.conn.isLost())
        e.conn.onResponse(c1.gen, "fuel"); e.conn.onFailure(c1.gen, dead, "fuel")
        assertFalse(e.conn.isLost()); assertTrue(e.conn.isCurrent(c2.gen))
    }

    // ---- 폐기 직후 재생성 경쟁 없음 ----
    @Test fun disconnectDuringCreate_discardsTheNewlyCreatedConnection() {
        val e = Env()
        e.openHook = { _, _ -> e.conn.disconnect() }      // 생성(open) 중에 폐기가 끼어든다
        assertNull(e.conn.connect())
        assertNull(e.conn.current()); assertEquals(listOf(1), e.disposed)
        e.openHook = { _, _ -> }
        assertNotNull(e.conn.connect(force = true)); assertNotNull(e.conn.current())
    }

    @Test fun shutdown_blocksFurtherConnects_andDisposesOnce() {
        val e = Env(); e.conn.connect()
        e.conn.shutdown(); e.conn.shutdown()
        assertNull(e.conn.connect(force = true)); assertEquals(listOf(1), e.disposed)
    }

    @Test fun concurrentConnect_createsExactlyOne() {
        val created = AtomicInteger(0); val gate = CountDownLatch(1)
        val conn = ManagedConnection(
            tag = "T", create = { created.incrementAndGet(); gate.await(); Fake(1) }, dispose = { },
            clock = { 0L }, log = { },
        )
        val pool = Executors.newFixedThreadPool(8)
        val futures = List(8) { pool.submit<Any?> { conn.connect() } }
        Thread.sleep(100); gate.countDown()
        val results = futures.map { it.get() }
        pool.shutdown()
        assertEquals(1, created.get())
        assertEquals("생성한 스레드만 연결을 받는다", 1, results.count { it != null })
        assertNotNull(conn.current())
    }

    // ---- 준비 정체(내비 helper): 콜백이 10초 안에 없으면 교체 ----
    @Test fun readyTimeout_replacesStalledConnection_boundaryAndClockGoingBack() {
        val p = ConnectionPolicy(readyTimeoutMs = 10_000, deathWaitsBackoff = false, backoffStartMs = 0, backoffMaxMs = 0)
        fun env() = Env(p)
        var e = env(); var c = e.conn.connect()!!
        e.now = 9_999; assertEquals("9.999초는 연결 중", c, e.conn.connect())
        e.now = 10_000; assertEquals("10초 무콜백이면 교체", 2, e.conn.connect()!!.value.id)
        e = env(); e.now = 50_000; c = e.conn.connect()!!
        e.now = 10_000; assertEquals("시계가 뒤로 가면 교체", 2, e.conn.connect()!!.value.id)
        e = env(); c = e.conn.connect()!!; e.conn.markReady(c.gen)
        e.now = 99_999; assertEquals("준비된 연결은 유지", c, e.conn.connect()); assertTrue(e.conn.isReady())
        // 낡은 세대의 준비 콜백은 무시
        e = env(); val a = e.conn.connect()!!; e.conn.invalidate(); val b = e.conn.connect()!!
        e.conn.markReady(a.gen); assertFalse(e.conn.isReady())
        e.conn.markReady(b.gen); assertTrue(e.conn.isReady())
    }

    @Test fun createFailureWithZeroBackoff_retriesOnEveryConnect() {
        val e = Env(ConnectionPolicy(backoffStartMs = 0, backoffMaxMs = 0)); e.failCreate = 2
        assertNull(e.conn.connect()); assertNull(e.conn.connect()); assertNotNull(e.conn.connect())
    }

    // ---- 시도 간격(차량 리더·UX: 7초) ----
    @Test fun minAttemptGap_limitsAttempts_forcedAttemptBypasses() {
        val e = Env(ConnectionPolicy(deathWaitsBackoff = false, minAttemptGapMs = 7_000, backoffStartMs = 7_000, backoffMaxMs = 7_000)); e.failCreate = 3
        assertNull(e.conn.connect(force = true))          // 시작 시 강제 시도
        e.now = 6_999; assertNull(e.conn.connect()); assertEquals(1, e.created.size)
        e.now = 7_000; assertNull(e.conn.connect()); assertEquals(2, e.created.size)
        e.now = 13_999; assertNull(e.conn.connect()); assertEquals(2, e.created.size)
        e.now = 14_000; assertNull(e.conn.connect()); assertEquals(3, e.created.size)
        assertNotNull(e.conn.connect(force = true))
    }

    // ---- 감시 루프(기어/위치 같은 푸시형) ----
    @Test fun supervise_reconnectsWithBackoff_andReleasesOnCancel() = runTest {
        val created = mutableListOf<Fake>(); val disposed = mutableListOf<Int>(); val sleeps = mutableListOf<Long>()
        lateinit var conn: ManagedConnection<Fake>
        conn = ManagedConnection(
            tag = "T", create = { Fake(created.size + 1).also { created += it } }, dispose = { disposed += it.id },
            clock = { testScheduler.currentTime }, log = { },
        )
        val job = launch { conn.supervise({ sleeps += it; kotlinx.coroutines.delay(it) }) }
        runCurrent(); assertEquals(1, created.size)
        conn.onFailure(conn.current()!!.gen, dead); runCurrent()
        assertEquals("사망 즉시 폐기", listOf(1), disposed)
        advanceTimeBy(1_999); runCurrent(); assertEquals(1, created.size)
        advanceTimeBy(1); runCurrent(); assertEquals(2, created.size)
        job.cancel(); runCurrent()
        assertEquals(listOf(1, 2), disposed); assertEquals(listOf(2000L), sleeps)
    }

    // ---- r8R2-2: ReflectiveCar 정책 값(7초 고정 간격·사망 즉시 폐기) 경계 ----
    @Test fun reflectiveCarPolicy_valuesAndBoundaries_7000ms() {
        val p = com.hmgcarapp.core.vehicle.ReflectiveCar.POLICY
        assertEquals(7_000L, com.hmgcarapp.core.vehicle.ReflectiveCar.RECONNECT_GAP_MS)
        assertEquals(7_000L, p.minAttemptGapMs); assertEquals(7_000L, p.backoffStartMs); assertEquals(7_000L, p.backoffMaxMs)
        assertFalse(p.deathWaitsBackoff)
        // 사망 뒤: 마지막 시도로부터 7초가 지나야 새 시도(폐기는 즉시)
        val e = Env(p)
        val c1 = e.conn.connect()!!                       // t=0 시도
        e.now = 1_000; e.conn.onFailure(c1.gen, dead)
        assertNull(e.conn.connect()); assertEquals("사망 즉시 폐기", listOf(1), e.disposed)
        e.now = 6_999; assertNull(e.conn.connect()); assertEquals(1, e.created.size)
        e.now = 7_000; assertNotNull(e.conn.connect()); assertEquals(2, e.created.size)
        // 생성 실패 뒤: 고정 7초 백오프(늘어나지 않음)
        val f = Env(p); f.failCreate = 2
        assertNull(f.conn.connect())
        f.now = 6_999; assertNull(f.conn.connect()); assertEquals(1, f.created.size)
        f.now = 7_000; assertNull(f.conn.connect()); assertEquals(2, f.created.size)
        f.now = 13_999; assertNull(f.conn.connect()); assertEquals("두 번째 실패 뒤에도 7초", 2, f.created.size)
        f.now = 14_000; assertNotNull(f.conn.connect())
    }

    // ---- r8R2-4: open 이 던진 시도의 콜백은 callback(gen) 이 무시한다 ----
    @Test fun callbackFromAttemptWhoseOpenThrew_isIgnored_evenAfterLaterSuccess() {
        val e = Env()
        val got = mutableListOf<Int>()
        var cb: ((Int) -> Unit)? = null
        e.openHook = { f, g -> if (f.id == 1) { cb = e.conn.callback(g) { v: Int -> got += v }; throw IllegalStateException("open fail") } }
        assertNull(e.conn.connect())                      // 시도 1: open 이 던짐
        cb!!(1); assertTrue("실패한 시도의 콜백은 즉시 무시", got.isEmpty())
        e.now += 2_000; assertNotNull(e.conn.connect())   // 시도 2 성공
        cb!!(2); assertTrue("새 연결이 생긴 뒤에도 무시", got.isEmpty())
    }
}

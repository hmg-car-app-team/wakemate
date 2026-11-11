package com.hmgcarapp.wakemate.domain

/** 휴식(정차)이 끝났다고 인정된 순간의 기록. */
data class RestEvent(val drivingMsBefore: Long, val restMs: Long)

/**
 * 연속 주행 시간과 휴식 감지(순수, 단조 시계 주입).
 * - DRIVING 인 시간만 누적한다. 정차가 [restResetMs] 이상 이어지면 누적을 0 으로 되돌리고 [RestEvent] 를 한 번 돌려준다.
 * - 짧은 정차(신호 대기 등)는 누적을 유지한다. 첫 관측은 누적하지 않는다(연결 직후 값을 변화로 오인하지 않기).
 * - 프로세스가 죽었다 살아날 때는 [restore] 로 누적값만 복원한다(시계는 새로 시작).
 */
class DriveClock(private val restResetMs: Long = REST_RESET_MS, private val minReportMs: Long = MIN_REPORT_DRIVE_MS) {
    private var lastTick: Long? = null
    private var prevDriving = true
    private var accum = 0L
    private var stoppedMs = 0L
    private var restReported = false

    val drivingMs: Long get() = accum
    val stoppedForMs: Long get() = if (prevDriving) 0L else stoppedMs

    fun restore(drivingMs: Long) { accum = drivingMs.coerceAtLeast(0L) }

    /** 시연: 누적 주행 시간을 더한다. */
    fun addSimulated(ms: Long) { accum = (accum + ms).coerceAtLeast(0L) }

    fun reset() { accum = 0L; stoppedMs = 0L; restReported = false }

    fun tick(nowMs: Long, driving: Boolean): RestEvent? {
        val prev = lastTick
        lastTick = nowMs
        if (prev == null) { prevDriving = driving; return null }
        val dt = (nowMs - prev).coerceAtLeast(0L)
        var ev: RestEvent? = null
        if (prevDriving) accum += dt
        else {
            stoppedMs += dt
            if (!restReported && stoppedMs >= restResetMs) {
                restReported = true
                if (accum >= minReportMs) ev = RestEvent(accum, stoppedMs)
                accum = 0L
            }
        }
        if (driving != prevDriving) { stoppedMs = 0L; restReported = false }
        prevDriving = driving
        return ev
    }

    companion object {
        const val REST_RESET_MS = 15 * 60_000L
        const val MIN_REPORT_DRIVE_MS = 10 * 60_000L
    }
}

/**
 * 내기순환 연속 지속 시간(순수). 켜진 것을 처음 본 시각부터 센다(그 전에 언제부터 켜져 있었는지는 알 수 없으므로 하한).
 * 꺼짐 또는 알 수 없음(null)이 되면 0 으로 돌아간다.
 */
class RecircClock {
    private var since: Long? = null
    fun onState(on: Boolean?, nowMs: Long) {
        // 알 수 없음(null)은 상태를 바꾸지 않는다: 켜짐을 처음 볼 때 시작, 꺼짐을 볼 때만 초기화
        if (on == true) { if (since == null) since = nowMs } else if (on == false) since = null
    }
    /** 켜져 있으면 지속 시간, 아니면 null. */
    fun durationMs(nowMs: Long): Long? = since?.let { (nowMs - it).coerceAtLeast(0L) }
}

/** 위치 신선도(쉼터 계산에 쓰는 위치가 오래되면 위치 미확인으로 본다). */
object LocationFreshness {
    const val MAX_AGE_MS = 5 * 60_000L
    fun isFresh(atMs: Long?, nowMs: Long): Boolean = atMs != null && nowMs - atMs in 0..MAX_AGE_MS
}

/** 시연으로 더한 주행분은 저장하지 않는다. */
object DrivePersist {
    fun persistable(drivingMs: Long, simExtraMs: Long): Long = (drivingMs - simExtraMs).coerceAtLeast(0L)
}

/** 저장된 누적 주행 시간을 복원할지: 저장 뒤 [DriveClock.REST_RESET_MS] 이상 지났으면 휴식한 것으로 보고 0(벽시계 사용: 저장 만료 전용). */
object DriveRestore {
    fun restoredDrivingMs(savedDrivingMs: Long, savedAtWallMs: Long, nowWallMs: Long): Long {
        if (savedAtWallMs <= 0L) return 0L
        val gap = nowWallMs - savedAtWallMs
        return if (gap < 0L || gap >= DriveClock.REST_RESET_MS) 0L else savedDrivingMs.coerceAtLeast(0L)
    }
}

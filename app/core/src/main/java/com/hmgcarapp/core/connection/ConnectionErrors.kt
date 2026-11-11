package com.hmgcarapp.core.connection

import java.lang.reflect.InvocationTargetException

/** 서비스 연결 끊김을 뜻하는 구체적 표현만(너무 넓은 'connect'/'service'/'bind' 단독은 일반 오류까지 재연결시킨다). */
private val CONNECTION_HINTS = listOf(
    "disconnect", "not connected", "notconnected", "service connection", "service died", "binder died", "deadobject", "dead object",
    "remoteexception", "not initialized", "notinitialized", "connection lost", "연결 끊", "연결이 끊",
)

/** SDK 서비스 연결 실패 계열 오류인지(이 경우 연결을 새로 만들어야 한다). 모든 어댑터가 이 한 판정을 쓴다. */
fun isConnectionFailure(t: Throwable): Boolean {
    val s = (t.javaClass.simpleName + " " + (t.message ?: "")).lowercase()
    return CONNECTION_HINTS.any { it in s }
}

/**
 * CarService 사망·연결 끊김 계열 예외인지(원인 사슬 포함). 이 경우 CarPropertyManager/UX 매니저는 무효이므로 폐기 후 재연결한다.
 * android.car/android.os 클래스 대신 이름으로 판별(단위 테스트·비-car 기기 호환).
 */
fun isCarServiceFailure(t: Throwable): Boolean {
    var e: Throwable? = t; var depth = 0
    while (e != null && depth++ < 6) {
        if (e is InvocationTargetException) { e = e.targetException ?: e.cause; continue }
        val n = e.javaClass.simpleName
        if (n == "DeadObjectException" || n == "RemoteException" || n == "IllegalStateException" || n == "TransactionTooLargeException") return true
        if (isConnectionFailure(e)) return true
        e = e.cause
    }
    return false
}

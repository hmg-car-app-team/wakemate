package com.hmgcarapp.core.log

import android.util.Log

/**
 * 모든 앱 공통 로그. logcat 태그는 항상 "HMG/<모듈>" 로 시작한다.
 * 확인: adb logcat | grep HMG (logcat 태그 필터는 와일드카드를 지원하지 않음)
 * 단위 테스트에서는 [sink] 를 바꿔 로그를 수집할 수 있다.
 */
object HmgLog {
    const val PREFIX = "HMG"

    fun interface Sink { fun log(priority: Int, tag: String, msg: String, tr: Throwable?) }

    @Volatile
    var sink: Sink = Sink { p, tag, msg, tr ->
        try {
            when (p) {
                Log.ERROR -> Log.e(tag, msg, tr)
                Log.WARN -> Log.w(tag, msg, tr)
                Log.DEBUG -> Log.d(tag, msg, tr)
                else -> Log.i(tag, msg, tr)
            }
        } catch (_: Throwable) { /* JVM 단위 테스트 등 android.util.Log 없는 환경 */ }
    }

    fun tag(module: String) = "$PREFIX/$module"

    fun d(module: String, msg: String) = sink.log(Log.DEBUG, tag(module), msg, null)
    fun i(module: String, msg: String) = sink.log(Log.INFO, tag(module), msg, null)
    fun w(module: String, msg: String, tr: Throwable? = null) = sink.log(Log.WARN, tag(module), msg, tr)
    fun e(module: String, msg: String, tr: Throwable? = null) = sink.log(Log.ERROR, tag(module), msg, tr)
}

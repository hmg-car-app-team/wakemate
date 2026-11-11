package com.hmgcarapp.core.store

import android.content.Context
import com.hmgcarapp.core.log.HmgLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 앱별 로컬 JSON 저장소. 상태 저장·복원용 (프로세스가 죽어도 복원).
 * - 파일: <dir>/<name>.json, 쓰기는 임시 파일 후 rename (부분 쓰기 방지)
 * - 앱 샌드박스 filesDir 에만 저장되므로 앱 간 충돌 없음.
 * 사용:  val store = LocalJsonStore.forApp(context);  store.save("state", JSONObject().put("x", 1));  store.loadObject("state")
 */
/** [LocalJsonStore.loadResult] 결과: 파일 없음 / 정상 읽기 / 읽기 오류를 구분한다. */
sealed interface StoreLoad {
    data object Missing : StoreLoad
    data class Ok(val text: String) : StoreLoad
    data class ReadError(val cause: Throwable) : StoreLoad
}

class LocalJsonStore(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    init { dir.mkdirs() }

    private fun file(name: String): File {
        require(name.matches(Regex("[A-Za-z0-9_\\-]+"))) { "잘못된 이름: $name" }
        return File(dir, "$name.json")
    }

    fun saveText(name: String, json: String) {
        val f = file(name)
        synchronized(LOCK) {
            dir.mkdirs()
            val tmp = File(dir, "$name.json.${System.nanoTime()}.tmp")
            try {
                java.io.FileOutputStream(tmp).use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                    out.flush()
                    out.fd.sync()
                }
                try {
                    java.nio.file.Files.move(tmp.toPath(), f.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                tmp.delete() // 성공 시 이미 이동됨(no-op), 실패 시 잔재 제거
            }
            // 정상 저장 성공 -> 보존 중이던 손상 파일 삭제 (실패해도 저장 결과에는 영향 없음)
            runCatching { File(dir, "$name.json.corrupt").delete() }
        }
    }

    fun loadText(name: String): String? = try {
        synchronized(LOCK) { file(name).takeIf { it.exists() }?.readText(Charsets.UTF_8) }
    } catch (t: Throwable) { HmgLog.w("JsonStore", "읽기 실패 $name: $t"); null }

    /** 읽기 오류와 '파일 없음'을 구분한다. (loadText 의 null 계약은 그대로) */
    fun loadResult(name: String): StoreLoad {
        val f = file(name) // 잘못된 이름은 예외(경로 탈출 거부)
        return try {
        synchronized(LOCK) {
            if (!f.exists()) StoreLoad.Missing else StoreLoad.Ok(f.readText(Charsets.UTF_8))
        }
    } catch (t: Throwable) { HmgLog.w("JsonStore", "읽기 실패 $name: $t"); StoreLoad.ReadError(t) }
    }

    fun save(name: String, obj: JSONObject) = saveText(name, obj.toString())
    fun save(name: String, arr: JSONArray) = saveText(name, arr.toString())

    /** 손상된 JSON 이면 null (앱이 죽지 않게). 손상 파일 보존이 필요하면 [quarantineCorrupt]. */
    fun loadObject(name: String): JSONObject? = loadText(name)?.let { runCatching { JSONObject(it) }.getOrNull() }
    fun loadArray(name: String): JSONArray? = loadText(name)?.let { runCatching { JSONArray(it) }.getOrNull() }

    /** 손상 파일을 `name.json.corrupt` 로 rename 해 보존(기존 .corrupt 는 덮어씀). 옮겼으면 true. */
    fun quarantineCorrupt(name: String): Boolean {
        val f = file(name)
        return synchronized(LOCK) {
            if (!f.exists()) return@synchronized false
            try {
                val c = File(dir, "$name.json.corrupt")
                java.nio.file.Files.move(f.toPath(), c.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                c.setLastModified(clock()) // 보존 기간은 격리 시각 기준
                true
            } catch (t: Throwable) { HmgLog.w("JsonStore", "손상 파일 보존 실패 $name: $t"); false }
        }
    }

    /**
     * `name.json`, `name.json.corrupt`, `name.json.*.tmp` 를 모두 삭제(같은 락). 이름 검증 유지.
     * 반환: **삭제 후 세 종류가 모두 남아 있지 않으면 true**(원래 없었어도 true), 하나라도 잔존하면 false(r7R1-3/r7R2-8).
     */
    fun purge(name: String): Boolean {
        val f = file(name)
        return synchronized(LOCK) {
            runCatching { f.delete() }
            runCatching { File(dir, "$name.json.corrupt").delete() }
            runCatching { deleteTmp(name, minAgeMs = null) }
            !f.exists() && !File(dir, "$name.json.corrupt").exists() && tmpFiles(name).isEmpty()
        }
    }

    /** 오래된(기본 10분 이상) `name.json.*.tmp` 잔재 삭제(강제 종료로 finally 가 못 돈 경우). 지운 개수 반환. */
    fun cleanupStale(name: String, nowMs: Long = clock(), maxAgeMs: Long = 10 * 60_000L): Int {
        file(name)
        return synchronized(LOCK) { deleteTmp(name, minAgeMs = maxAgeMs, nowMs = nowMs) }
    }

    /** 손상 파일 보존은 최대 1개(새 손상이 오면 교체). **다음 정상 저장 성공 시 삭제**되며, 7일은 그 사이 저장이 없을 때의 상한이다. 삭제했으면 true. */
    fun pruneCorrupt(name: String, nowMs: Long = clock()): Boolean {
        file(name)
        return synchronized(LOCK) {
            val c = File(dir, "$name.json.corrupt")
            c.exists() && nowMs - c.lastModified() >= CORRUPT_RETENTION_MS && c.delete()
        }
    }

    private fun tmpFiles(name: String): List<File> {
        val prefix = "$name.json."
        return dir.listFiles()?.filter { it.name.startsWith(prefix) && it.name.endsWith(".tmp") } ?: emptyList()
    }

    private fun deleteTmp(name: String, minAgeMs: Long?, nowMs: Long = 0L): Int {
        var n = 0
        tmpFiles(name).forEach {
            if ((minAgeMs == null || nowMs - it.lastModified() >= minAgeMs) && it.delete()) n++
        }
        return n
    }

    fun delete(name: String): Boolean = file(name).delete()
    fun exists(name: String): Boolean = file(name).exists()

    companion object {
        private val LOCK = Any()
        const val CORRUPT_RETENTION_MS = 7 * 24 * 3600_000L
        fun forApp(context: Context, subdir: String = "hmg-store") = LocalJsonStore(File(context.filesDir, subdir))
    }
}

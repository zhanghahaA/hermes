package com.hermes.mobile.data.store

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * 记下闪退（和后台里没接住的异常），下次打开 App 时给用户看、导出诊断信息时附上，方便定位。
 * 只存在手机上（App 自己的目录），不往外发。
 */
object CrashLog {
    @Volatile var file: File? = null

    private const val Separator = "\n----- \n"
    private const val MaxBytes = 32 * 1024
    private const val SeenMarker = "[seen]"

    /** [fatal]：App 因此退出了（下次启动要提示）；否则只是后台出错被接住了。 */
    @Synchronized
    fun record(error: Throwable, fatal: Boolean, thread: String? = null, version: String? = null,
               redact: (String) -> String = { DiagnosticRedactor.redact(it) }) {
        val f = file ?: return
        runCatching {
            var rawTrace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            // 异常类型用短类名保留，避免脱敏时把 Java 包名误当作服务器域名。
            generateSequence(error) { it.cause }.take(16).forEach {
                rawTrace = rawTrace.replace(it.javaClass.name, it.javaClass.simpleName)
            }
            val trace = redact(rawTrace)
            val entry = buildString {
                append(if (fatal) "闪退" else "后台出错（已接住）")
                append(" · ").append(Instant.now())
                version?.let { append(" · 版本 ").append(it) }
                thread?.let { append(" · 线程 ").append(it) }
                append('\n').append(trace.take(12_000))
            }
            val old = if (f.exists()) f.readText() else ""
            // 新的放最前面，只留最近几条
            f.parentFile?.mkdirs()
            f.writeText(redact(entry + Separator + old).take(MaxBytes))
        }
    }

    /** 上次闪退还没给用户看过的话，返回它（并记为已看）。 */
    @Synchronized
    fun takeUnseenCrash(): String? {
        val f = file ?: return null
        return runCatching {
            if (!f.exists()) return null
            val text = f.readText()
            val first = text.substringBefore(Separator)
            if (!first.startsWith("闪退")) return null
            f.writeText(SeenMarker + text)
            DiagnosticRedactor.redact(first)
        }.getOrNull()
    }

    /** 最近的记录（诊断信息用）。 */
    @Synchronized
    fun recent(): String? = runCatching {
        file?.takeIf { it.exists() }?.readText()?.removePrefix(SeenMarker)?.take(8_000)?.let { DiagnosticRedactor.redact(it) }?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

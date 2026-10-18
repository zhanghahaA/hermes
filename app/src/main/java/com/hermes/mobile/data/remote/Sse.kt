package com.hermes.mobile.data.remote

import okio.BufferedSource

/** 一帧 SSE。[id] 是服务器给的序号（`id:` 行），[data] 是 JSON 文本。 */
data class SseEvent(val id: Long?, val event: String?, val data: String)

/**
 * 按 SSE 规范逐行读：`id:` / `event:` / `data:` 累积，空行派发一帧；`:` 开头的是注释（心跳），跳过。
 * 读到流结束时返回。
 */
internal suspend fun readSse(source: BufferedSource, onEvent: suspend (SseEvent) -> Unit) {
    var id: Long? = null
    var event: String? = null
    val data = StringBuilder()
    while (true) {
        val line = source.readUtf8Line() ?: break
        when {
            line.isEmpty() -> {
                if (data.isNotEmpty()) onEvent(SseEvent(id, event, data.toString()))
                id = null
                event = null
                data.clear()
            }
            line.startsWith(":") -> Unit
            else -> {
                val field = line.substringBefore(':')
                val value = line.substringAfter(':', "").removePrefix(" ")
                when (field) {
                    "id" -> id = value.trim().toLongOrNull()
                    "event" -> event = value
                    "data" -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(value)
                    }
                }
            }
        }
    }
    if (data.isNotEmpty()) onEvent(SseEvent(id, event, data.toString()))
}

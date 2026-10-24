package com.hermes.mobile.data.remote

/**
 * 一轮回复的公开正文。工具边界只分段，不能擦掉已经展示的文字。
 * message.interim 可能是之前 delta 的整段重播，不能再拼一遍。
 */
internal data class ReplyStream(
    val earlier: List<String> = emptyList(),
    val current: String = "",
    val boundary: Boolean = false,
) {
    val text: String get() = (earlier + current.trim()).filter { it.isNotBlank() }.joinToString("\n\n")

    fun delta(value: String): ReplyStream {
        if (value.isEmpty()) return this
        return if (boundary) ReplyStream(earlier + listOfNotNull(current.trim().takeIf { it.isNotEmpty() }), value.trimStart())
        else copy(current = (current + value).trimStart())
    }

    fun interim(value: String, alreadyStreamed: Boolean): ReplyStream {
        val body = value.trim()
        if (body.isEmpty()) return this
        // already_streamed 是服务器的去重契约；没有 delta（例如补发）时用完整文本补上。
        if (alreadyStreamed || current.trim() == body) {
            return copy(current = current.ifBlank { body }, boundary = true)
        }
        return delta(if (boundary || current.isBlank()) body else "\n\n$body").copy(boundary = true)
    }

    fun step(): ReplyStream = copy(boundary = true)

    /** 最后一段以服务器最终正文替换；之前已经说过的段落保留，避免最终正文重复出现。 */
    fun finish(output: String): Pair<String, String> {
        val final = output.trim().ifEmpty { current.trim() }
        val keepCurrent = boundary && current.isNotBlank() && current.trim() != final && !final.startsWith(current.trim())
        val before = (earlier + if (keepCurrent) listOf(current.trim()) else emptyList())
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        return before to final
    }
}

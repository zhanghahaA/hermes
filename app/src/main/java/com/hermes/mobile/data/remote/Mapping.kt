package com.hermes.mobile.data.remote

import com.hermes.mobile.data.model.ChatItem
import com.hermes.mobile.data.model.ImageRef
import com.hermes.mobile.data.model.Role
import com.hermes.mobile.data.model.Session
import com.hermes.mobile.data.model.SessionSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant

/** 服务器时间 → 毫秒。秒级浮点数、毫秒整数、数字字符串、ISO 时间都认。 */
internal fun JsonElement?.epochMillis(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    primitive.doubleOrNull?.let { n ->
        return if (n > 1e12) n.toLong() else (n * 1000).toLong()
    }
    val text = primitive.contentOrNull?.trim().orEmpty()
    if (text.isEmpty()) return null
    text.toDoubleOrNull()?.let { n -> return if (n > 1e12) n.toLong() else (n * 1000).toLong() }
    return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
}

internal fun SessionDto.toSession(now: Long): Session = Session(
    id = id,
    title = title?.takeIf { it.isNotBlank() } ?: preview?.lineSequence()?.firstOrNull()?.take(24)?.takeIf { it.isNotBlank() } ?: "未命名会话",
    preview = preview.orEmpty().replace('\n', ' ').trim(),
    updatedAt = last_active.epochMillis() ?: started_at.epochMillis() ?: now,
    source = SessionSource(source ?: "api_server"),
    pinned = pinned == true,
    model = model,
)

/** 列表里不该出现的会话：子代理内部会话、被隐藏或归档的会话。 */
internal fun SessionDto.visibleInList(): Boolean =
    is_internal_child != true && hidden != true && archived != true

/**
 * 把一条服务器消息变成聊天气泡；不该显示的返回 null。
 *
 * 只保留：用户消息（有文字或图片），以及 Hermes 有正文的回复。
 * 丢弃：工具消息、系统消息、隐藏消息、只有工具调用没有文字的中间步骤。
 * 思考内容（reasoning / reasoning_content）在 [MessageDto] 里根本没有声明，到不了这里。
 */
internal fun MessageDto.toChatItem(index: Int, fallbackTime: Long): ChatItem.Message? {
    if (display_kind == "hidden") return null
    val role = when (role) {
        "user" -> Role.User
        "assistant" -> Role.Assistant
        else -> return null
    }
    val (text, images) = content.parseContent(idSeed = index)
    if (role == Role.Assistant && text.isBlank()) return null
    if (role == Role.User && text.isBlank() && images.isEmpty()) return null
    val messageId = when (val raw = id) {
        is JsonPrimitive -> raw.contentOrNull
        else -> null
    } ?: "idx-$index"
    return ChatItem.Message(
        id = messageId,
        time = timestamp.epochMillis() ?: fallbackTime,
        role = role,
        text = text.trim(),
        images = images,
    )
}

/** content 可能是字符串，也可能是 `[{"type":"text","text":...},{"type":"image_url",...}]`。 */
private fun JsonElement?.parseContent(idSeed: Int): Pair<String, List<ImageRef>> = when (this) {
    null, is JsonNull -> "" to emptyList()
    is JsonPrimitive -> contentOrNull.orEmpty() to emptyList()
    is JsonArray -> {
        val text = StringBuilder()
        val images = mutableListOf<ImageRef>()
        forEachIndexed { i, part ->
            val obj = part as? JsonObject ?: return@forEachIndexed
            when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
                "text", "input_text", "output_text" -> {
                    val t = (obj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    if (t.isNotBlank()) {
                        if (text.isNotEmpty()) text.append("\n\n")
                        text.append(t)
                    }
                }
                // 图片暂时用占位色块显示，阶段 4 接入真实图片
                "image_url", "input_image", "image" -> images += ImageRef("img-$idSeed-$i", hue = ((idSeed * 47 + i * 89) % 360).toFloat(), aspect = 1.3f)
            }
        }
        text.toString() to images
    }
    is JsonObject -> ((this["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()) to emptyList()
}

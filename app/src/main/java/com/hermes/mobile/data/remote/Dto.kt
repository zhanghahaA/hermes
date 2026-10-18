package com.hermes.mobile.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Hermes API Server 的返回结构。所有字段都可选、都有默认值：
 * 新版本多出来的字段直接忽略，旧版本缺的字段用默认值，保证不同版本的 Hermes 都能解析。
 * 刻意不声明 reasoning / reasoning_content / tool_calls：思考内容在解析这一步就丢掉了。
 */
internal val HermesJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
internal data class HealthDto(
    val status: String? = null,
    val platform: String? = null,
    val version: String? = null,
) {
    /** 源码安装的 Hermes 会报 "unknown"，当作没有版本号。 */
    fun knownVersion(): String? = version?.trim()?.takeIf { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) }
}

@Serializable
internal data class EndpointDto(val method: String? = null, val path: String? = null)

@Serializable
internal data class CapabilitiesDto(
    val platform: String? = null,
    val model: String? = null,
    val features: JsonObject? = null,
    val endpoints: Map<String, EndpointDto>? = null,
)

@Serializable
internal data class SessionDto(
    val id: String,
    val source: String? = null,
    val model: String? = null,
    val title: String? = null,
    /** 秒级时间戳（浮点数）；个别版本可能是字符串，统一用 [epochMillis] 解析。 */
    val started_at: JsonElement? = null,
    val last_active: JsonElement? = null,
    val message_count: Int? = null,
    val preview: String? = null,
    val pinned: Boolean? = null,
    val archived: Boolean? = null,
    val hidden: Boolean? = null,
    val is_internal_child: Boolean? = null,
)

@Serializable
internal data class SessionListDto(
    val data: List<SessionDto> = emptyList(),
    val has_more: Boolean? = null,
)

@Serializable
internal data class SessionEnvelopeDto(val session: SessionDto? = null)

@Serializable
internal data class MessageDto(
    val id: JsonElement? = null,
    val role: String? = null,
    /** 字符串，或者 OpenAI 风格的分段数组。 */
    val content: JsonElement? = null,
    val timestamp: JsonElement? = null,
    val display_kind: String? = null,
)

@Serializable
internal data class MessageListDto(
    val session_id: String? = null,
    val data: List<MessageDto> = emptyList(),
)

@Serializable
internal data class ErrorBodyDto(val error: ErrorDto? = null)

@Serializable
internal data class ErrorDto(val message: String? = null, val type: String? = null, val code: String? = null)

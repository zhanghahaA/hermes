package com.hermes.mobile.data.remote

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * 一台 Hermes 实际能做什么。App 按这里决定显示哪些功能、走哪条接口，而不是看版本号。
 */
data class ServerCapabilities(
    val version: String?,
    /** `/v1/capabilities` 里列出的接口名（如 "sessions"、"session_chat_stream"）。 */
    val endpoints: Set<String>,
    /** 值为 true 的特性开关。 */
    val features: Set<String>,
    /** 服务器太旧，没有 `/v1/capabilities`，是靠逐个探测得到的结果。 */
    val probed: Boolean,
) {
    val sessions: Boolean get() = "sessions" in endpoints
    val sessionMessages: Boolean get() = "session_messages" in endpoints
    val sessionUpdate: Boolean get() = "session_update" in endpoints
    val sessionDelete: Boolean get() = "session_delete" in endpoints
    val streamingChat: Boolean get() = "session_chat_stream" in endpoints || "responses" in endpoints
    val approvals: Boolean get() = "approval_events" in features || "run_approval" in endpoints
    val sessionCreate: Boolean get() = "session_create" in endpoints
    val sessionFork: Boolean get() = "session_fork" in endpoints
    /** 聊天走 /v1/runs：提交、事件流都要有。断开不会打断 Hermes，可以重连接着收。 */
    val runs: Boolean get() = "runs" in endpoints && "run_events" in endpoints

    /** 给“测试连接”看的一句话总结。 */
    fun summary(): String = buildList {
        add(if (sessions) "会话 ✓" else "会话 ✗")
        add(if (runs) "聊天 ✓" else "聊天 ✗")
        add(if (approvals) "审批 ✓" else "审批 ✗")
    }.joinToString(" · ")

    companion object {
        internal fun from(dto: CapabilitiesDto, version: String?) = ServerCapabilities(
            version = version,
            endpoints = dto.endpoints?.keys.orEmpty(),
            features = dto.features.orEmpty()
                .filterValues { (it as? JsonPrimitive)?.booleanOrNull == true }
                .keys,
            probed = false,
        )
    }
}

/**
 * 探测能力：优先读 `/v1/capabilities`；旧版本（没有这个接口）就直接试会话接口，
 * 并用 `/v1/models` 验证 Key。
 */
suspend fun HermesClient.probeCapabilities(version: String?): ServerCapabilities = try {
    ServerCapabilities.from(capabilities(), version)
} catch (e: HermesError.NotSupported) {
    verifyKey()
    val hasSessions = try {
        sessions(limit = 1)
        true
    } catch (e: HermesError.NotSupported) {
        false
    }
    ServerCapabilities(
        version = version,
        endpoints = buildSet {
            add("responses")
            if (hasSessions) addAll(listOf("sessions", "session_messages", "session_update", "session_delete"))
        },
        features = emptySet(),
        probed = true,
    )
}

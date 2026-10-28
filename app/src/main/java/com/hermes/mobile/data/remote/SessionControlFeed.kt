package com.hermes.mobile.data.remote

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/** 从与桌面相同的 Gateway 快照和回放读取卡片；不创建新对话、不提交消息。 */
internal class SessionControlFeed(private val rpc: suspend (String, JsonObject) -> JsonObject) {
    data class Snapshot(val sessionId: String, val prompts: List<ControlRequest>)

    fun watch(sessionId: String, profile: String): Flow<Snapshot> = flow {
        fun params(id: String = sessionId, extra: JsonObject = JsonObject(emptyMap())) =
            JsonObject(extra + mapOf("session_id" to JsonPrimitive(id), "profile" to JsonPrimitive(profile)))
        var snapshot = rpc("session.resume", params(extra = buildJsonObject { put("omit_messages", true) }))
        if (snapshot["prompts"] !is JsonArray) throw HermesError.NotSupported("跨端互动卡片") // 旧版协议没有共享卡片，保留原 API 流程。
        val canonicalId = (snapshot["session_id"] as? JsonPrimitive)?.contentOrNull ?: sessionId
        val subscription = (snapshot["subscription_id"] as? JsonPrimitive)?.contentOrNull
        var sequence = snapshot["last_sequence"] ?: JsonPrimitive(0)
        var epoch = snapshot["replay_epoch"] ?: JsonPrimitive("")
        val prompts = linkedMapOf<String, ControlRequest>()
        fun restore() {
            prompts.clear()
            (snapshot["prompts"] as? JsonArray).orEmpty().forEach { item ->
                (item as? JsonObject)?.let { ControlRequest.from(it, fallbackGeneration = snapshot["execution_generation"]) }
                    ?.takeIf { it.promptId != null && it.generation != null }?.let { prompts[it.promptId!!] = it }
            }
        }
        restore()
        try {
            emit(Snapshot(canonicalId, prompts.values.toList()))
            while (true) {
                delay(2000)
                val batch = rpc("session.events.since", params(canonicalId, buildJsonObject {
                    put("last_sequence", sequence); put("replay_epoch", epoch)
                }))
                if ((batch["snapshot_required"] as? JsonPrimitive)?.booleanOrNull == true) {
                    // 重放截断时重新取快照；先释放旧订阅，避免累计监听者。
                    return@flow
                }
                (batch["events"] as? JsonArray).orEmpty().forEach { item ->
                    val event = item as? JsonObject ?: return@forEach
                    val payload = event["payload"] as? JsonObject ?: return@forEach
                    val type = (event["type"] as? JsonPrimitive)?.contentOrNull
                    when (type) {
                        "approval.request", "clarify.request" -> {
                            val kind = if (type == "clarify.request") ControlRequest.Kind.Clarify else ControlRequest.Kind.Approval
                            ControlRequest.from(payload, kind, event["execution_generation"])
                                ?.takeIf { it.promptId != null && it.generation != null }?.let { prompts[it.promptId!!] = it }
                        }
                        "approval.settled", "clarify.settled", "approval.responded", "clarify.responded" -> {
                            val id = (payload["prompt_id"] as? JsonPrimitive)?.contentOrNull
                            if (id != null) prompts.remove(id)
                        }
                    }
                }
                sequence = batch["last_sequence"] ?: batch["latest_seq"] ?: sequence
                epoch = batch["replay_epoch"] ?: epoch
                emit(Snapshot(canonicalId, prompts.values.toList()))
            }
        } finally {
            if (subscription != null) withContext(NonCancellable) {
                withTimeoutOrNull(2000) { runCatching {
                    rpc("session.detach", params(canonicalId, buildJsonObject { put("subscription_id", subscription) }))
                } }
            }
        }
    }

    suspend fun respond(sessionId: String, profile: String, request: ControlRequest, answer: String) {
        require(request.promptId != null && request.generation != null)
        rpc(if (request.kind == ControlRequest.Kind.Clarify) "clarify.respond" else "approval.respond", buildJsonObject {
            put("session_id", sessionId); put("profile", profile)
            put("prompt_id", request.promptId); put("execution_generation", request.generation)
            put(if (request.kind == ControlRequest.Kind.Clarify) "answer" else "choice", answer)
        })
    }
}

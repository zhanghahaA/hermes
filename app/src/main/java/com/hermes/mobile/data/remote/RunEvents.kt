package com.hermes.mobile.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull

/** Hermes 要用户处理的一件事（审批或追问），以及回复时要带回去的标识。 */
data class ControlRequest(
    val kind: Kind,
    /** 新版 Hermes 的 prompt_id；旧版审批可能是 request_id 或没有。 */
    val promptId: String?,
    /** 新版才有；有它就按新格式回复。 */
    val generation: JsonElement?,
    val command: String,
    val description: String,
    val question: String,
    val choices: List<String>,
) {
    enum class Kind { Approval, Clarify }

    /** 回复审批的请求体，按版本选格式。 */
    fun approvalBody(choice: String): JsonObject = buildJsonObject {
        if (generation != null && promptId != null) {
            put("request_id", JsonPrimitive(promptId))
            put("execution_generation", generation)
        } else if (promptId != null) {
            put("request_id", JsonPrimitive(promptId))
        }
        put("choice", JsonPrimitive(choice))
    }

    fun clarifyBody(answer: String): JsonObject = buildJsonObject {
        put("request_id", JsonPrimitive(promptId.orEmpty()))
        if (generation != null) put("execution_generation", generation)
        put("answer", JsonPrimitive(answer))
    }

    companion object {
        /** [fallbackGeneration]：pending_controls 里的单项可能不带执行代号，用整个任务的。 */
        internal fun from(obj: JsonObject, defaultKind: Kind? = null, fallbackGeneration: JsonElement? = null): ControlRequest? {
            val kindText = obj.string("kind") ?: when (obj.string("event")) {
                "approval.request" -> "approval"
                "clarify.request" -> "clarify"
                else -> null
            }
            val kind = when (kindText) {
                "approval" -> Kind.Approval
                "clarify" -> Kind.Clarify
                else -> defaultKind ?: return null
            }
            return ControlRequest(
                kind = kind,
                promptId = obj.string("prompt_id") ?: obj.string("request_id"),
                generation = obj["execution_generation"]?.takeUnless { it is kotlinx.serialization.json.JsonNull }
                    ?: fallbackGeneration?.takeUnless { it is kotlinx.serialization.json.JsonNull },
                command = obj.string("command").orEmpty(),
                description = obj.string("description").orEmpty(),
                question = obj.string("question").orEmpty().withoutChatHints(),
                choices = (obj["choices"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.withoutRecommended() }.orEmpty(),
            )
        }
    }
}

/** App 关心的事件。工作过程是否展示由本地设置决定。 */
sealed interface RunEvent {
    val seq: Long?

    data class Delta(override val seq: Long?, val text: String) : RunEvent
    data class Interim(override val seq: Long?, val text: String, val alreadyStreamed: Boolean) : RunEvent
    data class Reasoning(override val seq: Long?, val text: String, val delta: Boolean) : RunEvent
    data class Control(override val seq: Long?, val request: ControlRequest) : RunEvent
    /** [choice] 为空表示不知道选了什么（例如在别的设备上处理的）。 */
    data class ControlSettled(override val seq: Long?, val promptId: String?, val choice: String?) : RunEvent
    /** 只有工具生命周期会分隔正文；进度通知不能截断正在流式生成的一句话。 */
    data class Step(override val seq: Long?, val tool: String, val callId: String?, val text: String,
        val finished: Boolean, val failed: Boolean, val boundary: Boolean) : RunEvent
    data class Completed(override val seq: Long?, val output: String, val pendingSteer: String? = null) : RunEvent
    data class Failed(override val seq: Long?, val error: String) : RunEvent
    data class Cancelled(override val seq: Long?) : RunEvent
    /** 断线太久，漏掉的事件服务器已经不保留了。 */
    data class Truncated(override val seq: Long?) : RunEvent
    data class Ignored(override val seq: Long?) : RunEvent
}

internal fun parseRunEvent(frame: SseEvent): RunEvent {
    val obj = runCatching { HermesJson.parseToJsonElement(frame.data).jsonObject }.getOrNull()
        ?: return RunEvent.Ignored(frame.id)
    val seq = (obj["seq"] as? JsonPrimitive)?.longOrNull ?: frame.id
    val name = obj.string("event") ?: frame.event
    return when (name) {
        "message.delta" -> RunEvent.Delta(seq, obj.string("delta").orEmpty())
        "message.interim" -> RunEvent.Interim(seq, obj.string("text").orEmpty(), (obj["already_streamed"] as? JsonPrimitive)?.booleanOrNull == true)
        "reasoning.delta", "reasoning.available" -> RunEvent.Reasoning(seq,
            obj.string("delta") ?: obj.string("text").orEmpty(), name == "reasoning.delta")
        "approval.request" -> ControlRequest.from(obj, ControlRequest.Kind.Approval)?.let { RunEvent.Control(seq, it) } ?: RunEvent.Ignored(seq)
        "clarify.request" -> ControlRequest.from(obj, ControlRequest.Kind.Clarify)?.let { RunEvent.Control(seq, it) } ?: RunEvent.Ignored(seq)
        "approval.settled", "approval.responded", "clarify.settled", "clarify.responded" ->
            RunEvent.ControlSettled(seq, obj.string("prompt_id") ?: obj.string("request_id"), obj.string("choice") ?: obj.string("answer"))
        "run.completed" -> RunEvent.Completed(seq, obj.string("output").orEmpty(), obj.string("pending_steer")?.takeIf { it.isNotBlank() })
        "run.failed", "error" -> RunEvent.Failed(seq, obj.string("error") ?: obj.string("message") ?: "Hermes 处理失败")
        "run.cancelled", "run.interrupted" -> RunEvent.Cancelled(seq)
        "replay.truncated" -> RunEvent.Truncated(seq)
        "tool.started", "tool.completed", "tool.failed", "tool.progress" -> {
            val tool = obj.string("tool") ?: obj.string("tool_name") ?: "工具"
            val text = obj.string("preview") ?: obj.string("delta").orEmpty()
            if (tool == "_thinking") RunEvent.Reasoning(seq, text, name == "tool.progress")
            else RunEvent.Step(seq, tool, obj.string("tool_call_id"), text,
                name == "tool.completed" || name == "tool.failed",
                name == "tool.failed" || (obj["error"] as? JsonPrimitive)?.booleanOrNull == true,
                name != "tool.progress")
        }
        else -> RunEvent.Ignored(seq)
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** Hermes 给聊天软件加的提示（“Reply "skip" to skip…”），App 有按钮，用不上。 */
private fun String.withoutChatHints(): String =
    lines().filterNot { it.trim().startsWith("Reply \"skip\"", ignoreCase = true) }.joinToString("\n").trim()

/** 第一个选项带的 “(Recommended)” 后缀。Hermes 匹配回答时会忽略它，所以去掉再显示、再发回去都没问题。 */
private fun String.withoutRecommended(): String = removeSuffix("(Recommended)").trim()

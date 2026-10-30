package com.hermes.mobile.data.remote

import com.hermes.mobile.data.remote.GatewayChannel.Companion.long
import com.hermes.mobile.data.remote.GatewayChannel.Companion.str
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * SSH 连接的 [HermesApi]：全部经服务器上的小桥走 Hermes gateway（[GatewayChannel]），不需要 API Server。
 *
 * gateway 的说法和 API Server 不一样，这里翻译成 App 已有的那一套：
 * - 一轮对话 = gateway 的一次 admission。run id 写成 `会话 id|admission id`，App 重启后拿着它也知道是哪个会话。
 * - 事件：`message.delta` / `reasoning.delta` / `tool.start` / `tool.complete` / `approval.request` / `message.complete` …
 *   翻成 API Server 事件流的形状（`tool.started`、`run.completed`…），聊天逻辑原样用。
 * - 会话要先“订阅”（`session.resume` / `session.create`）才能发消息、收事件；订阅跟着连接走，换了连接重新订阅。
 * - 改会话（改名、置顶、归档、删除、分叉、模型、推理强度）都是 `session.mutate`，带 revision / generation 防并发；
 *   被拒时 gateway 会把最新的值带回来，照着重试一次。
 * - 会话列表、技能、工具集、定时任务：gateway 这条连接上没有，桥用 Hermes 自己的 Python 函数读（见 [GatewayBridge]）。
 */
class GatewayHermesApi(private val link: GatewayChannel) : HermesApi {
    /** 会话最新的 revision / generation（改会话、停止、插话、回复审批都要带）。 */
    private class Fence(@Volatile var revision: Long = 0, @Volatile var generation: Long = 0) {
        /** 同一个会话的修改排队发：同时发的两个（比如改名紧接着置顶）会拿同一个 revision，后到的那个必然冲突。 */
        val edits = Mutex()
    }

    private val fences = ConcurrentHashMap<String, Fence>()
    /** 会话 → 在第几条连接上订阅过。 */
    private val attached = ConcurrentHashMap<String, Int>()
    private val epochs = ConcurrentHashMap<String, String>()
    /** 会话 → 最新快照里还没处理的审批 / 追问（API Server 形状）。 */
    private val pendingPrompts = ConcurrentHashMap<String, List<JsonObject>>()
    /** 会话 → 已经告诉 gateway 的推理强度（变了才再改）。 */
    private val appliedEffort = ConcurrentHashMap<String, String>()

    private fun fence(sessionId: String) = fences.getOrPut(sessionId) { Fence() }

    private fun remember(sessionId: String, obj: JsonObject) {
        val f = fence(sessionId)
        obj.long("revision")?.let { f.revision = it }
        obj.long("execution_generation")?.let { f.generation = maxOf(f.generation, it) }
        obj.str("replay_epoch")?.let { epochs[sessionId] = it }
        (obj["prompts"] as? JsonArray)?.let { prompts ->
            pendingPrompts[sessionId] = prompts.mapNotNull { (it as? JsonObject)?.let(::promptToApi) }
        }
    }

    // ---- 基础 ----

    private suspend fun call(method: String, params: JsonObject, timeoutMillis: Long = 45_000): JsonElement = try {
        link.call(method, params, timeoutMillis)
    } catch (e: GatewayException) {
        // 只读的请求遇到通道刚好断了（下拉刷新、换网络）：换一条通道再问一次；会改东西的不自动重试
        if (!e.disconnected || method !in ReadOnlyMethods) throw e.toHermesError()
        try {
            link.call(method, params, timeoutMillis)
        } catch (again: GatewayException) {
            throw again.toHermesError()
        }
    }

    /** 通道真的还通（一来一回），用于下拉刷新时判断要不要重建 SSH。 */
    suspend fun ping(timeoutMillis: Long = 8_000) {
        try {
            link.call("ping", JsonObject(emptyMap()), timeoutMillis)
        } catch (e: GatewayException) {
            throw e.toHermesError()
        }
    }

    private suspend fun obj(method: String, params: JsonObject, timeoutMillis: Long = 45_000): JsonObject =
        call(method, params, timeoutMillis) as? JsonObject ?: JsonObject(emptyMap())

    private suspend fun local(name: String, params: JsonObject = JsonObject(emptyMap())): JsonObject = try {
        link.local(name, params) as? JsonObject ?: JsonObject(emptyMap())
    } catch (e: GatewayException) {
        // 只读的（会话列表、技能、工具集、读任务）断了换条通道再问一次；改任务不重试
        val readOnly = name != "jobs" || (params["op"] as? JsonPrimitive)?.contentOrNull in setOf("list", "get")
        if (!e.disconnected || !readOnly) throw e.toHermesError()
        try {
            link.local(name, params) as? JsonObject ?: JsonObject(emptyMap())
        } catch (again: GatewayException) {
            throw again.toHermesError()
        }
    }

    /** 订阅这个会话（这条连接上还没订阅过时），返回订阅时拿到的快照。 */
    private suspend fun attach(sessionId: String, withMessages: Boolean = false): JsonObject? {
        link.ensure()
        val generation = link.generation.value
        if (!withMessages && attached[sessionId] == generation) return null
        val snapshot = obj("session.resume", buildJsonObject {
            put("session_id", sessionId)
            put("omit_messages", !withMessages)
        }, timeoutMillis = 90_000)
        remember(sessionId, snapshot)
        attached[sessionId] = link.generation.value
        snapshot.str("session_id")?.takeIf { it != sessionId }?.let { attached[it] = link.generation.value; remember(it, snapshot) }
        return snapshot
    }

    /**
     * 改会话：带上最新的 revision（[needsGeneration] 时还有 generation）。gateway 说值旧了会把新的带回来，照着重试
     * （Hermes 刚回完一轮时会自己起标题，也会让 revision 变，所以多试几次）。
     */
    private suspend fun mutate(sessionId: String, operation: String, payload: JsonObject, needsGeneration: Boolean = false): JsonObject {
        attach(sessionId)
        val f = fence(sessionId)
        return f.edits.withLock { mutateInOrder(sessionId, f, operation, payload, needsGeneration) }
    }

    private suspend fun mutateInOrder(sessionId: String, f: Fence, operation: String, payload: JsonObject, needsGeneration: Boolean): JsonObject {
        var lastError: HermesError? = null
        repeat(4) {
            try {
                val result = link.call("session.mutate", buildJsonObject {
                    put("session_id", sessionId)
                    put("request_id", UUID.randomUUID().toString())
                    put("expected_revision", f.revision)
                    if (needsGeneration) put("expected_generation", f.generation)
                    put("operation", operation)
                    put("payload", payload)
                }) as? JsonObject ?: JsonObject(emptyMap())
                remember(sessionId, result)
                return result
            } catch (e: GatewayException) {
                if (e.reason !in setOf("revision_conflict", "stale_generation") || e.data == null) throw e.toHermesError()
                e.data.long("revision")?.let { f.revision = it }
                e.data.long("execution_generation")?.let { f.generation = it }
                lastError = e.toHermesError()
            }
        }
        throw lastError ?: HermesError.Conflict("revision_conflict", "会话刚被别处改过，再试一次")
    }

    override suspend fun health(): HealthDto {
        val hello = try {
            link.ensure()
        } catch (e: GatewayException) {
            throw e.toHermesError()
        }
        val sha = hello.str("code_sha")?.take(7)
        return HealthDto(status = "ok", platform = "hermes-agent", version = sha?.let { "git $it" })
    }

    override suspend fun capabilities(): CapabilitiesDto = CapabilitiesDto(
        platform = "hermes-agent",
        endpoints = listOf(
            "sessions", "session", "session_messages", "session_update", "session_delete", "session_create", "session_fork",
            "runs", "run_events", "run_approval", "run_stop", "run_steer", "model_options", "session_model_lock",
            "skills", "toolsets",
        ).associateWith { EndpointDto() },
        features = buildJsonObject { put("approval_events", true); put("gateway", true) },
    )

    override suspend fun verifyKey() = Unit

    // ---- 会话 ----

    override suspend fun sessions(limit: Int, offset: Int): SessionListDto {
        val result = local("sessions", buildJsonObject { put("limit", limit); put("offset", offset) })
        val data = (result["data"] as? JsonArray).orEmpty().mapNotNull { row ->
            runCatching { HermesJson.decodeFromJsonElement<SessionDto>(row) }.getOrNull()
        }
        return SessionListDto(data = data, has_more = data.size >= limit)
    }

    override suspend fun session(id: String): SessionDto? =
        local("session", buildJsonObject { put("session_id", id) })["session"]?.takeUnless { it is JsonNull }
            ?.let { runCatching { HermesJson.decodeFromJsonElement<SessionDto>(it) }.getOrNull() }

    override suspend fun messages(sessionId: String): MessageListDto {
        val snapshot = attach(sessionId, withMessages = true) ?: JsonObject(emptyMap())
        val messages = (snapshot["messages"] as? JsonArray).orEmpty().mapNotNull { m ->
            val o = m as? JsonObject ?: return@mapNotNull null
            MessageDto(
                id = o["row_id"]?.takeUnless { it is JsonNull } ?: o["message_uid"],
                role = o.str("role"),
                content = o["content"]?.takeUnless { it is JsonNull } ?: o["text"],
                timestamp = o["timestamp"],
                display_kind = o.str("display_kind"),
                finish_reason = o.str("finish_reason") ?: if ((o["tool_calls"] as? JsonArray)?.isNotEmpty() == true) "tool_calls" else null,
            )
        }
        return MessageListDto(session_id = snapshot.str("session_id") ?: sessionId, data = messages)
    }

    override suspend fun patchSession(id: String, fields: JsonObject): SessionDto? {
        fields["title"]?.let { (it as? JsonPrimitive)?.contentOrNull }?.let { title ->
            try {
                mutate(id, "rename", buildJsonObject { put("title", title) })
            } catch (e: HermesError.Server) {
                // Hermes 要求标题不重复；重名时 gateway 只回一个笼统的 request_failed
                if (e.code != "request_failed") throw e
                throw HermesError.Conflict("title_in_use", "改名没成功：可能已经有对话叫「$title」了（Hermes 要求标题不重复）")
            }
        }
        (fields["pinned"] as? JsonPrimitive)?.booleanOrNull?.let { pinned ->
            mutate(id, "sidebar", buildJsonObject { put("pinned", pinned) })
        }
        (fields["archived"] as? JsonPrimitive)?.booleanOrNull?.let { archived ->
            mutate(id, "archive", buildJsonObject { put("archived", archived) })
        }
        return session(id)
    }

    override suspend fun deleteSession(id: String) {
        mutate(id, "delete", JsonObject(emptyMap()), needsGeneration = true)
        attached.remove(id)
        fences.remove(id)
    }

    override suspend fun createSession(title: String?): SessionDto? {
        link.ensure()
        val snapshot = obj("session.create", buildJsonObject {
            put("source", "gui")
            if (!title.isNullOrBlank()) put("title", title)
        }, timeoutMillis = 90_000)
        val id = snapshot.str("session_id") ?: return null
        remember(id, snapshot)
        attached[id] = link.generation.value
        return SessionDto(id = id, source = "local", title = title)
    }

    override suspend fun forkSession(id: String): SessionDto? {
        val result = mutate(id, "branch", JsonObject(emptyMap()), needsGeneration = true)
        val child = result.str("branched_session_id") ?: return null
        return session(child) ?: SessionDto(id = child, source = "local")
    }

    // ---- 一轮对话 ----

    private fun runIdOf(sessionId: String, admissionId: String) = "$sessionId|$admissionId"

    private fun splitRun(runId: String): Pair<String, String> {
        val cut = runId.lastIndexOf('|')
        if (cut <= 0) throw HermesError.NotSupported("这一轮不是经 gateway 提交的")
        return runId.substring(0, cut) to runId.substring(cut + 1)
    }

    override suspend fun startRun(
        sessionId: String,
        input: JsonElement,
        idempotencyKey: String,
        instructions: String?,
        model: String?,
        provider: String?,
        workspace: String?,
        reasoningEffort: String?,
    ): RunAcceptedDto {
        attach(sessionId)
        val (text, images) = splitInput(input)
        // 图片先交给 gateway 放进它自己的暂存目录，再按路径附上（gateway 只认那里的文件）
        val attachments = images.map { (mime, bytes) ->
            val staged = obj("image.attach_bytes", buildJsonObject {
                put("session_id", sessionId)
                put("content_base64", Base64.getEncoder().encodeToString(bytes))
                put("filename", "image." + (mime.substringAfter('/').replace("jpeg", "jpg")))
            }, timeoutMillis = 120_000)
            buildJsonObject {
                put("path", staged.str("path") ?: throw HermesError.Server(500, null, "gateway 没有收下图片"))
                put("mime", staged.str("mime") ?: mime)
            }
        }
        if (reasoningEffort != null && appliedEffort[sessionId] != reasoningEffort) {
            mutate(sessionId, "reasoning", buildJsonObject { put("effort", reasoningEffort) }, needsGeneration = true)
            appliedEffort[sessionId] = reasoningEffort
        }
        val result = obj("prompt.submit", buildJsonObject {
            put("session_id", sessionId)
            put("text", text)
            put("submission_id", idempotencyKey)
            // 给模型看的补充说明（App 的格式要求、项目文件夹……）只放进这一轮给模型的文字，聊天记录里还是用户原话
            if (!instructions.isNullOrBlank()) put("api_text", text + "\n\n" + instructions)
            if (model != null) put("model_override", buildJsonObject {
                put("model", model)
                if (provider != null) put("provider", provider)
            })
            if (attachments.isNotEmpty()) put("attachments", JsonArray(attachments))
        }, timeoutMillis = 120_000)
        remember(sessionId, result)
        val admission = result.str("admission_id") ?: throw HermesError.NotHermes()
        return RunAcceptedDto(run_id = runIdOf(sessionId, admission), status = result.str("status"))
    }

    /** API Server 形状的输入（文字，或带图片的多段内容）→ 文字 + 图片（mime, 字节）。 */
    private fun splitInput(input: JsonElement): Pair<String, List<Pair<String, ByteArray>>> {
        if (input is JsonPrimitive) return (input.contentOrNull ?: "") to emptyList()
        val texts = mutableListOf<String>()
        val images = mutableListOf<Pair<String, ByteArray>>()
        fun part(p: JsonObject) {
            when (p.str("type")) {
                "text", "input_text" -> p.str("text")?.let(texts::add)
                "image_url", "input_image" -> {
                    val url = (p["image_url"] as? JsonObject)?.str("url") ?: p.str("image_url") ?: return
                    if (!url.startsWith("data:")) return
                    val mime = url.substringAfter("data:").substringBefore(';')
                    images += mime to Base64.getDecoder().decode(url.substringAfter(","))
                }
            }
        }
        (input as? JsonArray)?.forEach { item ->
            val o = item as? JsonObject ?: return@forEach
            when (val content = o["content"]) {
                is JsonArray -> content.forEach { (it as? JsonObject)?.let(::part) }
                is JsonPrimitive -> content.contentOrNull?.let(texts::add)
                else -> part(o)
            }
        }
        return texts.joinToString("\n\n") to images
    }

    override suspend fun runStatus(runId: String): RunStatusDto {
        val (sessionId, admission) = splitRun(runId)
        attach(sessionId)
        val receipt = obj("prompt.receipt", buildJsonObject {
            put("session_id", sessionId)
            put("admission_id", admission)
            put("include_result", true)
        })
        receipt.long("execution_generation")?.let { g -> fence(sessionId).let { it.generation = maxOf(it.generation, g) } }
        val status = when (receipt.str("status")) {
            "terminal" -> when (receipt.str("outcome")) {
                "completed", "steered" -> "completed"
                "interrupted", "cancelled" -> "cancelled"
                else -> "failed"
            }
            else -> "running"
        }
        val result = receipt["result"] as? JsonObject
        return RunStatusDto(
            run_id = runId,
            status = status,
            session_id = sessionId,
            output = if (status == "completed") result?.let(::finalText) else null,
            error = if (status == "failed") (result?.str("error") ?: receipt.str("outcome") ?: "Hermes 处理失败") else null,
            pending_controls = if (status == "running") pendingPrompts[sessionId]?.takeIf { it.isNotEmpty() } else null,
            execution_generation = JsonPrimitive(fence(sessionId).generation),
        )
    }

    /** 一轮的最终回复（receipt 里存的结构化结果，字段随版本变过）。 */
    private fun finalText(result: JsonObject): String? =
        listOf("final_response", "response", "output", "text").firstNotNullOfOrNull { result.str(it)?.takeIf(String::isNotBlank) }

    private fun generationOf(body: JsonObject, sessionId: String): Long =
        (body["execution_generation"] as? JsonPrimitive)?.longOrNull ?: fence(sessionId).generation

    override suspend fun respondApproval(runId: String, body: JsonObject) {
        val (sessionId, _) = splitRun(runId)
        attach(sessionId)
        obj("approval.respond", buildJsonObject {
            put("session_id", sessionId)
            put("execution_generation", generationOf(body, sessionId))
            put("prompt_id", body.str("request_id").orEmpty())
            put("choice", body.str("choice") ?: "deny")
        })
    }

    override suspend fun respondClarify(runId: String, body: JsonObject) {
        val (sessionId, _) = splitRun(runId)
        attach(sessionId)
        obj("clarify.respond", buildJsonObject {
            put("session_id", sessionId)
            put("execution_generation", generationOf(body, sessionId))
            put("prompt_id", body.str("request_id").orEmpty())
            put("answer", body.str("answer").orEmpty())
        })
    }

    /** 停止 / 插话要带当前这一轮的 generation；旧了 gateway 会带回新的，照着重试一次。 */
    private suspend fun control(method: String, sessionId: String, extra: JsonObject = JsonObject(emptyMap())): JsonObject {
        attach(sessionId)
        repeat(2) { attempt ->
            try {
                return link.call(method, JsonObject(extra + mapOf(
                    "session_id" to JsonPrimitive(sessionId),
                    "execution_generation" to JsonPrimitive(fence(sessionId).generation),
                ))) as? JsonObject ?: JsonObject(emptyMap())
            } catch (e: GatewayException) {
                val fresh = e.data?.long("execution_generation")
                if (e.reason != "stale_generation" || fresh == null || attempt == 1) throw e.toHermesError()
                fence(sessionId).generation = fresh
            }
        }
        error("unreachable")
    }

    override suspend fun steerRun(runId: String, text: String) {
        val (sessionId, _) = splitRun(runId)
        val result = control("session.steer", sessionId, buildJsonObject { put("text", text) })
        if (result.str("status") == "rejected") throw HermesError.Conflict("run_not_accepting_steer", "这一轮已经不收插话了")
    }

    override suspend fun stopRun(runId: String) {
        val (sessionId, _) = splitRun(runId)
        control("session.interrupt", sessionId)
    }

    override fun runEvents(runId: String, afterSeq: Long): Flow<SseEvent> = channelFlow {
        val (sessionId, admission) = splitRun(runId)
        val live = Channel<JsonObject>(Channel.UNLIMITED)
        // 先开始收，再补发：中间来的事件不会漏（按 seq 去重）
        launch(start = CoroutineStart.UNDISPATCHED) {
            link.events.collect { if (it.str("session_id") == sessionId) live.send(it) }
        }
        try {
            attach(sessionId)
        } catch (e: HermesError) {
            close(e)
            return@channelFlow
        }
        launch {
            link.connected.drop(1).first { !it }
            close(HermesError.Unreachable("和 Hermes gateway 的连接断了"))
        }
        var last = afterSeq
        var done = false
        suspend fun handle(event: JsonObject) {
            val seq = event.long("seq")
            if (seq != null) {
                if (seq <= last) return
                last = seq
            }
            event.long("execution_generation")?.let { g -> fence(sessionId).let { it.generation = maxOf(it.generation, g) } }
            val translated = translate(event, admission) ?: return
            send(SseEvent(seq, null, translated.toString()))
            if (translated.str("event") in TerminalEvents) done = true
        }
        val replay = try {
            obj("session.events.since", buildJsonObject {
                put("session_id", sessionId)
                put("last_sequence", maxOf(0L, afterSeq))
                epochs[sessionId]?.let { put("replay_epoch", it) }
            })
        } catch (e: HermesError) {
            JsonObject(emptyMap())
        }
        if ((replay["snapshot_required"] as? JsonPrimitive)?.booleanOrNull == true || (replay["truncated"] as? JsonPrimitive)?.booleanOrNull == true) {
            send(SseEvent(null, null, buildJsonObject { put("event", "replay.truncated") }.toString()))
        }
        replay.str("replay_epoch")?.let { epochs[sessionId] = it }
        for (e in (replay["events"] as? JsonArray).orEmpty()) {
            (e as? JsonObject)?.let { handle(it) }
            if (done) break
        }
        while (!done) handle(live.receive())
        close()
    }

    /** gateway 事件 → API Server 事件流的 JSON；和这一轮无关的返回 null。 */
    private fun translate(event: JsonObject, admission: String): JsonObject? {
        val type = event.str("type") ?: return null
        val payload = event["payload"] as? JsonObject ?: JsonObject(emptyMap())
        val owner = event.str("admission_id") ?: payload.str("admission_id")
        if (owner != null && owner != admission) return null
        if (owner == null && type !in setOf("approval.request", "clarify.request", "approval.settled", "clarify.settled")) return null
        val generation = event["execution_generation"] ?: payload["execution_generation"]
        return when (type) {
            "message.delta" -> payload.str("text")?.takeIf { it.isNotEmpty() }?.let { text ->
                buildJsonObject { put("event", "message.delta"); put("delta", text) }
            }
            "message.interim" -> buildJsonObject {
                put("event", "message.interim")
                put("text", payload.str("text").orEmpty())
                put("already_streamed", (payload["already_streamed"] as? JsonPrimitive)?.booleanOrNull == true)
            }
            "reasoning.delta" -> payload.str("text")?.takeIf { it.isNotEmpty() }?.let { text ->
                buildJsonObject { put("event", "reasoning.delta"); put("delta", text) }
            }
            "tool.start" -> buildJsonObject {
                put("event", "tool.started")
                put("tool", payload.str("name") ?: payload.str("tool_name") ?: "工具")
                (payload.str("tool_call_id") ?: payload.str("tool_id"))?.let { put("tool_call_id", it) }
                put("preview", payload.str("context").orEmpty())
            }
            "tool.complete" -> {
                val failed = (payload["is_error"] as? JsonPrimitive)?.booleanOrNull == true
                buildJsonObject {
                    put("event", if (failed) "tool.failed" else "tool.completed")
                    put("tool", payload.str("name") ?: payload.str("tool_name") ?: "工具")
                    (payload.str("tool_call_id") ?: payload.str("tool_id"))?.let { put("tool_call_id", it) }
                    put("preview", payload.str("result").orEmpty().take(500))
                    put("error", failed)
                    payload["duration"]?.takeUnless { it is JsonNull }?.let { put("duration", it) }
                    payload["duration_s"]?.takeUnless { it is JsonNull }?.let { put("duration", it) }
                }
            }
            "approval.request", "clarify.request" -> JsonObject(payload + buildJsonObject {
                put("event", type)
                put("kind", if (type == "clarify.request") "clarify" else "approval")
                if (generation != null && "execution_generation" !in payload) put("execution_generation", generation)
            })
            "approval.settled", "clarify.settled" -> buildJsonObject {
                put("event", type)
                payload.str("prompt_id")?.let { put("prompt_id", it) }
                (payload.str("choice") ?: payload.str("answer"))?.let { put("choice", it) }
            }
            "message.complete" -> when (payload.str("outcome") ?: payload.str("status")) {
                "cancelled", "interrupted" -> buildJsonObject { put("event", "run.cancelled") }
                "failed", "error" -> buildJsonObject {
                    put("event", "run.failed")
                    put("error", payload.str("error") ?: payload.str("text")?.takeIf(String::isNotBlank) ?: "Hermes 处理失败")
                }
                else -> buildJsonObject { put("event", "run.completed"); put("output", payload.str("text").orEmpty()) }
            }
            "error" -> buildJsonObject {
                put("event", "run.failed")
                put("error", payload.str("message") ?: payload.str("text") ?: "Hermes 处理失败")
            }
            else -> null
        }
    }

    /** gateway 快照里的待处理审批 / 追问 → API Server `pending_controls` 的形状。 */
    private fun promptToApi(prompt: JsonObject): JsonObject = JsonObject(prompt + buildJsonObject {
        put("kind", prompt.str("kind") ?: "approval")
    })

    // ---- 模型 ----

    override suspend fun modelOptions(): ModelOptionsDto =
        HermesJson.decodeFromJsonElement(obj("model.options", JsonObject(emptyMap()), timeoutMillis = 90_000))

    override suspend fun lockSessionModel(sessionId: String, model: String, provider: String) {
        mutate(sessionId, "model", buildJsonObject { put("model", model); put("provider", provider) }, needsGeneration = true)
    }

    override suspend fun healthDetailed(): JsonObject = throw HermesError.NotSupported("gateway 状态")

    // ---- 技能、工具集、定时任务（桥里用 Hermes 的 Python 函数办）----

    override suspend fun skills(): SkillListDto = HermesJson.decodeFromJsonElement(local("skills"))

    override suspend fun toolsets(): ToolsetListDto = HermesJson.decodeFromJsonElement(local("toolsets"))

    private suspend fun jobsOp(op: String, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject =
        local("jobs", buildJsonObject { put("op", op); block() })

    private fun jobOf(result: JsonObject): JobDto? =
        result["job"]?.takeUnless { it is JsonNull }?.let { HermesJson.decodeFromJsonElement<JobDto>(it) }

    override suspend fun jobs(): JobListDto = HermesJson.decodeFromJsonElement(jobsOp("list"))

    override suspend fun createJob(fields: JsonObject): JobDto? = jobOf(jobsOp("create") { put("fields", fields) })

    override suspend fun updateJob(id: String, fields: JsonObject): JobDto? = jobOf(jobsOp("update") { put("job_id", id); put("fields", fields) })

    override suspend fun deleteJob(id: String) {
        jobsOp("delete") { put("job_id", id) }
    }

    override suspend fun jobAction(id: String, action: String): JobDto? = jobOf(jobsOp(action) { put("job_id", id) })

    override suspend fun job(id: String): JobDto? = jobOf(jobsOp("get") { put("job_id", id) })

    companion object {
        private val TerminalEvents = setOf("run.completed", "run.failed", "run.cancelled")
        private val ReadOnlyMethods = setOf("session.resume", "session.events.since", "prompt.receipt", "model.options", "ping")
    }
}

/** gateway 的拒绝原因 → App 认识的错误。 */
internal fun GatewayException.toHermesError(): HermesError = when {
    disconnected -> HermesError.Unreachable(message ?: "和 Hermes gateway 的连接断了")
    reason == "unknown_method" -> HermesError.NotSupported("这个版本的 Hermes gateway 没有这个功能")
    reason == "permission_denied" -> HermesError.Conflict("permission_denied", "没有权限（这个会话可能属于别的 bot 或别的用户）")
    reason == "session_busy" || reason == "admission_conflict" -> HermesError.Conflict("session_active_turn", "这个会话正在别处回复中，等它结束再发")
    reason == "not_found" -> HermesError.NotSupported("找不到这个会话")
    reason == "queue_full" -> HermesError.Server(429, "queue_full", "Hermes 同时处理的任务太多了")
    else -> HermesError.Server(500, reason, "Hermes gateway：$reason")
}

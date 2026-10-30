package com.hermes.mobile.data.ssh

import com.hermes.mobile.data.remote.HermesJson
import kotlinx.serialization.json.*
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 测试用的 Hermes gateway：跑在测试 SSH 服务器里，代替服务器上的小桥（[com.hermes.mobile.data.remote.GatewayBridge]），
 * 按同一套一行一个 JSON 的说法应答。行为照着真实 gateway 录下来的样子做（见 docs/protocol.md 的 gateway 一节）：
 * 订阅后才能发消息、事件带每个会话自己的 seq、可以按 seq 补发、改会话要带 revision、回复审批要带 generation。
 *
 * 假模型的剧本和 [com.hermes.mobile.data.remote.FakeHermesServer] 一样：默认先说一句、跑一个工具、再给“最终回复：原话”；
 * “审批”先要批准；“选项”先追问颜色；“慢”一直不结束直到被停止；“画图”回复里带 MEDIA；带图片时回“收到 N 张图片”。
 */
class FakeGateway {
    /** 手机发来的每一个请求（方法名在 `method` 或 `local` 里）。 */
    val requests = CopyOnWriteArrayList<JsonObject>()
    /** false：桥说 gateway 没在运行。 */
    @Volatile var running = true
    /** 每次 session.mutate 进来先调它：用来模拟“Hermes 正好在自己起标题”，把 revision 改掉。 */
    @Volatile var beforeMutate: ((Session) -> Unit)? = null
    /** 暂存图片放在这里（相当于 Hermes 的 cache/images）。 */
    val stagingDir: java.nio.file.Path = java.nio.file.Files.createTempDirectory("gateway-images")

    inner class Session(val id: String, var title: String?, val source: String) {
        @Volatile var revision = 0L
        @Volatile var generation = 0L
        @Volatile var pinned = false
        @Volatile var archived = false
        @Volatile var effort: String? = null
        @Volatile var model: String? = null
        val started = System.currentTimeMillis() / 1000.0
        val messages = CopyOnWriteArrayList<JsonObject>()
        val events = CopyOnWriteArrayList<JsonObject>()
        val epoch: String = UUID.randomUUID().toString().replace("-", "")
        val answers = ConcurrentHashMap<String, CompletableFuture<String>>()
        val steers = CopyOnWriteArrayList<String>()
        @Volatile var interrupted = false
        @Volatile var busy = false
        /** 测试让“慢”的那一轮正常结束（比如在手机断线时）。 */
        @Volatile var released = false
    }

    val sessions = ConcurrentHashMap<String, Session>()
    private val rowIds = AtomicLong(100)
    /** admission id → (状态, 结局, 最终回复)。 */
    private val receipts = ConcurrentHashMap<String, Triple<String, String?, String?>>()
    private val submissions = ConcurrentHashMap<String, String>()
    private val connections = CopyOnWriteArrayList<Connection>()

    init {
        // 和 FakeHermesServer 的 s1 对应：一段已有的对话
        val s1 = Session("s1", "家庭网络", "api_server")
        s1.messages += message("user", "网络老断")
        s1.messages += message("assistant", "DNS 改好了。", finish = "stop")
        sessions[s1.id] = s1
    }

    private fun message(role: String, content: String, finish: String? = null) = buildJsonObject {
        put("role", role)
        put("content", content)
        put("row_id", rowIds.incrementAndGet())
        put("timestamp", System.currentTimeMillis() / 1000.0)
        if (finish != null) put("finish_reason", finish)
    }

    class Refusal(val reason: String, val data: JsonObject = buildJsonObject { put("reason", reason) }) : Exception(reason)

    /** 一条 SSH 通道上的一个桥（一条 gateway 连接）。 */
    inner class Connection(private val emit: (String) -> Unit) {
        val subscriptions: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun send(event: JsonObject) = emit(buildJsonObject { put("event", event) }.toString())

        fun handle(req: JsonObject): JsonElement {
            requests += req
            val p = req["params"] as? JsonObject ?: JsonObject(emptyMap())
            req["local"]?.jsonPrimitive?.content?.let { return local(it, p) }
            return when (val method = req["method"]!!.jsonPrimitive.content) {
                "ping" -> JsonObject(emptyMap())
                "session.create" -> {
                    val s = Session("local-" + UUID.randomUUID().toString().replace("-", ""), p["title"]?.jsonPrimitive?.contentOrNull, "local")
                    sessions[s.id] = s
                    subscriptions += s.id
                    snapshot(s, withMessages = false)
                }
                "session.resume" -> {
                    val s = session(p)
                    subscriptions += s.id
                    snapshot(s, withMessages = p["omit_messages"]?.jsonPrimitive?.booleanOrNull != true)
                }
                "prompt.submit" -> submit(p)
                "prompt.receipt" -> {
                    val (status, outcome, final) = receipts[p.str("admission_id")] ?: throw Refusal("not_found")
                    buildJsonObject {
                        put("admission_id", p.str("admission_id"))
                        put("status", status)
                        outcome?.let { put("outcome", it) }
                        put("execution_generation", session(p).generation)
                        if (final != null) put("result", buildJsonObject { put("final_response", final) })
                    }
                }
                "approval.respond", "clarify.respond" -> {
                    val s = session(p)
                    checkGeneration(s, p)
                    val answer = p.str("choice") ?: p.str("answer") ?: ""
                    s.answers.remove(p.str("prompt_id"))?.complete(answer) ?: throw Refusal("not_found")
                    buildJsonObject { put("status", "resolved"); put("prompt_id", p.str("prompt_id")) }
                }
                "session.steer" -> {
                    val s = session(p)
                    checkGeneration(s, p)
                    if (!s.busy) return buildJsonObject { put("status", "rejected"); put("text", p.str("text")) }
                    s.steers += p.str("text")!!
                    buildJsonObject { put("status", "queued"); put("text", p.str("text")); put("execution_generation", s.generation) }
                }
                "session.interrupt" -> {
                    val s = session(p)
                    checkGeneration(s, p)
                    s.interrupted = true
                    buildJsonObject { put("execution_generation", s.generation); put("execution_state", "running") }
                }
                "session.events.since" -> {
                    val s = session(p)
                    val epoch = p.str("replay_epoch")
                    if (epoch != null && epoch != s.epoch) return buildJsonObject { put("events", JsonArray(emptyList())); put("snapshot_required", true) }
                    val after = p["last_sequence"]?.jsonPrimitive?.long ?: 0
                    buildJsonObject {
                        put("events", JsonArray(s.events.filter { it["seq"]!!.jsonPrimitive.long > after }))
                        put("replay_epoch", s.epoch)
                        put("last_sequence", s.events.size.toLong())
                    }
                }
                "session.mutate" -> mutate(p)
                "image.attach_bytes" -> {
                    val bytes = java.util.Base64.getDecoder().decode(p.str("content_base64"))
                    val file = stagingDir.resolve("upload_" + UUID.randomUUID().toString().take(8) + ".jpg")
                    java.nio.file.Files.write(file, bytes)
                    buildJsonObject { put("path", file.toString()); put("mime", "image/jpeg") }
                }
                "model.options" -> HermesJson.parseToJsonElement(
                    """{"providers":[{"slug":"custom","name":"本机","is_current":true,"authenticated":true,"models":["fake-model","other-model"],
                       "capabilities":{"fake-model":{"reasoning":true},"other-model":{"reasoning":false}}}],"model":"fake-model","provider":"custom"}""",
                )
                else -> if (method.startsWith("groups.")) JsonObject(emptyMap()) else throw Refusal("unknown_method")
            }
        }

        private fun submit(p: JsonObject): JsonElement {
            val s = session(p)
            if (s.id !in subscriptions) throw Refusal("permission_denied")
            val submission = p.str("submission_id") ?: throw Refusal("invalid_params")
            submissions[submission]?.let { return buildJsonObject { put("admission_id", it); put("status", "queued") } }
            val admission = UUID.randomUUID().toString().replace("-", "")
            submissions[submission] = admission
            receipts[admission] = Triple("queued", null, null)
            val attachments = (p["attachments"] as? JsonArray).orEmpty()
            attachments.forEach { a ->
                val path = a.jsonObject.str("path")!!
                if (!java.nio.file.Paths.get(path).startsWith(stagingDir)) throw Refusal("invalid_params")
            }
            Thread { turn(s, admission, p.str("text").orEmpty(), p.str("api_text"), attachments.size) }.apply { isDaemon = true; start() }
            return buildJsonObject { put("admission_id", admission); put("status", "queued") }
        }

        private fun mutate(p: JsonObject): JsonElement {
            val s = session(p)
            beforeMutate?.invoke(s)
            val expected = p["expected_revision"]?.jsonPrimitive?.long
            if (expected != s.revision) throw Refusal("revision_conflict", buildJsonObject {
                put("reason", "revision_conflict"); put("revision", s.revision); put("execution_generation", s.generation)
            })
            val payload = p["payload"]!!.jsonObject
            val op = p.str("operation")!!
            if (op in setOf("delete", "branch", "model", "reasoning")) checkGeneration(s, buildJsonObject { put("execution_generation", p["expected_generation"] ?: JsonNull) })
            val extra = buildJsonObject {
                when (op) {
                    "rename" -> {
                        val title = payload.str("title")
                        if (sessions.values.any { it !== s && it.title == title }) throw Refusal("request_failed")
                        s.title = title
                        put("title", title)
                    }
                    "sidebar" -> { s.pinned = payload["pinned"]!!.jsonPrimitive.boolean; put("pinned", s.pinned) }
                    "archive" -> { s.archived = payload["archived"]!!.jsonPrimitive.boolean; put("archived", s.archived) }
                    "reasoning" -> { s.effort = payload.str("effort"); s.generation++; put("effort", s.effort) }
                    "model" -> { s.model = payload.str("model"); s.generation++ }
                    "delete" -> sessions.remove(s.id)
                    "branch" -> {
                        val child = Session("local-" + UUID.randomUUID().toString().replace("-", ""), (s.title ?: "会话") + " #2", "local")
                        child.messages += s.messages
                        sessions[child.id] = child
                        subscriptions += child.id
                        put("branched_session_id", child.id)
                    }
                    else -> throw Refusal("invalid_params")
                }
            }
            s.revision++
            return JsonObject(extra + buildJsonObject {
                put("session_id", s.id); put("operation", op); put("revision", s.revision); put("execution_generation", s.generation)
            })
        }
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun session(p: JsonObject) = sessions[p.str("session_id")] ?: throw Refusal("not_found")

    private fun checkGeneration(s: Session, p: JsonObject) {
        val given = p["execution_generation"]?.jsonPrimitive?.longOrNull
        if (given != s.generation) throw Refusal("stale_generation", buildJsonObject {
            put("reason", "stale_generation"); put("revision", s.revision); put("execution_generation", s.generation)
        })
    }

    private fun snapshot(s: Session, withMessages: Boolean) = buildJsonObject {
        put("session_id", s.id)
        put("stored_session_id", s.id)
        put("messages", if (withMessages) JsonArray(s.messages) else JsonArray(emptyList()))
        put("message_count", s.messages.size)
        put("running", s.busy)
        put("replay_epoch", s.epoch)
        put("last_sequence", s.events.size.toLong())
        put("subscription_id", UUID.randomUUID().toString())
        put("revision", s.revision)
        put("execution_generation", s.generation)
        put("prompts", JsonArray(emptyList()))
        put("info", buildJsonObject { put("reasoning_effort", s.effort.orEmpty()); s.model?.let { put("model", it) } })
    }

    /** 推一个事件给订阅了这个会话的连接（也存下来，按 seq 补发用）。 */
    private fun publish(s: Session, type: String, payload: JsonObject, admission: String?) {
        val event = buildJsonObject {
            put("type", type)
            put("session_id", s.id)
            put("payload", payload)
            put("seq", s.events.size + 1L)
            put("replay_epoch", s.epoch)
            put("execution_generation", s.generation)
            if (admission != null) put("admission_id", admission)
        }
        s.events += event
        connections.filter { s.id in it.subscriptions }.forEach { runCatching { it.send(event) } }
    }

    private fun turn(s: Session, admission: String, text: String, apiText: String?, images: Int) {
        while (s.busy) Thread.sleep(20)
        s.busy = true
        s.interrupted = false
        s.released = false
        s.steers.clear()
        s.generation++
        receipts[admission] = Triple("started", null, null)
        val stored = (1..images).joinToString("") {
            "[The user sent an image~ Here's what I can see:\n一张测试图片。]\n[If you need a closer look, use vision_analyze with image_url: $stagingDir/x.jpg ~]\n\n"
        } + text
        s.messages += message("user", stored)
        val modelSees = apiText ?: text
        publish(s, "message.start", JsonObject(emptyMap()), admission)
        publish(s, "reasoning.delta", buildJsonObject { put("text", "SECRET-THINKING 不能显示") }, admission)
        fun ask(type: String, fields: JsonObjectBuilder.() -> Unit): String {
            val id = UUID.randomUUID().toString().take(10)
            val answer = CompletableFuture<String>()
            s.answers[id] = answer
            publish(s, type, buildJsonObject { put("prompt_id", id); put("execution_generation", s.generation); fields() }, admission)
            val got = runCatching { answer.get(15, TimeUnit.SECONDS) }.getOrDefault("deny")
            publish(s, type.replace("request", "settled"), buildJsonObject { put("prompt_id", id); put("choice", got) }, admission)
            return got
        }
        var outcome = "completed"
        val reply: String = when {
            "审批" in text -> {
                val choice = ask("approval.request") {
                    put("kind", "approval"); put("command", "rm -rf /tmp/x"); put("description", "删除临时目录")
                    put("choices", buildJsonArray { add("once"); add("session"); add("always"); add("deny") })
                }
                if (choice == "deny") "好的，不执行了。" else "最终回复：$text"
            }
            "选项" in text -> {
                ask("clarify.request") {
                    put("kind", "clarify"); put("question", "选择颜色")
                    put("choices", buildJsonArray { add("蓝色"); add("绿色") })
                }
                "最终回复：$text"
            }
            "慢" in text -> {
                publish(s, "message.delta", buildJsonObject { put("text", "正在") }, admission)
                val until = System.currentTimeMillis() + 15_000
                while (!s.interrupted && !s.released && s.steers.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
                when {
                    s.steers.isNotEmpty() -> "收到插话：${s.steers.last()}"
                    s.released -> "最终回复：$text"
                    else -> { outcome = "interrupted"; "正在" }
                }
            }
            "画图" in text -> "画好了，送你一只猫。\nMEDIA:/gen/cat.png"
            images > 0 -> "收到 $images 张图片：$text"
            else -> {
                publish(s, "message.delta", buildJsonObject { put("text", "我先查一下。") }, admission)
                publish(s, "tool.start", buildJsonObject { put("name", "terminal"); put("tool_call_id", "t-$admission"); put("context", "ls") }, admission)
                publish(s, "tool.complete", buildJsonObject {
                    put("name", "terminal"); put("tool_call_id", "t-$admission"); put("context", "ls"); put("is_error", false); put("result", "a.txt")
                }, admission)
                "最终回复：$text"
            }
        }
        if (outcome == "completed") {
            if (!modelSees.startsWith(text)) error("给模型的文字应当以用户原话开头")
            publish(s, "message.delta", buildJsonObject { put("text", "\n\n$reply") }, admission)
            s.messages += message("assistant", reply, finish = "stop")
        }
        if (s.title == null) s.title = "关于" + text.take(12)
        receipts[admission] = Triple("terminal", outcome, reply.takeIf { outcome == "completed" })
        s.busy = false
        publish(s, "message.complete", buildJsonObject {
            put("text", reply); put("outcome", outcome); put("status", if (outcome == "completed") "complete" else "interrupted"); put("admission_id", admission)
        }, admission)
    }

    private fun local(name: String, p: JsonObject): JsonElement = when (name) {
        "sessions" -> buildJsonObject {
            put("data", JsonArray(sessions.values.filter { !it.archived }.sortedByDescending { it.started }.map { row(it) }))
        }
        "session" -> buildJsonObject { put("session", sessions[p.str("session_id")]?.let(::row) ?: JsonNull) }
        "skills" -> HermesJson.parseToJsonElement("""{"data":[{"name":"github-pr-review","description":"审 PR","category":"开发"}]}""")
        "toolsets" -> HermesJson.parseToJsonElement("""{"data":[{"name":"web","label":"Web","description":"web_search","enabled":true,"configured":true,"tools":["web_search"]}]}""")
        "jobs" -> HermesJson.parseToJsonElement("""{"jobs":[]}""")
        else -> throw Refusal("unknown_method")
    }

    private fun row(s: Session) = buildJsonObject {
        put("id", s.id); put("source", s.source); put("title", s.title)
        put("started_at", s.started); put("last_active", s.started); put("message_count", s.messages.size)
        put("preview", s.messages.firstOrNull { it.str("role") == "user" }?.str("content")?.take(60))
        put("pinned", s.pinned); put("archived", s.archived); put("hidden", false)
    }

    /** 测试 SSH 服务器上的命令：`hermes config path` 回个路径；桥脚本按 gateway 应答；别的回空。 */
    inner class BridgeCommand(private val command: String) : Command {
        private lateinit var input: InputStream
        private lateinit var output: OutputStream
        private lateinit var callback: ExitCallback
        @Volatile private var connection: Connection? = null
        override fun setInputStream(value: InputStream) { input = value }
        override fun setOutputStream(value: OutputStream) { output = value }
        override fun setErrorStream(value: OutputStream) {}
        override fun setExitCallback(value: ExitCallback) { callback = value }
        override fun destroy(channel: ChannelSession) {
            connection?.let(connections::remove)
            runCatching { input.close() }
        }

        override fun start(channel: ChannelSession, env: Environment) {
            Thread {
                val lock = Any()
                fun emit(text: String) = synchronized(lock) { output.write((text + "\n").toByteArray(Charsets.UTF_8)); output.flush() }
                try {
                    if (command.contains("config path")) {
                        emit("/tmp/hermes-fixture/config.yaml")
                        return@Thread
                    }
                    if (!command.contains("def local_sessions")) {
                        emit("{}")
                        return@Thread
                    }
                    if (!running) {
                        emit("""{"ready":false,"error":"Hermes gateway 没在运行（stopped）。在电脑上运行 hermes gateway 启动它"}""")
                        return@Thread
                    }
                    val conn = Connection(::emit)
                    connection = conn
                    connections += conn
                    emit("""{"ready":true,"code_sha":"abcdef1234567","capabilities":["session-authority-v1"],"home":"/tmp/hermes-fixture"}""")
                    input.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        val req = runCatching { HermesJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEachLine
                        val id = req["id"]
                        // 每个请求单独一个线程，像真的桥一样可以并发
                        Thread {
                            val reply = try {
                                buildJsonObject { put("id", id ?: JsonNull); put("result", conn.handle(req)) }
                            } catch (e: Refusal) {
                                buildJsonObject { put("id", id ?: JsonNull); put("error", buildJsonObject { put("reason", e.reason); put("data", e.data) }) }
                            } catch (e: Exception) {
                                buildJsonObject { put("id", id ?: JsonNull); put("error", buildJsonObject { put("reason", e.toString()) }) }
                            }
                            runCatching { emit(reply.toString()) }
                        }.apply { isDaemon = true; start() }
                    }
                } catch (_: Exception) {
                } finally {
                    connection?.let(connections::remove)
                    callback.onExit(0)
                }
            }.apply { isDaemon = true; start() }
        }
    }

    /** 装到测试 SSH 服务器上。 */
    fun install(server: TestSshServer): TestSshServer = server.apply {
        sshd.commandFactory = org.apache.sshd.server.command.CommandFactory { _, command -> BridgeCommand(command) }
    }
}

package com.hermes.mobile.data.remote

import java.util.concurrent.TimeUnit

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * 模拟不同版本的 Hermes API Server。响应结构照 hermes-agent 源码里的 api_server.py 写，
 * 并故意加了一些未知字段、思考内容、工具消息，用来验证 App 的容错和过滤。
 */
class FakeHermesServer(val version: Version, val key: String = "test-key-0123456789abcdef") {

    enum class Version {
        /** v0.22 左右：有 /v1/capabilities（含 endpoints 表）、完整会话接口。 */
        Modern,
        /** v2026.6.19 左右：有会话接口，但没有 pinned/hidden 等字段，时间是字符串。 */
        Mid,
        /** v2026.4 左右：没有 /v1/capabilities、没有会话接口。 */
        Old,
        /** 不是 Hermes：随便一个返回网页的服务器。 */
        NotHermes,
        /** 新版 Hermes 的 `hermes serve`（桌面版后端）占了这个端口。 */
        HermesServe,
    }

    val server = MockWebServer()
    // 测试线程遍历时服务器线程还在写：用写时复制的列表，遍历不会出错
    val requests: MutableList<RecordedRequest> = java.util.concurrent.CopyOnWriteArrayList()
    var failPatch = false
    @Volatile var patchFailureGate: java.util.concurrent.CountDownLatch? = null
    /** PATCH 过 archived=true 的会话：列表里就带上 archived，客户端不该再显示。 */
    val archivedIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** 模拟有的 Hermes 模式：聊天记录里图片只记一个 [screenshot]，不记路径。 */
    var screenshotOnly = false
    /** 模拟 Hermes 在用户消息后追加系统提示（换了客户端时）。 */
    var appendSystemNote = false
    /** 前几次提交对话返回 503（带 Retry-After: 0），用来验证重试。 */
    var failRunsTimes = 0

    /**
     * 一轮对话的剧本。按用户输入里的关键词决定：
     * 默认：思考 → 一段过程中的话 → 工具 → 最终回复；“审批”：先要审批，批准后才回复；
     * “断线”：第一次事件流只发一半就断；“慢”：一直不结束，直到被停止。
     */
    inner class FakeRun(val id: String, val sessionId: String, val input: String, val imageShas: List<String> = emptyList()) {
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        var approved: String? = null
        var stopped = false
        var eventRequests = 0
        var idempotencyKey: String? = null
        val steers = mutableListOf<String>()
        var pendingSteer: String? = null
        val finished: Boolean get() = events.any { it.contains("\"run.completed\"") || it.contains("\"run.cancelled\"") }

        private fun add(event: String, fields: String = "") {
            val seq = events.size
            val body = """{"event":"$event","run_id":"$id","timestamp":1.0${if (fields.isEmpty()) "" else ",$fields"},"seq":$seq}"""
            events += "id: $seq\ndata: $body\n\n"
        }

        init {
            add("run.started")
            add("reasoning.delta", "\"text\":\"SECRET-THINKING 不能显示\"")
            when {
                "逐句" in input -> sentence("第一句。")
                "审批" in input -> add(
                    "approval.request",
                    "\"kind\":\"approval\",\"prompt_id\":\"p1\",\"execution_generation\":3,\"command\":\"rm -rf /tmp/x\",\"description\":\"删除临时目录\",\"choices\":[\"once\",\"session\",\"always\",\"deny\"]",
                )
                "选项" in input -> add("clarify.request", "\"kind\":\"clarify\",\"prompt_id\":\"q1\",\"execution_generation\":3,\"question\":\"选择颜色\",\"choices\":[\"蓝色\",\"绿色\"]")
                "慢" in input -> add("message.delta", "\"delta\":\"正在\"")
                "断线" in input -> add("message.delta", "\"delta\":\"我先查一下。\"")
                else -> {
                    add("message.delta", "\"delta\":\"我先查一下。\"")
                    add("tool.started", "\"tool\":\"terminal\",\"preview\":\"ls\"")
                    add("tool.completed", "\"tool\":\"terminal\"")
                    add("message.delta", "\"delta\":\"\\n\\n最终\"")
                    add("message.delta", "\"delta\":\"回复\"")
                    complete()
                }
            }
        }

        /** 测试主动推进下一句；两句之间保留工具等待，验证不会退回空白。 */
        fun sentence(text: String) {
            val encoded = kotlinx.serialization.json.JsonPrimitive(text)
            add("message.delta", "\"delta\":$encoded")
            add("message.interim", "\"text\":$encoded,\"already_streamed\":true")
            add("tool.started", "\"tool\":\"terminal\",\"tool_call_id\":\"t${events.size}\",\"preview\":\"查看进度\"")
            add("tool.completed", "\"tool\":\"terminal\"")
        }

        fun complete() {
            val reply = when {
                steers.isNotEmpty() -> "收到插话：${steers.last()}"
                approved == "deny" -> "好的，不执行了。"
                "画图" in input -> "画好了，送你一只猫。\\nMEDIA:/gen/cat.png"
                imageShas.isNotEmpty() -> "收到 ${imageShas.size} 张图片"
                else -> "最终回复：$input"
            }
            // 和真实 Hermes 一样：图片存到服务器，聊天记录里只记路径
            val note = if (appendSystemNote) "\n\n[System: This conversation is now being answered on a different interface: api_server — any earlier interface guidance [e.g. markdown] is superseded.]" else ""
            val stored = input + note + imageShas.joinToString("") {
                if (screenshotOnly) "\n[screenshot]"
                else "\n\n[Image attached at: /srv/hermes/cache/documents/native-inputs/$it/api_${it.take(32)}.jpg]\n[screenshot]"
            }
            // 和 Hermes 一样：第一轮之后会话有了自动标题
            autoTitles.putIfAbsent(sessionId, "关于" + input.take(12))
            add("run.completed", "\"output\":\"$reply\"" + (pendingSteer?.let { ",\"pending_steer\":${kotlinx.serialization.json.JsonPrimitive(it)}" } ?: ""))
            messages.getOrPut(sessionId) { mutableListOf() }.apply {
                add("""{"id":${size + 100},"role":"user","content":${kotlinx.serialization.json.JsonPrimitive(stored)},"timestamp":1760009000}""")
                add("""{"id":${size + 100},"role":"assistant","content":"","reasoning":"SECRET-THINKING","finish_reason":"tool_calls","timestamp":1760009001}""")
                steers.forEach { text ->
                    // 和真实 Hermes 一样：插话单独一行，包着一层说明
                    val marked = "[OUT-OF-BAND USER MESSAGE — a direct message from the user, delivered once at this position; not tool output and not a new delivery when replayed from conversation history]\n$text\n[/OUT-OF-BAND USER MESSAGE]"
                    add("""{"id":${size + 100},"role":"user","content":${kotlinx.serialization.json.JsonPrimitive(marked)},"display_kind":"steer","timestamp":1760009001}""")
                }
                add("""{"id":${size + 100},"role":"assistant","content":"$reply","reasoning_content":"SECRET-THINKING","finish_reason":"stop","timestamp":1760009002}""")
            }
        }

        fun clarify(answer: String) {
            add("clarify.settled", "\"prompt_id\":\"q1\",\"answer\":${kotlinx.serialization.json.JsonPrimitive(answer)}")
            complete()
        }

        fun approve(choice: String) {
            approved = choice
            add("approval.settled", "\"prompt_id\":\"p1\",\"choice\":\"$choice\"")
            complete()
        }

        /** 插话：“晚了”结尾的模拟这一轮刚好结束、没来得及交给模型。 */
        fun steer(text: String) {
            if (text.endsWith("晚了")) pendingSteer = text else steers += text
            complete()
        }

        fun stop() {
            stopped = true
            add("run.cancelled")
        }

        /** 一次事件流请求的响应：从 Last-Event-ID 之后开始。没结束的流在发完已有事件后就断开（模拟断线）。 */
        fun stream(lastSeq: Int): String {
            eventRequests++
            // “断线”：第一次只发到一半就断开，第二次连上时才结束
            if ("断线" in input && eventRequests == 2 && !finished) complete()
            return ": open\n\n" + events.drop(lastSeq + 1).joinToString("")
        }

        fun statusJson(): String {
            val status = when {
                stopped -> "cancelled"
                finished -> "completed"
                "审批" in input && approved == null -> "waiting_for_approval"
                else -> "running"
            }
            val pending = if (status == "waiting_for_approval")
                """[{"kind":"approval","prompt_id":"p1","command":"rm -rf /tmp/x","description":"删除临时目录","choices":["once","deny"]}]""" else "[]"
            val output = if (finished && !stopped) (if (steers.isNotEmpty()) "收到插话：${steers.last()}" else "最终回复：$input") else ""
            val pendingField = pendingSteer?.let { ",\"pending_steer\":${kotlinx.serialization.json.JsonPrimitive(it)}" } ?: ""
            return """{"object":"hermes.run","run_id":"$id","status":"$status","session_id":"$sessionId","output":"$output","pending_controls":$pending,"execution_generation":3$pendingField}"""
        }
    }

    /** 会话 id → 锁定的 provider/model。 */
    /** 会话 id → Hermes 自动起的标题。 */
    val autoTitles = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 模拟 /v1/skills 自身出错（某些 Hermes 版本就是这样）。 */
    @Volatile var skillsBroken = false
    /** 可控地延后任务操作回包，验证连续点击的请求顺序。 */
    @Volatile var jobActionDelayMillis: Long = 0

    val modelLocks = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** 定时任务（JSON 字段，按 Hermes 的样子）。 */
    val jobs = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, Any?>>()
    /** 被“立即运行”后，下一次查询时变成已运行（模拟调度器下一轮）。 */
    private val pendingJobRuns = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    val runs = java.util.concurrent.ConcurrentHashMap<String, FakeRun>()
    /** 会话 id → 动态追加的消息（JSON）。 */
    val messages = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()
    /** 和 Hermes 一样只返回最近这么多条（工具消息也算）；null 表示全给。 */
    var messageWindow: Int? = null
    private var nextRun = 0
    private var nextSession = 0

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    fun start(): FakeHermesServer {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return handle(request)
            }
        }
        server.start()
        return this
    }

    fun shutdown() = server.shutdown()

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun error(code: Int, message: String, errCode: String? = null) =
        json("""{"error":{"message":"$message","type":"invalid_request_error","code":${errCode?.let { "\"$it\"" } ?: "null"}}}""", code)

    private fun runsApi(request: RecordedRequest, path: String): MockResponse? = synchronized(this) {
        val body = request.body.clone().readUtf8()
        when {
            path == "/api/sessions" && request.method == "POST" -> {
                val id = "new${++nextSession}"
                json("""{"object":"hermes.session","session":{"id":"$id","source":"api_server","title":null,"started_at":1760009000.0,"message_count":0}}""")
            }
            path.endsWith("/fork") && request.method == "POST" ->
                json("""{"object":"hermes.session","session":{"id":"fork1","source":"api_server","title":"家庭网络 (fork)","started_at":1760009000.0}}""")
            path == "/v1/runs" && request.method == "POST" -> {
                if (failRunsTimes > 0) {
                    failRunsTimes--
                    return@synchronized error(503, "busy").setHeader("Retry-After", "0")
                }
                val obj = HermesJson.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
                val sessionId = (obj["session_id"] as kotlinx.serialization.json.JsonPrimitive).content
                // input 是字符串，或者 [{"role":"user","content":[{"type":"text",...},{"type":"image_url",...}]}]
                val (input, shas) = when (val raw = obj["input"]) {
                    is kotlinx.serialization.json.JsonPrimitive -> raw.content to emptyList()
                    is kotlinx.serialization.json.JsonArray -> {
                        val parts = (raw.last() as kotlinx.serialization.json.JsonObject)["content"] as kotlinx.serialization.json.JsonArray
                        val text = parts.mapNotNull { p ->
                            val o = p as kotlinx.serialization.json.JsonObject
                            if ((o["type"] as kotlinx.serialization.json.JsonPrimitive).content == "text") (o["text"] as kotlinx.serialization.json.JsonPrimitive).content else null
                        }.joinToString("\n")
                        val images = parts.mapNotNull { p ->
                            val o = p as kotlinx.serialization.json.JsonObject
                            val url = ((o["image_url"] as? kotlinx.serialization.json.JsonObject)?.get("url") as? kotlinx.serialization.json.JsonPrimitive)?.content
                            url?.substringAfter(",")?.let { b64 ->
                                java.security.MessageDigest.getInstance("SHA-256").digest(java.util.Base64.getDecoder().decode(b64)).joinToString("") { "%02x".format(it) }
                            }
                        }
                        text to images
                    }
                    else -> return@synchronized error(400, "Missing 'input' field")
                }
                if (sessionId == "s2") return@synchronized error(409, "permission denied", "permission_denied")
                // 同一个幂等键返回同一轮
                val key = request.getHeader("Idempotency-Key")
                val existing = runs.values.firstOrNull { it.idempotencyKey == key && key != null }
                val run = existing ?: FakeRun("run_${++nextRun}", sessionId, input, shas).also { it.idempotencyKey = key; runs[it.id] = it }
                json("""{"run_id":"${run.id}","status":"started"}""", 202)
            }
            path == "/v1/skills" -> if (skillsBroken) {
                error(500, "Failed to enumerate skills", "server_error")
            } else {
                json(
                    """{"object":"list","data":[
                       {"name":"arxiv","description":"Search and summarize arXiv papers.","category":"research"},
                       {"name":"obsidian","description":"Read, search, create, and edit notes in the Obsidian vault.","category":"note-taking"},
                       {"name":"plain","description":null,"category":null}
                    ]}""",
                )
            }
            path == "/v1/toolsets" -> json(
                """{"object":"list","platform":"api_server","data":[
                   {"name":"web","label":"🔍 Web Search & Scraping","description":"web_search, web_extract","enabled":true,"configured":true,"tools":["web_extract","web_search"]},
                   {"name":"image_gen","label":"🎨 Image Generation","description":"image_generate","enabled":false,"configured":false,"tools":["image_generate"]}
                ]}""",
            )
            path == "/api/model/options" -> json(
                """{"model":"fake-model","provider":"custom","providers":[
                   {"slug":"custom","name":"Custom endpoint","is_current":true,"authenticated":true,"models":["fake-model"],"capabilities":{"fake-model":{"reasoning":true}}},
                   {"slug":"openrouter","name":"OpenRouter","is_current":false,"authenticated":true,"models":["anthropic/claude-sonnet-5.5","openai/gpt-6.1-sol"]},
                   {"slug":"nous","name":"Nous Portal","is_current":false,"authenticated":false,"models":[]}
                ]}""",
            )
            path.matches(Regex("/api/sessions/[^/]+/model")) && request.method == "POST" -> {
                val id = path.removePrefix("/api/sessions/").removeSuffix("/model")
                val obj = HermesJson.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
                val model = (obj["model"] as kotlinx.serialization.json.JsonPrimitive).content
                val provider = (obj["provider"] as kotlinx.serialization.json.JsonPrimitive).content
                modelLocks[id] = "$provider/$model"
                json("""{"object":"hermes.session.model_lock","session_id":"$id","runtime":{"provider":"$provider","model":"$model"}}""")
            }
            path == "/api/jobs" && request.method == "GET" -> json("""{"jobs":[${jobs.values.joinToString(",") { jobJson(it) }}]}""")
            path == "/api/jobs" && request.method == "POST" -> {
                val obj = HermesJson.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
                val schedule = (obj["schedule"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                if (schedule == "bad") return@synchronized json("""{"error":"Invalid schedule 'bad'"}""", 400)
                val id = "%012x".format(jobs.size + 0xabc000)
                jobs[id] = mutableMapOf(
                    "id" to id, "name" to (obj["name"] as kotlinx.serialization.json.JsonPrimitive).content,
                    "prompt" to (obj["prompt"] as kotlinx.serialization.json.JsonPrimitive).content,
                    "schedule" to schedule, "deliver" to ((obj["deliver"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "local"),
                    "enabled" to true, "state" to "scheduled", "last_run_at" to null, "last_status" to null,
                )
                json("""{"job":${jobJson(jobs[id]!!)}}""")
            }
            path.startsWith("/api/jobs/") -> {
                val rest = path.removePrefix("/api/jobs/")
                val id = rest.substringBefore('/')
                val job = jobs[id] ?: return@synchronized json("""{"error":"Job not found"}""", 404)
                if (pendingJobRuns.remove(id)) {
                    job["last_run_at"] = "2026-10-18T10:55:03+00:00"
                    job["last_status"] = "ok"
                    messages.getOrPut("cron_$id") { mutableListOf() }
                    cronSessions += """{"id":"cron_$id","source":"cron","title":"${job["name"]} · Oct 18 10:55","started_at":1792316103,"last_active":1792316103,"preview":"[IMPORTANT: You are running as a scheduled cron job. DELIVERY: Your fin"}"""
                }
                when {
                    rest.endsWith("/pause") -> { job["enabled"] = false; job["state"] = "paused"; json("""{"job":${jobJson(job)}}""").setBodyDelay(jobActionDelayMillis, TimeUnit.MILLISECONDS) }
                    rest.endsWith("/resume") -> { job["enabled"] = true; job["state"] = "scheduled"; json("""{"job":${jobJson(job)}}""").setBodyDelay(jobActionDelayMillis, TimeUnit.MILLISECONDS) }
                    rest.endsWith("/run") -> { pendingJobRuns += id; json("""{"job":${jobJson(job)}}""") }
                    request.method == "PATCH" -> {
                        val obj = HermesJson.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
                        obj.forEach { (k, v) -> job[k] = (v as? kotlinx.serialization.json.JsonPrimitive)?.content }
                        json("""{"job":${jobJson(job)}}""")
                    }
                    request.method == "DELETE" -> { jobs.remove(id); json("""{"ok":true}""") }
                    else -> json("""{"job":${jobJson(job)}}""")
                }
            }
            path.startsWith("/v1/runs/") -> {
                val rest = path.removePrefix("/v1/runs/")
                val run = runs[rest.substringBefore('/')] ?: return@synchronized error(404, "Run not found", "run_not_found")
                when (rest.substringAfter('/', "")) {
                    "" -> json(run.statusJson())
                    "events" -> MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBody(run.stream(request.getHeader("Last-Event-ID")?.toInt() ?: -1))
                    "clarify" -> {
                        val obj = HermesJson.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
                        run.clarify((obj["answer"] as kotlinx.serialization.json.JsonPrimitive).content)
                        json("""{"status":"resolved"}""")
                    }
                    "approval" -> {
                        val choice = Regex("\"choice\":\"(\\w+)\"").find(body)!!.groupValues[1]
                        run.approve(choice)
                        json("""{"run_id":"${run.id}","status":"resolved"}""")
                    }
                    "steer" -> {
                        if (run.finished || run.stopped) return@synchronized error(409, "Run is not currently accepting steer input", "run_not_accepting_steer")
                        val text = (HermesJson.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject)["input"]
                            .let { (it as kotlinx.serialization.json.JsonPrimitive).content }
                        run.steer(text)
                        json("""{"object":"hermes.run.steer","run_id":"${run.id}","accepted":true}""")
                    }
                    "stop" -> {
                        run.stop()
                        json("""{"run_id":"${run.id}","status":"stopping"}""")
                    }
                    else -> null
                }
            }
            else -> null
        }
    }

    /** 定时任务跑出来的会话（列表里会出现）。 */
    val cronSessions: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    private fun jobJson(job: Map<String, Any?>): String {
        val sched = job["schedule"] as String
        val kind = if (sched.startsWith("every")) "interval" else "cron"
        fun q(v: Any?) = if (v == null) "null" else kotlinx.serialization.json.JsonPrimitive(v.toString()).toString()
        return """{"id":${q(job["id"])},"name":${q(job["name"])},"prompt":${q(job["prompt"])},
            "schedule":{"kind":"$kind","expr":${q(sched)},"display":${q(sched)}},"schedule_display":${q(sched)},
            "enabled":${job["enabled"]},"state":${q(job["state"])},"next_run_at":"2026-10-19T08:00:00+00:00",
            "last_run_at":${q(job["last_run_at"])},"last_status":${q(job["last_status"])},"last_error":null,"deliver":${q(job["deliver"])}}"""
    }

    private fun handle(request: RecordedRequest): MockResponse {
        if (version == Version.HermesServe) {
            return MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json")
                .setBody("""{"error":"Headless backend (hermes serve): web UI disabled — use `hermes dashboard` for the browser UI."}""")
        }
        if (version == Version.NotHermes) {
            return MockResponse().setHeader("Content-Type", "text/html").setBody("<html><body>Welcome to nginx!</body></html>")
        }
        val rawPath = request.path!!.substringBefore('?')
        // 和真实 Hermes 一样：没开的 Profile 前缀直接 404
        if (rawPath.startsWith("/p/") && !rawPath.startsWith("/p/work/")) {
            return json("""{"error":"Unknown or unconfigured profile"}""", 404)
        }
        val path = rawPath.removePrefix("/p/work")
        if (path == "/health") {
            val v = when (version) {
                Version.Modern -> "0.22.2"
                Version.Mid -> "2026.6.19"
                else -> "2026.4.16"
            }
            return json("""{"status":"ok","platform":"hermes-agent","version":"$v","brand_new_field":{"x":1}}""")
        }
        if (request.getHeader("Authorization") != "Bearer $key") return error(401, "Invalid API key", "invalid_api_key")

        if (version == Version.Modern) runsApi(request, path)?.let { return it }
        return when {
            path == "/v1/models" -> json("""{"object":"list","data":[{"id":"hermes-agent","object":"model"}]}""")
            path == "/v1/capabilities" -> when (version) {
                Version.Old -> MockResponse().setResponseCode(404).setBody("404: Not Found")
                Version.Mid -> json(
                    """{"object":"hermes.api_server.capabilities","platform":"hermes-agent","features":{"session_chat_streaming":true,"approval_events":true},
                       "endpoints":{"health":{"method":"GET","path":"/health"},"sessions":{"method":"GET","path":"/api/sessions"},
                       "session_messages":{"method":"GET","path":"/api/sessions/{session_id}/messages"},
                       "session_chat_stream":{"method":"POST","path":"/api/sessions/{session_id}/chat/stream"}}}""",
                )
                else -> json(
                    """{"object":"hermes.api_server.capabilities","platform":"hermes-agent","model":"hermes-agent",
                       "auth":{"type":"bearer","required":true},"runtime":{"mode":"server_agent"},
                       "features":{"session_chat_streaming":true,"approval_events":true,"model_options":true,"jobs_admin":false,
                         "session_continuity_header":"X-Hermes-Session-Id","browser_extension_control":{"enabled":false}},
                       "endpoints":{"health":{"method":"GET","path":"/health"},"sessions":{"method":"GET","path":"/api/sessions"},
                         "session":{"method":"GET","path":"/api/sessions/{session_id}"},
                         "session_update":{"method":"PATCH","path":"/api/sessions/{session_id}"},
                         "session_delete":{"method":"DELETE","path":"/api/sessions/{session_id}"},
                         "session_messages":{"method":"GET","path":"/api/sessions/{session_id}/messages"},
                         "session_chat_stream":{"method":"POST","path":"/api/sessions/{session_id}/chat/stream"},
                         "session_create":{"method":"POST","path":"/api/sessions"},
                         "session_fork":{"method":"POST","path":"/api/sessions/{session_id}/fork"},
                         "runs":{"method":"POST","path":"/v1/runs"},
                         "run_status":{"method":"GET","path":"/v1/runs/{run_id}"},
                         "run_events":{"method":"GET","path":"/v1/runs/{run_id}/events"},
                         "run_stop":{"method":"POST","path":"/v1/runs/{run_id}/stop"},
                         "run_approval":{"method":"POST","path":"/v1/runs/{run_id}/approval"},
                         "model_options":{"method":"GET","path":"/api/model/options"},
                         "session_model_lock":{"method":"POST","path":"/api/sessions/{session_id}/model"},
                         "run_steer":{"method":"POST","path":"/v1/runs/{run_id}/steer"},
                         "skills":{"method":"GET","path":"/v1/skills"},
                         "toolsets":{"method":"GET","path":"/v1/toolsets"}}}""",
                )
            }
            path == "/api/sessions" && version == Version.Old -> MockResponse().setResponseCode(404).setBody("404: Not Found")
            path == "/api/sessions" && version == Version.Mid -> json(
                """{"object":"list","data":[
                   {"id":"m1","source":"cli","title":"旧版本会话","started_at":"2026-06-20T08:00:00Z","last_active":"1781942400.5","message_count":3,"preview":"hello"}
                ],"limit":100,"offset":0}""",
            )
            path == "/api/sessions" -> json(
                """{"object":"list","data":[
                   {"id":"s1","source":"api_server","title":"家庭网络","started_at":1760000000.0,"last_active":1760003600.25,"message_count":6,"preview":"DNS 改好了","pinned":false,"archived":${"s1" in archivedIds},"hidden":false,"has_system_prompt":false,"is_internal_child":false,"future_field":[1,2]},
                   {"id":"s2","source":"telegram","title":null,"started_at":1760000000,"last_active":1760001000,"preview":"周五开会提醒\n第二行","pinned":true},
                   {"id":"s3","source":"api_server","title":"被隐藏的","hidden":true},
                   {"id":"s4","source":"api_server","title":"已归档","archived":true},
                   {"id":"s5","source":"delegate","title":"子代理","is_internal_child":true},
                   {"id":"s6","source":"kanban","title":"看板来的"}${cronSessions.joinToString("") { ",$it" }}
                ],"limit":100,"offset":0,"has_more":false}""",
            )
            path.endsWith("/messages") && path != "/api/sessions/s1/messages" -> {
                val id = path.removePrefix("/api/sessions/").removeSuffix("/messages")
                val all = messages[id].orEmpty().toList()
                json("""{"object":"list","session_id":"$id","data":[${(messageWindow?.let { all.takeLast(it) } ?: all).joinToString(",")}]}""")
            }
            path == "/api/sessions/s1/messages" -> json(
                """{"object":"list","session_id":"s1","data":[
                   {"id":1,"role":"system","content":"You are Hermes"},
                   {"id":2,"role":"user","content":"网络老断","timestamp":1760000100.0},
                   {"id":3,"role":"assistant","content":"","reasoning":"SECRET-THINKING-1","tool_calls":[{"id":"t1","function":{"name":"terminal"}}],"timestamp":1760000110.0},
                   {"id":4,"role":"tool","content":"ping output...","tool_call_id":"t1","tool_name":"terminal"},
                   {"id":5,"role":"assistant","content":"DNS 改好了。","reasoning_content":"SECRET-THINKING-2","finish_reason":"stop","timestamp":1760000200.0},
                   {"id":6,"role":"assistant","content":"","display_kind":"hidden"},
                   {"id":7,"role":"user","content":[{"type":"text","text":"看这张图"},{"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}],"timestamp":1760000300},
                   {"id":8,"role":"assistant","content":[{"type":"output_text","text":"图里是路由器后台。"}],"timestamp":1760000400,"some_new_field":"x"}
                   ${messages["s1"].orEmpty().joinToString("") { ",$it" }}
                ],"pagination":{"limit":500,"offset":0,"order":"latest","returned":8}}""",
            )
            // 单个会话：新建的会话在第一轮之后有了 Hermes 自动起的标题
            path.matches(Regex("/api/sessions/new\\d+")) && request.method == "GET" -> {
                val id = path.removePrefix("/api/sessions/")
                val title = autoTitles[id]?.let { kotlinx.serialization.json.JsonPrimitive(it).toString() } ?: "null"
                json("""{"object":"hermes.session","session":{"id":"$id","source":"api_server","title":$title,"started_at":1760009000.0}}""")
            }
            path.startsWith("/api/sessions/") && request.method == "PATCH" ->
                if (failPatch) {
                    patchFailureGate?.await(5, TimeUnit.SECONDS)
                    error(500, "database is locked")
                } else {
                    val id = path.removePrefix("/api/sessions/")
                    val body = request.body.clone().readUtf8()
                    if ("\"archived\":true" in body) archivedIds += id
                    if ("\"archived\":false" in body) archivedIds -= id
                    json("""{"object":"hermes.session","session":{"id":"s1","title":"新标题"}}""")
                }
            path.startsWith("/api/sessions/") && request.method == "DELETE" -> json("""{"object":"hermes.session.deleted","id":"s1","deleted":true}""")
            else -> MockResponse().setResponseCode(404).setBody("404: Not Found")
        }
    }
}

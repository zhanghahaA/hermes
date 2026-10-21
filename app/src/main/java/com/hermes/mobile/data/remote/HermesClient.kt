package com.hermes.mobile.data.remote

import com.hermes.mobile.data.ConnectionTrace
import com.hermes.mobile.data.ConnectionTrace.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.serialization.json.put
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.EventListener
import okhttp3.Connection
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resumeWithException

/** 调用 Hermes 时可能出现的错误，已经翻译成用户能看懂的说法。 */
sealed class HermesError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unreachable(detail: String, cause: Throwable? = null) : HermesError(detail, cause)
    class Timeout(cause: Throwable? = null) : HermesError("连接超时，确认地址、端口和网络（例如 Tailscale 是否已连上）", cause)
    class Tls(detail: String, cause: Throwable? = null) : HermesError("HTTPS 证书有问题：$detail", cause)
    class NotHermes(detail: String = "这个地址有服务在运行，但不是 Hermes API Server") : HermesError(detail)
    class Unauthorized(val status: Int = 401) : HermesError("API Key 不对，或者服务器没有配置 API_SERVER_KEY")
    class NotSupported(val what: String) : HermesError("这台 Hermes 的版本不支持$what")
    class Conflict(val code: String?, detail: String) : HermesError(detail)
    class Server(val status: Int, val code: String?, detail: String, val retryAfterSeconds: Long? = null) : HermesError(detail)
    class BadUrl(detail: String) : HermesError(detail)
    /** SSH 隧道建不起来；[hostKeyChanged] 为 true 表示主机指纹和记录的不一致。 */
    class Ssh(detail: String, val hostKeyChanged: Boolean = false) : HermesError(detail)
}

/**
 * 一个连接对应一个客户端：负责拼地址、带 Key、把 HTTP/网络错误翻译成 [HermesError]。
 * [baseUrl] 形如 `http://100.64.0.2:8642`，[profile] 非空时所有请求加 `/p/<profile>` 前缀。
 */
class HermesClient(
    baseUrl: String,
    private val apiKey: String?,
    private val profile: String? = null,
    http: OkHttpClient = SharedHttp,
    private val diagnostics: ConnectionTrace? = null,
) {
    private val base: HttpUrl = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
        ?: throw HermesError.BadUrl("地址格式不对：$baseUrl")
    private val http = http

    /** 事件流用的客户端：服务器每 10 秒发一次心跳，30 秒没有任何数据就认为连接已死。 */
    private val streamHttp: OkHttpClient = http.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()

    internal suspend fun health(): HealthDto = try {
        get("/health", HealthDto.serializer(), auth = false, profileScoped = false).also {
            // 随便一个返回 JSON 的服务也能解析成功（字段全是可选的），至少要有 status 或 platform 才算 Hermes
            if (it.status == null && it.platform == null) throw HermesError.NotHermes()
            diagnostics?.record(Event.HealthParsed)
        }
    } catch (e: HermesError) {
        diagnostics?.record(Event.HealthFailed, error = e.cause ?: e)
        throw e
    }

    /** 只给 SSH 的 IPv4 本地入口使用；保留原客户端配置，但绝不让系统 HTTP 代理接管它。 */
    internal fun throughSshTunnel(trace: ConnectionTrace): HermesClient {
        require(base.host == "127.0.0.1" && base.scheme == "http")
        val tunnelHttp = http.newBuilder()
            .proxy(Proxy.NO_PROXY)
            .eventListenerFactory { call ->
                if (call.request().method != "GET" || call.request().url.encodedPath != "/health") EventListener.NONE
                else object : EventListener() {
                    override fun callStart(call: Call) = trace.record(Event.HealthStart, localPort = base.port, proxy = Proxy.Type.DIRECT)
                    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) =
                        trace.record(Event.HealthConnectStart, proxy = proxy.type())
                    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: okhttp3.Protocol?, ioe: IOException) =
                        trace.record(Event.HealthConnectFailed, error = ioe, proxy = proxy.type())
                    override fun connectionAcquired(call: Call, connection: Connection) =
                        trace.record(Event.HealthConnected, proxy = connection.route().proxy.type())
                    override fun requestHeadersStart(call: Call) = trace.record(Event.HealthHeadersStart)
                    override fun requestHeadersEnd(call: Call, request: Request) = trace.record(Event.HealthHeadersSent)
                    override fun responseHeadersStart(call: Call) = trace.record(Event.HealthResponseStart)
                    override fun responseHeadersEnd(call: Call, response: Response) = trace.record(Event.HealthResponse, status = response.code)
                    override fun responseBodyEnd(call: Call, byteCount: Long) = trace.record(Event.HealthBodyFinished, bytes = byteCount)
                    override fun callEnd(call: Call) = trace.record(Event.HealthCallFinished)
                    override fun callFailed(call: Call, ioe: IOException) = trace.record(Event.HealthFailed, error = ioe)
                }
            }.build()
        return HermesClient(base.toString(), apiKey, profile, tunnelHttp, trace)
    }

    internal suspend fun capabilities(): CapabilitiesDto = get("/v1/capabilities", CapabilitiesDto.serializer())

    internal suspend fun sessions(limit: Int = 100, offset: Int = 0): SessionListDto =
        get("/api/sessions?limit=$limit&offset=$offset", SessionListDto.serializer())

    internal suspend fun session(id: String): SessionDto? =
        get("/api/sessions/${id.encode()}", SessionEnvelopeDto.serializer()).session

    internal suspend fun messages(sessionId: String): MessageListDto =
        get("/api/sessions/${sessionId.encode()}/messages", MessageListDto.serializer())

    internal suspend fun patchSession(id: String, fields: JsonObject): SessionDto? =
        send("PATCH", "/api/sessions/${id.encode()}", fields, SessionEnvelopeDto.serializer()).session

    suspend fun deleteSession(id: String) {
        execute(request("/api/sessions/${id.encode()}").delete().build()).close()
    }

    internal suspend fun createSession(title: String?): SessionDto? =
        send("POST", "/api/sessions", patch { if (title != null) put("title", title) }, SessionEnvelopeDto.serializer()).session

    internal suspend fun forkSession(id: String): SessionDto? =
        send("POST", "/api/sessions/${id.encode()}/fork", JsonObject(emptyMap()), SessionEnvelopeDto.serializer()).session

    /** 提交一轮对话。[idempotencyKey] 相同的重试不会让 Hermes 重复执行。 */
    internal suspend fun startRun(
        sessionId: String,
        input: JsonElement,
        idempotencyKey: String,
        instructions: String? = null,
        model: String? = null,
        provider: String? = null,
        workspace: String? = null,
    ): RunAcceptedDto {
        val body = patch {
            put("input", input)
            put("session_id", sessionId)
            // 这一轮用哪个模型。/v1/runs 不读会话上锁定的模型，不带就是 Hermes 的默认模型
            if (model != null) put("model", model)
            if (provider != null) put("provider", provider)
            // 只对这一轮生效的补充系统提示（Hermes 叫 ephemeral system prompt），不进聊天记录
            if (instructions != null) put("instructions", instructions)
        }
        val request = request("/v1/runs")
            .header("Idempotency-Key", idempotencyKey)
            // 仅保留合法 ASCII 路径的兼容头；中文等完整目录由 JSON instructions 传递。
            // 不擅自 URL 编码：服务器没有约定对该头解码，编码后可能被当成另一个目录。
            .apply {
                workspace?.takeIf { path -> path.isNotEmpty() && path.all { it in ' '..'~' } }
                    ?.let { header("X-Hermes-Workspace", it) }
            }
            .post(body.toString().toRequestBody(JsonMedia))
            .build()
        return decode(execute(request), RunAcceptedDto.serializer())
    }

    internal suspend fun runStatus(runId: String): RunStatusDto = get("/v1/runs/${runId.encode()}", RunStatusDto.serializer())

    internal suspend fun respondApproval(runId: String, body: JsonObject) {
        execute(request("/v1/runs/${runId.encode()}/approval").post(body.toString().toRequestBody(JsonMedia)).build()).close()
    }

    internal suspend fun respondClarify(runId: String, body: JsonObject) {
        execute(request("/v1/runs/${runId.encode()}/clarify").post(body.toString().toRequestBody(JsonMedia)).build()).close()
    }

    /** 在 Hermes 回复的过程中插一句话（它会在下一步看到，不打断当前工作）。 */
    internal suspend fun steerRun(runId: String, text: String) {
        val body = kotlinx.serialization.json.buildJsonObject { put("input", kotlinx.serialization.json.JsonPrimitive(text)) }.toString()
        execute(request("/v1/runs/${runId.encode()}/steer").post(body.toRequestBody(JsonMedia)).build()).close()
    }

    internal suspend fun stopRun(runId: String) {
        execute(request("/v1/runs/${runId.encode()}/stop").post("{}".toRequestBody(JsonMedia)).build()).close()
    }

    /**
     * 订阅一轮对话的事件（SSE）。[afterSeq] 是已经处理过的最后一个序号，服务器会补发之后的事件。
     * 流正常结束（任务结束）时 Flow 完成；网络断开时抛 [HermesError]，由调用方决定是否重连。
     */
    internal fun runEvents(runId: String, afterSeq: Long): Flow<SseEvent> = flow {
        val request = request("/v1/runs/${runId.encode()}/events")
            .header("Accept", "text/event-stream")
            .apply { if (afterSeq >= 0) header("Last-Event-ID", afterSeq.toString()) }
            .get()
            .build()
        val response = execute(request, streamHttp)
        response.consume {
            val source = response.body.source()
            try {
                readSse(source) { emit(it) }
            } catch (e: SocketTimeoutException) {
                throw HermesError.Timeout(e)
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw HermesError.Unreachable(e.message ?: "连接断开", e)
            }
        }
    }.flowOn(Dispatchers.IO)

    // ---- 模型 ----

    internal suspend fun modelOptions(): ModelOptionsDto = get("/api/model/options", ModelOptionsDto.serializer())

    /** 把会话锁定到某个模型（之后这个会话的每一轮都用它）。 */
    internal suspend fun lockSessionModel(sessionId: String, model: String, provider: String) {
        val body = patch {
            put("model", model)
            put("provider", provider)
        }
        execute(request("/api/sessions/${sessionId.encode()}/model").post(body.toString().toRequestBody(JsonMedia)).build()).close()
    }

    // ---- 定时任务 ----

    /** `/health/detailed`：gateway 的运行状态、各平台连接情况、今天的用量。字段随版本变化，原样返回。 */
    internal suspend fun healthDetailed(): JsonObject = get("/health/detailed", JsonObject.serializer())

    internal suspend fun skills(): SkillListDto = get("/v1/skills", SkillListDto.serializer())

    internal suspend fun toolsets(): ToolsetListDto = get("/v1/toolsets", ToolsetListDto.serializer())

    internal suspend fun jobs(): JobListDto = get("/api/jobs?include_disabled=true", JobListDto.serializer())

    internal suspend fun createJob(fields: JsonObject): JobDto? = send("POST", "/api/jobs", fields, JobEnvelopeDto.serializer()).job

    internal suspend fun updateJob(id: String, fields: JsonObject): JobDto? =
        send("PATCH", "/api/jobs/${id.encode()}", fields, JobEnvelopeDto.serializer()).job

    internal suspend fun deleteJob(id: String) {
        execute(request("/api/jobs/${id.encode()}").delete().build()).close()
    }

    /** pause / resume / run */
    internal suspend fun jobAction(id: String, action: String): JobDto? =
        send("POST", "/api/jobs/${id.encode()}/$action", JsonObject(emptyMap()), JobEnvelopeDto.serializer()).job

    internal suspend fun job(id: String): JobDto? = get("/api/jobs/${id.encode()}", JobEnvelopeDto.serializer()).job

    /** 用一个必须鉴权、又在所有版本都有的接口验证 Key（旧版本没有 /v1/capabilities 时用）。 */
    suspend fun verifyKey() {
        execute(request("/v1/models").get().build()).close()
    }

    // ---- 底层 ----

    private fun url(path: String, profileScoped: Boolean): HttpUrl {
        // 支持反向代理下的路径前缀，例如 https://example.com/hermes
        val prefix = if (profileScoped && !profile.isNullOrBlank()) "/p/${profile.trim()}" else ""
        return (base.toString().trimEnd('/') + prefix + path).toHttpUrlOrNull()
            ?: throw HermesError.BadUrl("地址格式不对：$base$path")
    }

    private fun request(path: String, auth: Boolean = true, profileScoped: Boolean = true): Request.Builder =
        Request.Builder().url(url(path, profileScoped)).apply {
            header("Accept", "application/json")
            if (auth && !apiKey.isNullOrBlank()) header("Authorization", "Bearer ${apiKey.trim()}")
        }

    private suspend fun <T> get(
        path: String,
        strategy: DeserializationStrategy<T>,
        auth: Boolean = true,
        profileScoped: Boolean = true,
    ): T = decode(execute(request(path, auth, profileScoped).get().build()), strategy)

    private suspend fun <T> send(method: String, path: String, body: JsonElement, strategy: DeserializationStrategy<T>): T {
        val requestBody: RequestBody = body.toString().toRequestBody(JsonMedia)
        return decode(execute(request(path).method(method, requestBody).build()), strategy)
    }

    private suspend fun <T> decode(response: Response, strategy: DeserializationStrategy<T>): T = response.consume {
        val text = try {
            withContext(Dispatchers.IO) { response.body.string() }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw if (e is SocketTimeoutException) HermesError.Timeout(e)
                else HermesError.Unreachable("读取 HTTP 响应失败", e)
        }
        withContext(Dispatchers.Default) {
            try {
                HermesJson.decodeFromString(strategy, text)
            } catch (e: Exception) {
                throw HermesError.NotHermes()
            }
        }
    }

    private suspend fun execute(request: Request, client: OkHttpClient = http): Response {
        val response = try {
            client.newCall(request).await()
        } catch (e: SocketTimeoutException) {
            throw HermesError.Timeout(e)
        } catch (e: UnknownHostException) {
            throw HermesError.Unreachable("找不到这个地址（${request.url.host}），检查拼写或网络", e)
        } catch (e: ConnectException) {
            throw HermesError.Unreachable("连不上 ${request.url.host}:${request.url.port}，确认 Hermes gateway 在运行、端口正确", e)
        } catch (e: SSLException) {
            throw HermesError.Tls(e.message ?: "握手失败", e)
        } catch (e: IOException) {
            throw HermesError.Unreachable(e.message ?: "网络错误", e)
        }
        if (response.isSuccessful) return response
        val (code, message) = response.use { r ->
            val body = runCatching { r.body.string() }.getOrDefault("")
            if ("Headless backend (hermes serve)" in body || "hermes dashboard" in body) throw HermesError.NotHermes(WrongBackendHint)
            if (r.code == 404 && "Unknown or unconfigured profile" in body) throw HermesError.BadUrl(unknownProfileHint())
            val error = runCatching { HermesJson.decodeFromString(ErrorBodyDto.serializer(), body).error }.getOrNull()
            // 定时任务接口的错误是 {"error": "一句话"}
            val plain = if (error == null) runCatching {
                (HermesJson.parseToJsonElement(body) as? JsonObject)?.get("error")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            }.getOrNull() else null
            error?.code to (error?.message ?: plain ?: "HTTP ${r.code}")
        }
        // 端口上跑的是 hermes serve / hermes dashboard（桌面版和网页版的后端），不是 App 要连的 API Server
        throw when (response.code) {
            401, 403 -> HermesError.Unauthorized(response.code)
            404, 405 -> HermesError.NotSupported("这个功能")
            409 -> HermesError.Conflict(code, message)
            else -> HermesError.Server(response.code, code, message, response.header("Retry-After")?.trim()?.toLongOrNull())
        }
    }

    private fun unknownProfileHint() =
        "这台 Hermes 没有提供名为「${profile?.trim()}」的 Profile。确认名字拼写（hermes profile list 能看到），" +
            "并在它的 config.yaml 里开启 gateway.multiplex_profiles；只用默认 Profile 的话把这一栏留空"

    private fun String.encode(): String = java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    companion object {
        private val JsonMedia = "application/json".toMediaType()

        /** 新版 Hermes 的 `hermes serve` / `hermes dashboard` 也会占一个端口，但它们不是 App 用的 API Server。 */
        const val WrongBackendHint =
            "这个端口上运行的是 hermes serve / dashboard（桌面版和网页版用的后端），不是 API Server。" +
                "App 要连的是 hermes gateway 里的 API Server：在 ~/.hermes/.env 里设 API_SERVER_ENABLED=true，" +
                "启动 hermes gateway，并确认它的端口（默认 8642）没有被 hermes serve 占用"

        /** 所有连接共用一个连接池。普通请求 20 秒超时；流式聊天（阶段 2）会单独放宽。 */
        val SharedHttp: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

        fun patch(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            // 仅在内存的响应对象上关联 Call，不增加 HTTP Header，也不改变发送的请求。
            val owned = response.newBuilder().request(response.request.newBuilder()
                .tag(ResponseCall::class.java, ResponseCall(call)).build()).build()
            cont.resume(owned) { _, resource, _ -> call.cancel(); resource.close() }
        }
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

private class ResponseCall(val call: Call)

/** 用线程安全的 Call.cancel 解除阻塞，正文始终只由消费线程关闭，避免并发关闭 source。 */
private suspend fun <T> Response.consume(block: suspend () -> T): T = use {
    coroutineScope {
        val owner = currentCoroutineContext().job
        val closeOnCancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally {
                if (!owner.isActive) request.tag(ResponseCall::class.java)?.call?.cancel()
            }
        }
        try { block() } finally { closeOnCancellation.cancel() }
    }
}

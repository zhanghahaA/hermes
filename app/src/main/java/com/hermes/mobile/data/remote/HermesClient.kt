package com.hermes.mobile.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 调用 Hermes 时可能出现的错误，已经翻译成用户能看懂的说法。 */
sealed class HermesError(message: String) : Exception(message) {
    class Unreachable(detail: String) : HermesError(detail)
    class Timeout : HermesError("连接超时，确认地址、端口和网络（例如 Tailscale 是否已连上）")
    class Tls(detail: String) : HermesError("HTTPS 证书有问题：$detail")
    class NotHermes : HermesError("这个地址有服务在运行，但不是 Hermes API Server")
    class Unauthorized : HermesError("API Key 不对，或者服务器没有配置 API_SERVER_KEY")
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
) {
    private val base: HttpUrl = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
        ?: throw HermesError.BadUrl("地址格式不对：$baseUrl")
    private val http = http

    /** 事件流用的客户端：服务器每 10 秒发一次心跳，30 秒没有任何数据就认为连接已死。 */
    private val streamHttp: OkHttpClient = http.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()

    internal suspend fun health(): HealthDto =
        get("/health", HealthDto.serializer(), auth = false, profileScoped = false)

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
    internal suspend fun startRun(sessionId: String, input: String, idempotencyKey: String): RunAcceptedDto {
        val body = patch {
            put("input", input)
            put("session_id", sessionId)
        }
        val request = request("/v1/runs")
            .header("Idempotency-Key", idempotencyKey)
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
        response.use { r ->
            val source = r.body.source()
            try {
                readSse(source) { emit(it) }
            } catch (e: SocketTimeoutException) {
                throw HermesError.Timeout()
            } catch (e: IOException) {
                throw HermesError.Unreachable(e.message ?: "连接断开")
            }
        }
    }.flowOn(Dispatchers.IO)

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

    private suspend fun <T> decode(response: Response, strategy: DeserializationStrategy<T>): T = withContext(Dispatchers.Default) {
        response.use {
            val text = it.body.string()
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
            throw HermesError.Timeout()
        } catch (e: UnknownHostException) {
            throw HermesError.Unreachable("找不到这个地址（${request.url.host}），检查拼写或网络")
        } catch (e: ConnectException) {
            throw HermesError.Unreachable("连不上 ${request.url.host}:${request.url.port}，确认 Hermes gateway 在运行、端口正确")
        } catch (e: SSLException) {
            throw HermesError.Tls(e.message ?: "握手失败")
        } catch (e: IOException) {
            throw HermesError.Unreachable(e.message ?: "网络错误")
        }
        if (response.isSuccessful) return response
        val (code, message) = response.use { r ->
            val body = runCatching { r.body.string() }.getOrDefault("")
            val error = runCatching { HermesJson.decodeFromString(ErrorBodyDto.serializer(), body).error }.getOrNull()
            error?.code to (error?.message ?: "HTTP ${r.code}")
        }
        throw when (response.code) {
            401, 403 -> HermesError.Unauthorized()
            404, 405 -> HermesError.NotSupported("这个功能")
            409 -> HermesError.Conflict(code, message)
            else -> HermesError.Server(response.code, code, message, response.header("Retry-After")?.trim()?.toLongOrNull())
        }
    }

    private fun String.encode(): String = java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    companion object {
        private val JsonMedia = "application/json".toMediaType()

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
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

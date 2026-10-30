package com.hermes.mobile.data.remote

import com.hermes.mobile.data.ssh.RemoteStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** gateway 拒绝了一个请求（[reason] 是它给的原因，比如 `stale_generation`、`permission_denied`），或者通道断了。 */
class GatewayException(val reason: String, val data: JsonObject? = null, val disconnected: Boolean = false) : Exception(reason)

/**
 * 经 SSH 上的一条命令通道和服务器上的小桥（[GatewayBridge.Script]，群聊用的是 [HermesAdmin.GroupBridgeScript]）
 * 一行一个 JSON 地来回传：请求按 id 对上回复；gateway 主动推来的 `{"event": …}` 从 [events] 出去。
 *
 * 一条通道一直开着；断了下一次调用时重新开。SSH 底层断掉（息屏、换网络）时服务器发不回退出码，
 * 所以读线程结束、写失败、等回复超时都算死通道。每开一条新通道 [generation] 加一——gateway 的订阅
 * （`session.resume`）是跟着连接走的，换了通道要重新订阅。
 */
class GatewayChannel(private val open: suspend () -> RemoteStream) : java.io.Closeable {
    private val lock = Mutex()
    @Volatile private var stream: RemoteStream? = null
    @Volatile private var alive = false
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val ids = AtomicLong()

    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 4096)
    /** gateway 推来的事件（`params` 部分：`type`、`session_id`、`payload`、`seq`…）。 */
    val events: SharedFlow<JsonObject> = _events.asSharedFlow()

    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    private val _alive = MutableStateFlow(false)
    /** 当前有一条活着的通道。断了变 false（不会自己重连，下次调用才重开）。 */
    val connected: StateFlow<Boolean> = _alive.asStateFlow()

    /** 桥说 ready 时带的信息（Hermes 代码版本等）。 */
    @Volatile var hello: JsonObject? = null
        private set

    /** 确保有一条活着的通道（没有就开），返回桥的 ready 信息。 */
    suspend fun ensure(): JsonObject {
        channel()
        return hello ?: JsonObject(emptyMap())
    }

    private suspend fun channel(): RemoteStream = lock.withLock {
        stream?.takeIf { alive && it.exitCode == null }?.let { return it }
        stream?.let { runCatching { it.close() } }
        stream = null
        markDead()
        val s = try {
            open()
        } catch (e: GatewayException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw GatewayException(e.message ?: "连不上 Hermes gateway", disconnected = true)
        }
        val ready = CompletableDeferred<JsonObject>()
        val reader = Thread({
            try {
                s.output.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    val obj = runCatching { HermesJson.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return@forEachLine
                    when {
                        "ready" in obj -> ready.complete(obj)
                        "event" in obj -> (obj["event"] as? JsonObject)?.let { event -> runBlocking { _events.emit(event) } }
                        "closed" in obj -> Unit
                        else -> {
                            val id = (obj["id"] as? JsonPrimitive)?.longOrNull ?: return@forEachLine
                            pending.remove(id)?.complete(obj)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (stream === s) markDead()
                val gone = GatewayException("和 Hermes gateway 的连接断了", disconnected = true)
                ready.completeExceptionally(gone)
                pending.values.forEach { it.completeExceptionally(gone) }
                pending.clear()
            }
        }, "hermes-gateway")
        reader.isDaemon = true
        reader.start()
        val hi = try {
            withTimeout(60_000) { ready.await() }
        } catch (e: Exception) {
            runCatching { s.close() }
            val why = if (s.exitCode != null) s.errorText().lines().lastOrNull { it.isNotBlank() } else null
            throw GatewayException(why?.take(300) ?: "连不上 Hermes gateway", disconnected = true)
        }
        if ((hi["ready"] as? JsonPrimitive)?.booleanOrNull != true) {
            runCatching { s.close() }
            throw GatewayException((hi["error"] as? JsonPrimitive)?.contentOrNull ?: "连不上 Hermes gateway", disconnected = true)
        }
        hello = hi
        stream = s
        alive = true
        _alive.value = true
        _generation.value = _generation.value + 1
        s
    }

    private fun markDead() {
        alive = false
        _alive.value = false
    }

    /** 这条通道不能再用了：下次调用重新开。 */
    private fun drop(s: RemoteStream) {
        if (stream === s) markDead()
        runCatching { s.close() }
    }

    /**
     * 调一个 gateway 方法，返回 `result`。[rpcTimeoutSeconds] 是桥那边等 gateway 回答的时间（null 用 Hermes 客户端的默认）；
     * [timeoutMillis] 是手机这边总共等多久。
     */
    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap()), timeoutMillis: Long = 45_000, rpcTimeoutSeconds: Double? = null): JsonElement =
        send(buildJsonObject {
            put("method", method)
            put("params", params)
            if (rpcTimeoutSeconds != null) put("timeout", rpcTimeoutSeconds)
        }, timeoutMillis)

    /** 让桥自己用 Hermes 的 Python 函数办的事（见 [GatewayBridge]）。 */
    suspend fun local(name: String, params: JsonObject = JsonObject(emptyMap()), timeoutMillis: Long = 45_000): JsonElement =
        send(buildJsonObject {
            put("local", name)
            put("params", params)
        }, timeoutMillis)

    private suspend fun send(body: JsonObject, timeoutMillis: Long): JsonElement {
        val s = channel()
        val id = ids.incrementAndGet()
        val answer = CompletableDeferred<JsonObject>()
        pending[id] = answer
        val line = JsonObject(body + ("id" to JsonPrimitive(id))).toString() + "\n"
        try {
            withContext(Dispatchers.IO) {
                synchronized(s.input) {
                    s.input.write(line.toByteArray(Charsets.UTF_8))
                    s.input.flush()
                }
            }
        } catch (e: Exception) {
            pending.remove(id)
            drop(s)
            throw GatewayException("和 Hermes gateway 的连接断了", disconnected = true)
        }
        val reply = try {
            withTimeout(timeoutMillis) { answer.await() }
        } catch (e: TimeoutCancellationException) {
            // 一直没回音多半是底层连接已经半死不活了，换一条
            drop(s)
            throw GatewayException("Hermes gateway ${timeoutMillis / 1000} 秒没有回应", disconnected = true)
        } finally {
            pending.remove(id)
        }
        (reply["error"] as? JsonObject)?.let { error ->
            val data = error["data"] as? JsonObject
            val reason = (data?.get("reason") as? JsonPrimitive)?.contentOrNull
                ?: (error["reason"] as? JsonPrimitive)?.contentOrNull ?: "gateway 出错了"
            throw GatewayException(reason, data)
        }
        return reply["result"] ?: JsonNull
    }

    override fun close() {
        stream?.let { runCatching { it.close() } }
        stream = null
        markDead()
    }

    companion object {
        fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
        fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    }
}

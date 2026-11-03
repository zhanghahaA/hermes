package com.hermes.mobile.data.providers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

@Serializable
data class ModelProvider(
    val id: String, val name: String, val authenticated: Boolean = false,
    val apiKey: Boolean = false, val flow: String = "", val models: List<String> = emptyList(),
)

@Serializable
data class ModelSettingsSnapshot(
    val providers: List<ModelProvider> = emptyList(), val provider: String = "", val model: String = "",
    val baseUrl: String = "", val hasKey: Boolean = false, val home: String = "", val apiMode: String = "chat_completions",
)

@Serializable
data class AccountLogin(
    val id: String, val provider: String, val status: String = "starting", val code: String = "",
    val url: String = "", val expiresAt: Long = 0,
) {
    override fun toString() = "AccountLogin(provider=$provider, status=$status, details=[已隐藏])"
}

@Serializable
data class ModelSettingsRequest(
    val action: String, val provider: String = "", val model: String = "", val apiKey: String? = null,
    val baseUrl: String = "", val sessionId: String = "", val apiMode: String = "chat_completions",
    /** 自定义 / 本地端点：保存时带上读到的模型清单。 */
    val models: List<String> = emptyList(),
) {
    override fun toString() = "ModelSettingsRequest(action=$action, contents=[已隐藏])"

    fun validate() {
        require(action in setOf("inspect", "save", "login", "poll", "cancel", "models", "enable_clarify")) { "未知模型配置操作" }
        if (action in setOf("save", "login", "models")) require(Regex("[a-zA-Z0-9][a-zA-Z0-9:_-]{0,79}").matches(provider)) { "请选择提供方" }
        if (action == "save") {
            require(model.isNotBlank() && model.length <= 200 && model.none { it.isISOControl() }) { "请填写模型名称" }
            require(!looksLikeAddress(model)) { AddressAsModel }
        }
        if (action == "save" || action == "models" && provider in setOf("custom", "local")) {
            require(apiKey == null || apiKey.length <= 4096 && apiKey.none { it.isISOControl() }) { "模型密钥格式无效" }
            require(apiMode in setOf("chat_completions", "codex_responses", "anthropic_messages")) { "接口格式无效" }
            if (baseUrl.isNotBlank()) require(endpointAllowed(baseUrl)) { EndpointRule }
            if (provider in setOf("custom", "local")) require(baseUrl.isNotBlank()) { "请填写模型端点" }
            require(models.size <= 500 && models.all { it.length <= 200 && it.none(Char::isISOControl) }) { "模型清单无效" }
        }
        if (action in setOf("poll", "cancel")) require(Regex("[a-f0-9]{32}").matches(sessionId)) { "授权会话无效" }
    }

    companion object {
        const val EndpointRule = "云端端点使用 HTTPS；本机和局域网地址（如 192.168.x.x）可以用 HTTP"
        const val AddressAsModel = "模型名称不能是网址。要用自己的服务地址，请把提供方换成「自定义端点」，在「模型接口地址」里填写。"

        /** 模型名称里填了服务地址（固定提供方没有地址栏时常见），保存后 Hermes 会拿它当模型名去请求。 */
        fun looksLikeAddress(model: String): Boolean = Regex("(?i)^\\s*[a-z][a-z0-9+.-]*://").containsMatchIn(model)

        /** 和 settings.py 的 endpoint() 一致：https 都行；http 只给回环、私有网段、链路本地、Tailscale 和 .local。 */
        fun endpointAllowed(value: String): Boolean {
            val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
            val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: return false
            if (uri.userInfo != null || uri.fragment != null || uri.query != null) return false
            return uri.scheme == "https" || uri.scheme == "http" && privateHost(host)
        }

        fun privateHost(host: String): Boolean {
            val name = host.lowercase()
            if (name == "localhost" || name.endsWith(".local")) return true
            val v4 = name.split(".").takeIf { it.size == 4 }?.map { it.toIntOrNull() ?: return false }
            if (v4 != null) {
                if (v4.any { it !in 0..255 }) return false
                val (a, b) = v4
                return a == 127 || a == 10 || a == 172 && b in 16..31 || a == 192 && b == 168 || a == 169 && b == 254 || a == 100 && b in 64..127
            }
            if (!name.contains(":") || !name.all { it.isDigit() || it in 'a'..'f' || it == ':' }) return false
            return name == "::1" || name.startsWith("fc") || name.startsWith("fd") || name.startsWith("fe8") || name.startsWith("fe9") ||
                name.startsWith("fea") || name.startsWith("feb")
        }
    }
}

/** 实时读取自定义 / 本地端点模型清单的结果（只有分类，不含服务商原始错误）。 */
@Serializable
data class EndpointProbe(val status: String = "", val models: List<String> = emptyList(), val baseUrl: String = "", val code: Int = 0)

@Serializable
data class ModelSettingsResponse(
    val ok: Boolean = true, val error: String = "", val snapshot: ModelSettingsSnapshot? = null,
    val login: AccountLogin? = null, val probe: EndpointProbe? = null,
)

data class ModelSettingsState(
    val snapshot: ModelSettingsSnapshot? = null, val login: AccountLogin? = null,
    val busy: Boolean = false, val message: String = "正在读取当前连接的模型设置…", val failed: Boolean = false,
    /** 最近一次实时读取端点的结果；地址探测出带 /v1 的版本时界面用它更新地址。 */
    val probe: EndpointProbe? = null,
    /** 读取端点时给出的说明；界面据此把它显示在读取按钮旁边。 */
    val probeMessage: String = "",
)

/** 一个页面绑定一个连接，凭据仅通过受保护的命令输入发送；界面状态不保留提交的密钥。 */
class ModelSettingsManager(
    private val scope: CoroutineScope, private val program: String,
    private val execute: suspend (String, String) -> String,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(ModelSettingsState())
    val state = mutable.asStateFlow()

    fun submit(request: ModelSettingsRequest, onSuccess: () -> Unit = {}): Job = scope.launch {
        if (!mutex.tryLock()) return@launch
        mutable.update { it.copy(busy = true, failed = false) }
        try {
            request.validate()
            val raw = withTimeout(90_000) { execute(program, json.encodeToString(ModelSettingsRequest.serializer(), request)) }
            require(raw.length <= 512 * 1024) { "模型配置回包过大" }
            val line = raw.lineSequence().singleOrNull { it.startsWith(Receipt) } ?: error("没有收到模型配置结果，请检查 Hermes 是否安装完整")
            val response = try { json.decodeFromString(ModelSettingsResponse.serializer(), line.removePrefix(Receipt)) }
                catch (_: Exception) { error("模型配置结果格式不完整") }
            check(response.ok) { response.error.ifBlank { "模型设置未完成" } }
            if (request.action in setOf("inspect", "models", "save")) require(response.snapshot != null) { "模型配置回包不完整" }
            if (request.action in setOf("login", "poll", "cancel")) require(response.login != null) { "授权回包不完整" }
            response.login?.let { login ->
                require(Regex("[a-f0-9]{32}").matches(login.id) && login.provider.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9:_-]{0,79}"))) { "授权会话格式无效" }
                require(login.status in setOf("starting", "pending", "approved", "expired", "cancelled", "error") && login.code.length <= 200 && login.url.length <= 4096) { "授权回包不完整" }
                if (request.action == "login") require(login.provider == request.provider) { "授权提供方不匹配" }
                if (request.action in setOf("poll", "cancel")) require(login.id == request.sessionId) { "授权会话不匹配" }
                if (login.url.isNotBlank()) {
                    val uri = URI(login.url)
                    require(uri.scheme == "https" && uri.host != null && uri.userInfo == null) { "授权页面必须使用 HTTPS" }
                }
            }
            val probe = response.probe?.takeIf { request.action == "models" }?.let { found ->
                require(found.status in ProbeStatuses && found.models.size <= 500 && found.models.all { m -> m.length <= 200 && m.none(Char::isISOControl) }) { "模型配置回包不完整" }
                // 回来的地址也要满足同样的规则，否则不采用。
                found.copy(baseUrl = found.baseUrl.takeIf { url -> url.isNotBlank() && ModelSettingsRequest.endpointAllowed(url) } ?: request.baseUrl)
            }
            mutable.update { it.copy(snapshot = response.snapshot ?: it.snapshot, login = response.login ?: it.login,
                probe = if (request.action == "models") probe else it.probe,
                message = when (request.action) {
                    "enable_clarify" -> "已启用手机互动选项。请新建会话尝试；若旧进程尚未读取配置，请在空闲时重启 Hermes。"
                    "save" -> "模型设置已保存。新会话将使用此配置；已锁定模型的旧会话需单独切换。"
                    "login", "poll" -> when (response.login?.status) {
                        "approved" -> "账号授权成功，选择模型后点保存即可。"
                        "error" -> "账号授权未完成，请重试或使用 Hermes 的原有登录命令。"
                        "expired" -> "授权码已过期，请重新登录。"
                        "cancelled" -> "已取消授权。"
                        else -> "请在浏览器完成授权，返回后检查登录结果。"
                    }
                    "cancel" -> "已请求取消授权。"
                    "models" -> if (probe != null) probeMessage(probe, request.baseUrl, request.apiKey != null)
                        else if (response.snapshot?.providers?.any { row -> row.id == request.provider && row.models.isNotEmpty() } == true)
                        "已读取模型列表，可以选择或手动填写。" else "未返回可选模型清单，可直接填写模型名称。"
                    else -> "已读取当前连接的模型设置。"
                }, failed = response.login?.status in setOf("error", "expired") || probe != null && probe.models.isEmpty()).let { next ->
                    next.copy(probeMessage = if (probe != null) next.message else "") } }
            onSuccess()
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            mutable.update { it.copy(failed = true, message = "等待模型配置结果超时，请刷新确认，不要重复提交。") }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // 服务商错误可带凭据；仅显示我们自己产生的可预期说明，禁止回显原始回包或异常。
            val known = e.message.orEmpty()
            mutable.update { it.copy(failed = true, message = if (isSafeError(known)) known else "模型设置未完成，请检查连接、授权和 Hermes 版本后重试。") }
        } finally { mutable.update { it.copy(busy = false) }; mutex.unlock() }
    }

    companion object {
        const val Receipt = "HMMODELS"
        private val ProbeStatuses = setOf("ok", "unreachable", "rejected", "http", "empty", "transport")

        internal fun probeMessage(probe: EndpointProbe, typed: String, keyTyped: Boolean): String {
            val moved = probe.baseUrl.trimEnd('/') != typed.trim().trimEnd('/')
            return when (probe.status) {
                "ok" -> "读到 ${probe.models.size} 个模型，点「从模型列表选择」。" + if (moved) "地址已改成实际能用的 ${probe.baseUrl}。" else ""
                "transport" -> "读到 ${probe.models.size} 个模型，但这个地址不支持所选的接口格式，请换一个接口格式再保存。"
                "unreachable" -> "连不上这个地址：请确认地址和端口，模型服务在运行，并且运行 Hermes 的设备能访问它（本机安装时就是这台手机）。"
                "rejected" -> if (keyTyped) "端点拒绝了这个 API 密钥，请检查密钥。" else "端点要求 API 密钥，请先填写密钥再读取。"
                "http" -> "端点返回 HTTP ${probe.code.takeIf { it in 100..599 } ?: "错误"}，没有给出模型清单。请检查地址（一般以 /v1 结尾），或直接填写模型名称。"
                else -> "连上了，但这个地址没有返回模型清单。请检查地址（一般以 /v1 结尾），或直接填写模型名称。"
            }
        }
        private val SafeErrors = setOf("请选择提供方", "请填写模型名称", ModelSettingsRequest.AddressAsModel, "模型密钥格式无效","请填写模型端点", "接口格式无效",
            ModelSettingsRequest.EndpointRule, "模型清单无效", "没有收到模型配置结果，请检查 Hermes 是否安装完整",
            "当前 Hermes 不支持浏览器授权，请升级 Hermes 或使用下方登录命令。",
            "当前 Hermes 缺少模型配置接口或其依赖，请检查 Hermes 安装与版本；这不是浏览器授权错误。", "请先在设置中为该连接配置 SSH，并连接成功后重试。",
            "模型设置未完成：请检查模型、密钥和端点，修改前的配置备份已保留。", "请先安装 Hermes，再配置模型提供方。",
            "请先完成 Termux 执行授权", "模型配置文件无法读取，请先修复，已有配置未覆盖。")
        private val MissingModule = Regex("（缺少：[A-Za-z0-9_.]{1,100}）")

        /** 已知说明原样显示；缺依赖的那条允许带上 Python 模块名（只有模块名，不含路径和回包）。 */
        internal fun isSafeError(message: String): Boolean = message in SafeErrors ||
            SafeErrors.any { message.startsWith(it) && MissingModule.matches(message.removePrefix(it)) }
    }
}

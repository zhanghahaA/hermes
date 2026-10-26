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
) {
    override fun toString() = "ModelSettingsRequest(action=$action, contents=[已隐藏])"

    fun validate() {
        require(action in setOf("inspect", "save", "login", "poll", "cancel", "models")) { "未知模型配置操作" }
        if (action in setOf("save", "login", "models")) require(Regex("[a-zA-Z0-9][a-zA-Z0-9:_-]{0,79}").matches(provider)) { "请选择提供方" }
        if (action == "save") {
            require(model.isNotBlank() && model.length <= 200 && model.none { it.isISOControl() }) { "请填写模型名称" }
            require(apiKey == null || apiKey.length <= 4096 && apiKey.none { it.isISOControl() }) { "模型密钥格式无效" }
            require(apiMode in setOf("chat_completions", "codex_responses", "anthropic_messages")) { "接口格式无效" }
            if (baseUrl.isNotBlank()) {
                val uri = runCatching { URI(baseUrl) }.getOrNull()
                require(uri != null && uri.host != null && uri.userInfo == null && uri.fragment == null && uri.query == null &&
                    (uri.scheme == "https" || uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "::1", "[::1]"))) {
                    "云端端点使用 HTTPS；Hermes 所在机器的本地模型可使用本机 HTTP 地址"
                }
            }
            if (provider in setOf("custom", "local")) require(baseUrl.isNotBlank()) { "请填写模型端点" }
        }
        if (action in setOf("poll", "cancel")) require(Regex("[a-f0-9]{32}").matches(sessionId)) { "授权会话无效" }
    }
}

@Serializable
data class ModelSettingsResponse(
    val ok: Boolean = true, val error: String = "", val snapshot: ModelSettingsSnapshot? = null,
    val login: AccountLogin? = null,
)

data class ModelSettingsState(
    val snapshot: ModelSettingsSnapshot? = null, val login: AccountLogin? = null,
    val busy: Boolean = false, val message: String = "正在读取当前连接的模型设置…", val failed: Boolean = false,
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
            mutable.update { it.copy(snapshot = response.snapshot ?: it.snapshot, login = response.login ?: it.login,
                message = when (request.action) {
                    "save" -> "模型设置已保存。新会话将使用此配置；已锁定模型的旧会话需单独切换。"
                    "login", "poll" -> when (response.login?.status) {
                        "approved" -> "账号授权成功，选择模型后点保存即可。"
                        "error" -> "账号授权未完成，请重试或使用 Hermes 的原有登录命令。"
                        "expired" -> "授权码已过期，请重新登录。"
                        "cancelled" -> "已取消授权。"
                        else -> "请在浏览器完成授权，返回后检查登录结果。"
                    }
                    "cancel" -> "已请求取消授权。"
                    "models" -> if (response.snapshot?.providers?.any { row -> row.id == request.provider && row.models.isNotEmpty() } == true)
                        "已读取模型列表，可以选择或手动填写。" else "未返回可选模型清单，可直接填写模型名称。"
                    else -> "已读取当前连接的模型设置。"
                }, failed = response.login?.status in setOf("error", "expired")) }
            onSuccess()
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            mutable.update { it.copy(failed = true, message = "等待模型配置结果超时，请刷新确认，不要重复提交。") }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // 服务商错误可带凭据；仅显示我们自己产生的可预期说明，禁止回显原始回包或异常。
            val known = e.message.orEmpty()
            mutable.update { it.copy(failed = true, message = if (known in SafeErrors) known else "模型设置未完成，请检查连接、授权和 Hermes 版本后重试。") }
        } finally { mutable.update { it.copy(busy = false) }; mutex.unlock() }
    }

    companion object {
        const val Receipt = "HMMODELS"
        private val SafeErrors = setOf("请选择提供方", "请填写模型名称", "模型密钥格式无效", "请填写模型端点", "接口格式无效",
            "云端端点使用 HTTPS；Hermes 所在机器的本地模型可使用本机 HTTP 地址", "没有收到模型配置结果，请检查 Hermes 是否安装完整",
            "当前 Hermes 不支持浏览器授权，请升级 Hermes 或使用下方登录命令。", "请先在设置中为该连接配置 SSH，并连接成功后重试。",
            "模型设置未完成：请检查模型、密钥和端点，修改前的配置备份已保留。", "请先安装 Hermes，再配置模型提供方。",
            "请先完成 Termux 执行授权", "模型配置文件无法读取，请先修复，已有配置未覆盖。")
    }
}

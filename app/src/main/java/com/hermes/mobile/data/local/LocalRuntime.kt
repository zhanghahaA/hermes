package com.hermes.mobile.data.local

import kotlinx.serialization.Serializable

/** 界面只依赖这些能力；以后内置运行环境时不必继续使用 Termux 的 Intent 或路径。 */
interface LocalRuntimeBackend {
    val name: String
    val externalApps: Boolean
    val hermesInstallWarning: String? get() = null
    val initializationCommand: String? get() = null
    fun environment(): RuntimeEnvironment
    suspend fun modelSettings(program: String, input: String): String = error("此运行环境暂不支持模型设置")
    suspend fun execute(request: RuntimeRequest, progress: (String) -> Unit): RuntimeResponse
}

data class RuntimeEnvironment(
    val installed: Boolean = false,
    val compatible: Boolean = false,
    val permission: Boolean = false,
    val arm64: Boolean = false,
    val freeBytes: Long = 0,
    val viewerInstalled: Boolean = false,
    val version: String = "",
) {
    val blockers: List<String> get() = buildList {
        if (!installed) add("请安装并打开一次 Termux")
        else if (!compatible) add("请使用 GitHub 或 F-Droid 的 Termux 0.118 或更新版本")
        if (!arm64) add("本机运行包需要 arm64 手机")
        if (!permission) add("请授权在 Termux 中执行命令")
    }
    val enoughSpace: Boolean get() = freeBytes >= 2L * 1024 * 1024 * 1024
}

@Serializable
enum class RuntimeAction {
    Inspect, Prepare, InstallHermes, UpgradeHermes, ConfigureHermes,
    StartHermes, StopHermes, RestartHermes, HermesLogs, RemoveHermes,
    InstallService, InstallProxy, StartService, StopService, ServiceLogs, RemoveService,
    InstallBrowser, StartBrowser, StopBrowser,
}

@Serializable
enum class ReleaseChannel(val id: String) { Stable("stable"), Canary("canary") }

@Serializable
data class LocalModelSetup(val model: String, val apiKey: String, val baseUrl: String = "https://openrouter.ai/api/v1") {
    init {
        require(model.isNotBlank() && model.length <= 200) { "请填写模型名称" }
        require(apiKey.isNotBlank() && apiKey.length <= 4096 && apiKey.none { it.isISOControl() }) { "请填写有效的模型密钥" }
        val uri = runCatching { java.net.URI(baseUrl) }.getOrNull()
        require(uri != null && uri.host != null && uri.userInfo == null && uri.fragment == null &&
            (uri.scheme == "https" || uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "::1"))) {
            "模型地址需要 HTTPS，本机模型可使用本机 HTTP 地址"
        }
    }
    override fun toString() = "LocalModelSetup(model=$model, credentials=[已隐藏])"
}

@Serializable
data class PythonService(val id: String, val name: String, val source: String, val requirements: List<String> = emptyList()) {
    init {
        require(Id.matches(id)) { "服务标识使用 2–40 位小写字母、数字和短横线，且以字母开头" }
        require(name.isNotBlank() && name.length <= 80) { "请填写服务名称" }
        require(source.isNotBlank() && source.toByteArray().size <= 64 * 1024) { "导入的 Python 程序不能超过 64 KB" }
        require(requirements.size <= 20 && requirements.all { Requirement.matches(it) }) { "依赖请逐行填写包名，可指定 == 版本；不接受命令、网址或安装选项" }
    }
    override fun toString() = "PythonService(id=$id, source=[已隐藏])"
    companion object {
        val Id = Regex("[a-z][a-z0-9-]{1,39}")
        private val Requirement = Regex("[A-Za-z0-9][A-Za-z0-9._-]*(?:==[A-Za-z0-9][A-Za-z0-9.+_-]*)?")
    }
}

@Serializable
data class ReverseProxyService(val id: String, val name: String, val port: Int, val target: String) {
    init {
        require(PythonService.Id.matches(id) && name.isNotBlank() && name.length <= 80) { "请填写有效的代理标识和名称" }
        require(port in 1024..65535 && port !in setOf(8642, 9222)) { "代理端口需在 1024–65535 之间，并避开 Hermes 和浏览器端口" }
        val uri = runCatching { java.net.URI(target) }.getOrNull()
        require(uri != null && uri.host != null && uri.userInfo == null && uri.fragment == null && uri.query == null &&
            uri.path.orEmpty() in setOf("", "/") && uri.scheme in setOf("http", "https")) { "代理目标填写完整 HTTP(S) 地址，不包含密码、路径或查询参数" }
    }
}

@Serializable
data class RuntimeRequest(
    val action: RuntimeAction,
    val channel: ReleaseChannel = ReleaseChannel.Stable,
    val model: LocalModelSetup? = null,
    val service: PythonService? = null,
    val proxy: ReverseProxyService? = null,
    val serviceId: String? = null,
    val acceptExperimental: Boolean = false,
) {
    override fun toString() = "RuntimeRequest(action=$action, contents=[已隐藏])"
}

@Serializable
data class ManagedService(val id: String, val name: String, val running: Boolean = false)

@Serializable
data class RuntimeSnapshot(
    val toolsReady: Boolean = false,
    val hermesInstalled: Boolean = false,
    val configured: Boolean = false,
    val hermesRunning: Boolean = false,
    val browserInstalled: Boolean = false,
    val browserRunning: Boolean = false,
    val services: List<ManagedService> = emptyList(),
)

/** 凭据只交给连接存储，不放进界面状态或通用诊断。 */
class RuntimeResponse(val snapshot: RuntimeSnapshot, val output: String = "", internal val connectionKey: String? = null) {
    override fun toString() = "RuntimeResponse(snapshot=$snapshot, output=[已隐藏], credentials=[已隐藏])"
}

interface LocalConnectionAccess {
    fun save(apiKey: String)
    suspend fun verifyAndActivate()
    suspend fun awaitActivated() = Unit
}

/** Android Intent 桥是外部后端的私有实现；业务和界面不直接发任意 shell 命令。 */
interface TermuxTransport {
    fun environment(): RuntimeEnvironment
    suspend fun run(script: String, input: String = ""): TermuxCommandResult
    fun openViewer()
}

class TermuxCommandResult(val exitCode: Int, val stdout: String = "", val stderr: String = "") {
    override fun toString() = "TermuxCommandResult(exitCode=$exitCode, output=[已隐藏])"
}

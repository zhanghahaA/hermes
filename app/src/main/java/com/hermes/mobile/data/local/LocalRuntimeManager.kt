package com.hermes.mobile.data.local

import com.hermes.mobile.data.store.DiagnosticRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

data class LocalRuntimeState(
    val environment: RuntimeEnvironment,
    val snapshot: RuntimeSnapshot = RuntimeSnapshot(),
    val checked: Boolean = false,
    val busy: Boolean = false,
    val message: String = "先检查运行环境",
    val output: String = "",
    val failed: Boolean = false,
    val probe: RuntimeProbe = RuntimeProbe.NotChecked,
    val issue: RuntimeIssue? = null,
    val resultCode: Int? = null,
)

/** 随 App 进程保留状态。更换为内置后端时，界面、操作串行化和连接存储都可以保留。 */
class LocalRuntimeManager(
    private val scope: CoroutineScope,
    val backend: LocalRuntimeBackend,
    private val connection: LocalConnectionAccess,
    private val timeoutMillis: Long = 30 * 60_000L,
) {
    private val mutable = MutableStateFlow(LocalRuntimeState(backend.environment()))
    val state = mutable.asStateFlow()
    private val mutex = Mutex()

    fun refreshEnvironment() {
        val environment = backend.environment()
        mutable.update {
            val changed = it.environment.copy(freeBytes = environment.freeBytes) != environment
            it.copy(environment = environment, checked = if (changed) false else it.checked,
                probe = if (changed) RuntimeProbe.NotChecked else it.probe)
        }
    }

    /** 只在明确选择 App 管理的本机连接时自动恢复，不影响远程连接。 */
    suspend fun ensureRunning() = mutex.withLock {
        refreshEnvironment()
        check(state.value.environment.blockers.isEmpty()) { state.value.environment.blockers.joinToString("\n") }
        withTimeout(45_000) {
            execute(RuntimeRequest(RuntimeAction.Inspect))
            check(state.value.snapshot.configured) { "请先配置本机连接" }
            if (!state.value.snapshot.hermesRunning) execute(RuntimeRequest(RuntimeAction.StartHermes))
        }
    }

    private fun operation(label: String, block: suspend () -> Unit): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        if (!mutex.tryLock()) return@launch
        mutable.update { it.copy(busy = true, message = label, output = "", failed = false, issue = null, resultCode = null) }
        try {
            refreshEnvironment()
            check(state.value.environment.blockers.isEmpty()) { state.value.environment.blockers.joinToString("\n") }
            withTimeout(timeoutMillis) { block() }
        } catch (_: TimeoutCancellationException) {
            mutable.update {
                val inspecting = it.probe == RuntimeProbe.Checking
                it.copy(checked = false, failed = true, probe = if (inspecting) RuntimeProbe.TimedOut else it.probe,
                    issue = if (inspecting) RuntimeIssue.InspectionTimeout else RuntimeIssue.CommandPending,
                    message = if (inspecting) RuntimeIssue.InspectionTimeout.explanation
                        else "等待超时。Termux 中的任务可能仍在执行，请先检查状态，不要重复安装。")
            }
        } catch (e: CancellationException) {
            mutable.update { it.copy(checked = false, failed = true, probe = if (it.probe == RuntimeProbe.Checking) RuntimeProbe.NotChecked else it.probe,
                message = "等待已结束，请重新检查后台任务状态") }
            throw e
        } catch (e: Exception) {
            mutable.update { it.copy(failed = true, checked = false, probe = if (it.probe == RuntimeProbe.Checking) RuntimeProbe.Failed else it.probe,
                issue = (e as? RuntimeCommandException)?.issue,
                resultCode = (e as? RuntimeCommandException)?.resultCode,
                message = DiagnosticRedactor.redact(e.message ?: "操作未完成")) }
        } finally {
            mutable.update { it.copy(busy = false) }
            mutex.unlock()
        }
    }

    private suspend fun execute(request: RuntimeRequest) {
        val response = backend.execute(request) { step -> mutable.update { it.copy(message = step) } }
        response.connectionKey?.let(connection::save)
        mutable.update { it.copy(snapshot = response.snapshot, checked = true, output = response.output, probe = RuntimeProbe.Passed) }
    }

    fun inspect() = operation("正在检查运行环境") {
        mutable.update { it.copy(probe = RuntimeProbe.Checking) }
        withTimeout(25_000) { execute(RuntimeRequest(RuntimeAction.Inspect)) }
        mutable.update { it.copy(probe = RuntimeProbe.Passed) }
        mutable.update { it.copy(message = "环境检查完成") }
    }

    fun perform(request: RuntimeRequest) = operation(label(request.action)) {
        if (request.action in setOf(RuntimeAction.Prepare, RuntimeAction.RepairEnvironment, RuntimeAction.InstallHermes, RuntimeAction.InstallBrowser, RuntimeAction.InstallService, RuntimeAction.InstallProxy)) {
            check(state.value.environment.enoughSpace) { "请至少预留 2 GB 内部存储空间" }
        }
        if (request.action == RuntimeAction.InstallBrowser && !state.value.snapshot.toolsReady) execute(RuntimeRequest(RuntimeAction.Prepare))
        execute(request)
        mutable.update { it.copy(message = "${label(request.action)}完成") }
    }

    /** 安装不再依赖模型 Key；先完成安装与手机接口配置，再由设置页配置提供方。 */
    fun install(channel: ReleaseChannel = ReleaseChannel.Stable, repairEnvironment: Boolean = false) = operation("正在安装本机 Hermes") {
        if (repairEnvironment) {
            check(state.value.environment.enoughSpace) { "请至少预留 2 GB 内部存储空间" }
            execute(RuntimeRequest(RuntimeAction.RepairEnvironment))
        }
        mutable.update { it.copy(probe = RuntimeProbe.Checking) }
        withTimeout(25_000) { execute(RuntimeRequest(RuntimeAction.Inspect)) }
        if (!state.value.snapshot.toolsReady || !state.value.snapshot.hermesInstalled) {
            check(state.value.environment.enoughSpace) { "请至少预留 2 GB 内部存储空间" }
        }
        if (!state.value.snapshot.toolsReady) execute(RuntimeRequest(RuntimeAction.Prepare))
        if (!state.value.snapshot.hermesInstalled) {
            execute(RuntimeRequest(RuntimeAction.InstallHermes, channel = channel, acceptExperimental = true))
        }
        execute(RuntimeRequest(RuntimeAction.ConfigureHermes))
        mutable.update { it.copy(message = "Hermes 已安装。下一步配置模型提供方，再启动并连接。") }
    }

    suspend fun modelSettings(program: String, input: String): String = mutex.withLock {
        refreshEnvironment()
        withTimeout(90_000) { backend.modelSettings(program, input) }
    }

    fun deploy(model: LocalModelSetup, channel: ReleaseChannel, acceptExperimental: Boolean) = operation("正在准备本机 Hermes") {
        backend.hermesInstallWarning?.let { check(acceptExperimental) { it } }
        check(state.value.environment.enoughSpace) { "请至少预留 2 GB 内部存储空间" }
        execute(RuntimeRequest(RuntimeAction.InstallHermes, channel = channel, acceptExperimental = true))
        execute(RuntimeRequest(RuntimeAction.Prepare))
        execute(RuntimeRequest(RuntimeAction.ConfigureHermes, model = model))
        execute(RuntimeRequest(RuntimeAction.StartHermes))
        connection.verifyAndActivate()
        mutable.update { it.copy(message = "本机连接检查通过，已切换到「这台手机」") }
    }

    /** 切换后的连接恢复可能再次调用 ensureRunning；必须在操作锁释放后等待，避免互相等待。 */
    suspend fun awaitConnected() = connection.awaitActivated()

    fun connect() = operation("正在检查本机连接") {
        execute(RuntimeRequest(RuntimeAction.StartHermes))
        connection.verifyAndActivate()
        mutable.update { it.copy(message = "本机连接检查通过，已切换到「这台手机」") }
    }

    companion object {
        const val ConnectionId = "hermes-local-managed"
        fun label(action: RuntimeAction): String = when (action) {
            RuntimeAction.Inspect -> "检查环境"
            RuntimeAction.Prepare -> "准备 Python 服务环境"
            RuntimeAction.RepairEnvironment -> "修复 Termux 运行环境"
            RuntimeAction.InstallHermes -> "安装本机 Hermes"
            RuntimeAction.UpgradeHermes -> "升级本机 Hermes"
            RuntimeAction.ConfigureHermes -> "配置本机连接"
            RuntimeAction.StartHermes -> "启动 Hermes"
            RuntimeAction.StopHermes -> "停止 Hermes"
            RuntimeAction.RestartHermes -> "重启 Hermes"
            RuntimeAction.HermesLogs -> "查看 Hermes 日志"
            RuntimeAction.RemoveHermes -> "卸载 Hermes 软件包"
            RuntimeAction.InstallService -> "导入 Python 服务"
            RuntimeAction.InstallProxy -> "添加本机反向代理"
            RuntimeAction.StartService -> "启动服务"
            RuntimeAction.StopService -> "停止服务"
            RuntimeAction.ServiceLogs -> "查看服务日志"
            RuntimeAction.RemoveService -> "移除服务程序"
            RuntimeAction.InstallBrowser -> "安装 X11 与 Chromium 配套包"
            RuntimeAction.StartBrowser -> "打开浏览器"
            RuntimeAction.StopBrowser -> "停止浏览器"
        }
    }
}

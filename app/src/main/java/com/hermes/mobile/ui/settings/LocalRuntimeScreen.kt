package com.hermes.mobile.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.local.*
import com.hermes.mobile.platform.rememberRuntimeControls
import com.hermes.mobile.platform.rememberTextFilePicker
import kotlinx.coroutines.launch

@Composable
fun LocalRuntimeScreen(manager: LocalRuntimeManager, onBack: () -> Unit, onConfigureModels: () -> Unit = {}, onConnected: () -> Unit = {}) {
    val state by manager.state.collectAsState()
    val controls = rememberRuntimeControls {
        manager.refreshEnvironment()
        if (!manager.state.value.busy && manager.state.value.environment.blockers.isEmpty()) manager.inspect()
    }
    val scope = rememberCoroutineScope()
    var confirmation by remember { mutableStateOf<RuntimeRequest?>(null) }
    var editing by remember { mutableStateOf<RuntimeAction?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var openingChat by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(false) }
    var channel by remember { mutableStateOf(ReleaseChannel.Stable) }
    val copy = com.hermes.mobile.platform.rememberTextCopier()
    val showMessage = com.hermes.mobile.ui.LocalShowMessage.current
    val env = state.environment
    val snapshot = state.snapshot
    val ready = env.blockers.isEmpty() && !state.busy && !openingChat
    val installReady = ready && env.enoughSpace
    val step = state.setupStep()
    fun install() {
        scope.launch {
            manager.install(channel).join()
            if (!manager.state.value.failed && manager.state.value.snapshot.configured) onConfigureModels()
        }
    }
    fun connect() {
        openingChat = true
        scope.launch {
            try {
                manager.connect().join()
                if (!manager.state.value.failed) { manager.awaitConnected(); onConnected() }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { error = "本机连接尚未就绪，请检查模型设置后重试。" }
            finally { openingChat = false }
        }
    }
    LaunchedEffect(manager) { manager.refreshEnvironment() }
    LaunchedEffect(env.installed, env.permission, env.compatible, env.commandServiceAvailable, env.permissionRegistered, env.arm64) {
        if (env.blockers.isEmpty() && !state.checked && !state.busy) manager.inspect()
    }
    Scaffold(topBar = { TopAppBar(title = { Text("在手机上安装 Hermes") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("准备环境 → 安装 Hermes → 选择模型", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (openingChat) "正在连接" else step.title, style = MaterialTheme.typography.headlineSmall)
                if (step.detail.isNotBlank()) Text(step.detail)
                if (state.failed && step != RuntimeSetupStep.Initialize) Text(state.message, color = MaterialTheme.colorScheme.error)
                else if (state.busy || openingChat) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (openingChat) "正在启动并连接本机 Hermes…" else state.message)
                }
                Button(modifier = Modifier.fillMaxWidth().testTag("runtime-primary"),
                    enabled = !state.busy && !openingChat && step != RuntimeSetupStep.Unsupported,
                    onClick = {
                        when (step) {
                            RuntimeSetupStep.Download -> controls.downloadTermux()
                            RuntimeSetupStep.PermissionSettings -> controls.permissionSettings()
                            RuntimeSetupStep.Permission -> controls.requestPermission()
                            RuntimeSetupStep.Initialize -> {
                                manager.backend.initializationCommand?.let { copy(it, false); showMessage("已复制，请在 Termux 中粘贴并按回车。") }
                                controls.openTermux()
                            }
                            RuntimeSetupStep.Check -> manager.inspect()
                            RuntimeSetupStep.Storage -> controls.storageSettings()
                            RuntimeSetupStep.Install, RuntimeSetupStep.Configure -> install()
                            RuntimeSetupStep.Connect -> connect()
                            else -> Unit
                        }
                    }) { Text(if (openingChat) "正在连接…" else step.button) }
                if (step in setOf(RuntimeSetupStep.Install, RuntimeSetupStep.Configure)) {
                    Text("点击即开始安装和配置。需要联网，保留已有配置与会话；模型账号在安装后设置。", style = MaterialTheme.typography.bodySmall)
                }
                if (step == RuntimeSetupStep.Permission || step == RuntimeSetupStep.PermissionSettings) {
                    TextButton(onClick = controls.permissionSettings) { Text("没有弹窗？打开系统权限设置") }
                }
                if (step == RuntimeSetupStep.Connect) {
                    TextButton(onClick = onConfigureModels, enabled = ready) { Text("选择或更换模型") }
                }
            } }
            TextButton(onClick = { diagnostics = !diagnostics }) { Text(if (diagnostics) "收起检查详情" else "查看检查详情") }
            if (diagnostics) {
                RuntimeChecksPanel(state, controls, manager.backend.initializationCommand)
                OutlinedButton(onClick = { manager.inspect() }, enabled = ready) { Text("重新检查环境") }
                if (state.output.isNotBlank()) SelectionContainer { Text(state.output, style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级功能" else "高级功能") }
            if (advanced) {
                Text("安装通道", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReleaseChannel.entries.forEach { option ->
                        FilterChip(channel == option, { channel = option }, enabled = !state.busy,
                            label = { Text(if (option == ReleaseChannel.Stable) "稳定版（默认）" else "预览版") })
                    }
                }
                if (manager.backend.externalApps) {
                    OutlinedButton(onClick = controls.openTermux) { Text("打开 Termux") }
                    OutlinedButton(onClick = controls.batterySettings) { Text("后台与电池设置") }
                }
                if (snapshot.hermesInstalled) {
                    RuntimeButton(manager, RuntimeAction.StopHermes, ready && snapshot.toolsReady)
                    RuntimeButton(manager, RuntimeAction.RestartHermes, ready && snapshot.configured)
                    RuntimeButton(manager, RuntimeAction.HermesLogs, ready && snapshot.toolsReady)
                    OutlinedButton(onClick = { confirmation = RuntimeRequest(RuntimeAction.UpgradeHermes, acceptExperimental = true) }, enabled = ready) { Text("升级 Hermes") }
                    TextButton(onClick = { confirmation = RuntimeRequest(RuntimeAction.RemoveHermes) }, enabled = ready && snapshot.toolsReady) { Text("卸载 Hermes 软件包") }
                }
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("可选 · 自己的服务", style = MaterialTheme.typography.titleMedium)
                    Text("导入你信任的 Python 程序，或添加只监听本机的反向代理。服务共享 Termux 的数据访问权限，虚拟环境不是安全沙箱。", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { editing = RuntimeAction.InstallService }, enabled = installReady && snapshot.toolsReady) { Text("导入 Python 服务") }
                    OutlinedButton(onClick = { editing = RuntimeAction.InstallProxy }, enabled = installReady && snapshot.toolsReady) { Text("添加本机反向代理") }
                    snapshot.services.forEach { service ->
                        HorizontalDivider()
                        Text(service.name + if (service.running) " · 运行中" else " · 已停止")
                        RuntimeButton(manager, RuntimeAction.StartService, ready, service.id)
                        RuntimeButton(manager, RuntimeAction.StopService, ready, service.id)
                        RuntimeButton(manager, RuntimeAction.ServiceLogs, ready, service.id)
                        TextButton(onClick = { confirmation = RuntimeRequest(RuntimeAction.RemoveService, serviceId = service.id) }, enabled = ready) { Text("移除 ${service.name} 的程序") }
                    }
                } }
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("可选 · 桌面浏览器", style = MaterialTheme.typography.titleMedium)
                    Text("使用独立的 Termux:X11 显示窗口；只有打开浏览器时才启动。当前是浏览器启动与显示，尚不包含 Hermes 自动操作网页。", style = MaterialTheme.typography.bodySmall)
                    if (!env.viewerInstalled && manager.backend.externalApps) OutlinedButton(onClick = controls.downloadViewer) { Text("下载 Termux:X11") }
                    OutlinedButton(onClick = { confirmation = RuntimeRequest(RuntimeAction.InstallBrowser) }, enabled = installReady) { Text("安装浏览器配套包") }
                    RuntimeButton(manager, RuntimeAction.StartBrowser, ready && snapshot.toolsReady && snapshot.browserInstalled && env.viewerInstalled)
                    RuntimeButton(manager, RuntimeAction.StopBrowser, ready && snapshot.toolsReady)
                } }
            }
            Text("本机运行仍可能使用云端模型。首次安装 Termux 和系统授权需要你确认。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    confirmation?.let { request -> AlertDialog(onDismissRequest = { confirmation = null },
        title = { Text(LocalRuntimeManager.label(request.action)) },
        text = { Text(when (request.action) {
            RuntimeAction.RemoveService -> "停止并移除选定服务的程序与元数据，保留它生成的数据。"
            RuntimeAction.RemoveHermes -> "卸载 Hermes 软件包并停止 App 管理的 Hermes 进程，保留配置、会话和记忆。其它软件若使用同一 Hermes 软件包也会受影响。"
            RuntimeAction.UpgradeHermes -> manager.backend.hermesInstallWarning.orEmpty() + " 将通过签名软件源升级软件包，不自动重启当前进程。"
            RuntimeAction.InstallBrowser -> "将安装 Python 服务环境和 Chromium／X11 配套包，需要网络和较多空间；独立 X11 应用仍需在系统中确认安装。"
            else -> "将安装 Python 运行包以管理本机服务，已有 Hermes 配置与连接不会删除。"
        }) },
        confirmButton = { TextButton(onClick = { manager.perform(request); confirmation = null }) { Text("确认并继续") } },
        dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } }) }
    editing?.let { action -> RuntimeEditor(action, manager, onClose = { editing = null }, onError = { error = it }) }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("请检查输入") }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } }) }
}

@Composable
private fun RuntimeButton(manager: LocalRuntimeManager, action: RuntimeAction, enabled: Boolean, id: String? = null) {
    OutlinedButton(onClick = { manager.perform(RuntimeRequest(action, serviceId = id)) }, enabled = enabled) { Text(LocalRuntimeManager.label(action)) }
}

@Composable
private fun RuntimeEditor(action: RuntimeAction, manager: LocalRuntimeManager, onClose: () -> Unit, onError: (String) -> Unit) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://") }
    var port by remember { mutableStateOf("8080") }
    var requirements by remember { mutableStateOf("") }
    var source by remember { mutableStateOf<String?>(null) }
    val picker = rememberTextFilePicker { if (it != null) source = it }
    AlertDialog(onDismissRequest = onClose, title = { Text(LocalRuntimeManager.label(action)) }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(id, { id = it }, label = { Text("服务标识，如 my-service") }, singleLine = true)
            OutlinedTextField(name, { name = it }, label = { Text("服务名称") }, singleLine = true)
            if (action == RuntimeAction.InstallService) {
                OutlinedButton(onClick = picker) { Text(if (source == null) "选择 .py 文件（最多 64 KB）" else "已选择程序，点击重新选择") }
                OutlinedTextField(requirements, { requirements = it }, label = { Text("依赖包，每行一个，可留空") })
                Text("安装依赖和运行程序会执行代码，请只导入信任的来源。")
            } else {
                OutlinedTextField(port, { port = it }, label = { Text("本机端口") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("代理目标 HTTP(S) 地址") }, singleLine = true)
                Text("反向代理仅监听本机；本版不开放公网入口。")
            }
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching {
            when (action) {
                RuntimeAction.InstallService -> manager.perform(RuntimeRequest(action, service = PythonService(id, name,
                    source ?: error("请先选择 Python 程序"), requirements.lines().map(String::trim).filter(String::isNotEmpty))))
                RuntimeAction.InstallProxy -> manager.perform(RuntimeRequest(action, proxy = ReverseProxyService(id, name, port.toIntOrNull() ?: 0, url)))
                else -> error("不支持的服务操作")
            }
        }.onSuccess { onClose() }.onFailure { onError(it.message ?: "输入无效") }
    }) { Text("确认并继续") } }, dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
}

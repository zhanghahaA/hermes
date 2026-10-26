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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.local.*
import com.hermes.mobile.platform.rememberRuntimeControls
import com.hermes.mobile.platform.rememberTextFilePicker
import com.hermes.mobile.ui.components.SetupCommandCard
import kotlinx.coroutines.launch

@Composable
fun LocalRuntimeScreen(manager: LocalRuntimeManager, onBack: () -> Unit, onConfigureModels: () -> Unit = {}, onConnected: () -> Unit = {}) {
    val state by manager.state.collectAsState()
    val controls = rememberRuntimeControls(manager::refreshEnvironment)
    val scope = rememberCoroutineScope()
    var confirmation by remember { mutableStateOf<RuntimeRequest?>(null) }
    var editing by remember { mutableStateOf<RuntimeAction?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var openingChat by remember { mutableStateOf(false) }
    val env = state.environment
    val snapshot = state.snapshot
    val ready = env.blockers.isEmpty() && !state.busy && !openingChat
    LaunchedEffect(manager) { manager.refreshEnvironment() }
    LaunchedEffect(env.installed, env.permission) {
        if (env.blockers.isEmpty() && !state.checked && !state.busy) manager.inspect()
    }
    Scaffold(topBar = { TopAppBar(title = { Text("本机运行与服务") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("在手机上运行 Hermes 和自己的小服务。远程聊天不需要开启本机环境。", style = MaterialTheme.typography.bodyMedium)
            ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1 · 准备运行环境", style = MaterialTheme.typography.titleMedium)
                Text(manager.backend.name, style = MaterialTheme.typography.bodySmall)
                Text(if (env.installed) "已安装 ${env.version}" else "尚未安装")
                env.blockers.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!env.enoughSpace) Text("安装前请至少预留 2 GB 空间", color = MaterialTheme.colorScheme.error)
                if (manager.backend.externalApps) {
                    if (!env.installed) OutlinedButton(onClick = controls.downloadTermux) { Text("下载 Termux") }
                    else {
                        OutlinedButton(onClick = controls.openTermux) { Text("打开 Termux") }
                        if (!env.permission) {
                            OutlinedButton(onClick = controls.requestPermission) { Text("授予执行权限") }
                            Text("没有弹出授权窗口？打开系统权限设置 → 权限 → 其他权限，允许在 Termux 中执行命令，然后返回此页。", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = controls.permissionSettings) { Text("打开系统权限设置") }
                        }
                        manager.backend.initializationCommand?.let { command ->
                            SetupCommandCard("首次只做一次：允许 Hermes 管理服务", "打开 Termux 后，在输入区域长按 → 粘贴 → 按回车。",
                                command, copyLabel = "复制并打开 Termux", next = "完成后返回本页授权执行权限，App 会自动检查；然后点一键安装。", onCopied = controls.openTermux)
                        }
                    }
                    TextButton(onClick = controls.batterySettings) { Text("查看后台与电池设置") }
                }
                Button(onClick = {
                    manager.refreshEnvironment()
                    if (manager.state.value.environment.installed && !manager.state.value.environment.permission) controls.requestPermission()
                    else manager.inspect()
                }, enabled = !state.busy) { Text("检查环境") }
                if (!snapshot.toolsReady) OutlinedButton(onClick = { confirmation = RuntimeRequest(RuntimeAction.Prepare) }, enabled = ready) { Text("检查并准备环境") }
                else Text("环境已就绪，继续下面的部署。", color = MaterialTheme.colorScheme.primary)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.message, color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2 · 部署并连接 Hermes", style = MaterialTheme.typography.titleMedium)
                Text(if (!state.checked) "尚未检查" else if (!snapshot.hermesInstalled) "尚未安装" else if (snapshot.hermesRunning) "进程运行中" else "已安装，进程未运行")
                manager.backend.hermesInstallWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = { editing = RuntimeAction.InstallHermes }, enabled = ready) { Text("一键安装 Hermes") }
                if (snapshot.hermesInstalled) {
                    OutlinedButton(onClick = onConfigureModels, enabled = ready && snapshot.toolsReady) { Text("配置模型与提供方") }
                    Text("安装完成后，在 App 内配置账号或模型密钥，再点「启动并连接聊天」。", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { openingChat = true; scope.launch {
                        try {
                            manager.connect().join()
                            if (!manager.state.value.failed) { manager.awaitConnected(); onConnected() }
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { error = "本机连接尚未就绪，请检查运行状态后重试。" }
                        finally { openingChat = false }
                    } }, enabled = ready && snapshot.configured) { Text("启动并连接聊天") }
                    RuntimeButton(manager, RuntimeAction.StartHermes, ready && snapshot.configured)
                    RuntimeButton(manager, RuntimeAction.StopHermes, ready && snapshot.toolsReady)
                    RuntimeButton(manager, RuntimeAction.RestartHermes, ready && snapshot.configured)
                    RuntimeButton(manager, RuntimeAction.HermesLogs, ready && snapshot.toolsReady)
                    OutlinedButton(onClick = { confirmation = RuntimeRequest(RuntimeAction.UpgradeHermes, acceptExperimental = true) }, enabled = ready) { Text("升级 Hermes") }
                    TextButton(onClick = { confirmation = RuntimeRequest(RuntimeAction.RemoveHermes) }, enabled = ready && snapshot.toolsReady) { Text("卸载 Hermes 软件包") }
                }
            } }
            ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("可选 · 自己的服务", style = MaterialTheme.typography.titleMedium)
                Text("导入你信任的 Python 程序，或添加只监听本机的反向代理。服务共享 Termux 的数据访问权限，虚拟环境不是安全沙箱。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { editing = RuntimeAction.InstallService }, enabled = ready && snapshot.toolsReady) { Text("导入 Python 服务") }
                OutlinedButton(onClick = { editing = RuntimeAction.InstallProxy }, enabled = ready && snapshot.toolsReady) { Text("添加本机反向代理") }
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
                OutlinedButton(onClick = { confirmation = RuntimeRequest(RuntimeAction.InstallBrowser) }, enabled = ready) { Text("安装浏览器配套包") }
                RuntimeButton(manager, RuntimeAction.StartBrowser, ready && snapshot.toolsReady && snapshot.browserInstalled && env.viewerInstalled)
                RuntimeButton(manager, RuntimeAction.StopBrowser, ready && snapshot.toolsReady)
            } }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.message, color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (state.output.isNotBlank()) SelectionContainer { Text(state.output, style = MaterialTheme.typography.bodySmall) }
            Text("本机运行仍可能使用云端模型。系统可能回收后台进程；重新检查后恢复，不保证永久保活。", style = MaterialTheme.typography.bodySmall)
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
    editing?.let { action -> RuntimeEditor(action, manager, onClose = { editing = null }, onError = { error = it }, onInstall = { channel ->
        scope.launch {
            manager.install(channel).join()
            if (!manager.state.value.failed && manager.state.value.snapshot.hermesInstalled) onConfigureModels()
        }
    }) }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("请检查输入") }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } }) }
}

@Composable
private fun RuntimeButton(manager: LocalRuntimeManager, action: RuntimeAction, enabled: Boolean, id: String? = null) {
    OutlinedButton(onClick = { manager.perform(RuntimeRequest(action, serviceId = id)) }, enabled = enabled) { Text(LocalRuntimeManager.label(action)) }
}

@Composable
private fun RuntimeEditor(action: RuntimeAction, manager: LocalRuntimeManager, onClose: () -> Unit, onError: (String) -> Unit, onInstall: (ReleaseChannel) -> Unit) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(if (action == RuntimeAction.InstallProxy) "https://" else "https://openrouter.ai/api/v1") }
    var port by remember { mutableStateOf("8080") }
    var requirements by remember { mutableStateOf("") }
    var source by remember { mutableStateOf<String?>(null) }
    var channel by remember { mutableStateOf(ReleaseChannel.Stable) }
    val picker = rememberTextFilePicker { if (it != null) source = it }
    val service = action in setOf(RuntimeAction.InstallService, RuntimeAction.InstallProxy)
    AlertDialog(onDismissRequest = onClose, title = { Text(LocalRuntimeManager.label(action)) }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (service) {
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
            } else {
                Text("自动准备依赖、安装 Hermes 并创建手机连接接口。安装后再配置提供方，无需现在填写模型密钥。已有可用 Hermes 会直接识别，配置与会话保留。")
                Row { ReleaseChannel.entries.forEach { option -> FilterChip(channel == option, { channel = option }, label = { Text(option.id) }) } }
                Text(manager.backend.hermesInstallWarning.orEmpty())
            }
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching {
            when (action) {
                RuntimeAction.InstallService -> manager.perform(RuntimeRequest(action, service = PythonService(id, name,
                    source ?: error("请先选择 Python 程序"), requirements.lines().map(String::trim).filter(String::isNotEmpty))))
                RuntimeAction.InstallProxy -> manager.perform(RuntimeRequest(action, proxy = ReverseProxyService(id, name, port.toIntOrNull() ?: 0, url)))
                RuntimeAction.InstallHermes -> onInstall(channel)
                else -> manager.perform(RuntimeRequest(action, model = if (model.isBlank() && key.isBlank()) null else LocalModelSetup(model, key, url)))
            }
        }.onSuccess { key = ""; onClose() }.onFailure { onError(it.message ?: "输入无效") }
    }) { Text("确认并继续") } }, dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
}

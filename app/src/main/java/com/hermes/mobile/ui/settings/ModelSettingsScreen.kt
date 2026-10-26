package com.hermes.mobile.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.local.LocalRuntimeManager
import com.hermes.mobile.data.providers.*
import com.hermes.mobile.platform.rememberModelSettingsProgram
import com.hermes.mobile.platform.rememberRuntimeControls
import com.hermes.mobile.platform.rememberTextReader
import com.hermes.mobile.ui.components.SetupCommandCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ModelSettingsScreen(repository: HermesRepository, connectionId: String?, onBack: () -> Unit, onEditConnection: (String) -> Unit = {}, onConnected: () -> Unit = onBack) {
    val connections by repository.connections.collectAsState()
    val active by repository.activeConnectionId.collectAsState()
    val connectionState by repository.connectionState.collectAsState()
    val activeBot by repository.activeBot.collectAsState()
    val target = connections.firstOrNull { it.id == connectionId }
    val local = connectionId == LocalRuntimeManager.ConnectionId
    val scope = rememberCoroutineScope()
    val program = rememberModelSettingsProgram()
    // 捕获当前连接的管理通道；切换后旧页面不能将配置写入另一个连接。
    val admin = remember(connectionId, active, connectionState, activeBot) { if (active == connectionId && !local) repository.admin() else null }
    val manager = remember(connectionId, admin, program) {
        ModelSettingsManager(scope, program) { script, input ->
            if (local) (repository.localRuntime ?: error("请先安装 Hermes，再配置模型提供方。")).modelSettings(script, input)
            else {
                check(repository.activeConnectionId.value == connectionId && admin != null) { "请先在设置中为该连接配置 SSH，并连接成功后重试。" }
                admin.modelSettings(script, input)
            }
        }
    }
    var connecting by remember(manager) { mutableStateOf(false) }
    var connectMessage by remember(manager) { mutableStateOf("") }
    fun connectLocal() {
        if (connecting) return
        connecting = true
        connectMessage = "正在启动并检查本机连接…"
        scope.launch {
            try {
                val runtime = repository.localRuntime ?: error("本机环境尚未就绪")
                runtime.connect().join()
                connectMessage = runtime.state.value.message
                if (!runtime.state.value.failed) { runtime.awaitConnected(); onConnected() }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { connectMessage = "本机连接尚未就绪，请重试，或返回运行中心查看状态。" }
            finally { connecting = false }
        }
    }
    ModelSettingsContent(manager, (target?.name ?: if (local) "这台手机" else "未选择连接") + if (!local && activeBot != null) " · " + activeBot else "", onBack,
        ready = local || admin != null,
        onEditConnection = { connectionId?.let(onEditConnection) },
        onModelSaved = {
            if (repository.activeConnectionId.value == connectionId) {
                repository.setDefaultModel("")
                repository.refreshModels()
            }
            if (local) connectLocal()
        },
        saveAndConnect = local, externalBusy = connecting, externalMessage = connectMessage,
        onRetryConnect = if (local) ::connectLocal else null,
        openTermux = if (local) rememberRuntimeControls({}).openTermux else null)
}

@Composable
fun ModelSettingsContent(manager: ModelSettingsManager, connectionName: String, onBack: () -> Unit,
                         ready: Boolean = true, onEditConnection: () -> Unit = {}, onModelSaved: () -> Unit = {}, saveAndConnect: Boolean = false,
                         externalBusy: Boolean = false, externalMessage: String = "", onRetryConnect: (() -> Unit)? = null, openTermux: (() -> Unit)? = null) {
    val state by manager.state.collectAsState()
    val snapshot = state.snapshot
    val busy = state.busy || externalBusy
    val uriHandler = LocalUriHandler.current
    val read = rememberTextReader()
    var provider by remember(manager) { mutableStateOf("") }
    var model by remember(manager) { mutableStateOf("") }
    // 未提交的密钥只留当前页面内存，不进入系统保存状态、日志或剪贴板。
    var key by remember(manager) { mutableStateOf("") }
    var url by remember(manager) { mutableStateOf("") }
    var apiMode by remember(manager) { mutableStateOf("chat_completions") }
    var chooseProvider by remember(manager) { mutableStateOf(false) }
    var confirmSave by remember(manager) { mutableStateOf(false) }
    var confirmLogin by remember(manager) { mutableStateOf(false) }
    var chooseModel by remember(manager) { mutableStateOf(false) }
    LaunchedEffect(manager, ready) { if (ready) manager.submit(ModelSettingsRequest("inspect")) }
    LaunchedEffect(snapshot?.provider, snapshot?.model) {
        snapshot?.let { if (provider.isBlank()) { provider = it.provider; model = it.model; url = it.baseUrl; apiMode = it.apiMode } }
    }
    val selection = snapshot?.providers?.firstOrNull { it.id == provider }
    val login = state.login
    LaunchedEffect(manager, login?.id, login?.status) {
        if (login != null && login.status in setOf("starting", "pending")) {
            while (true) {
                delay(2500)
                manager.submit(ModelSettingsRequest("poll", sessionId = login.id)).join()
                if (manager.state.value.login?.status !in setOf("starting", "pending")) break
            }
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("模型与提供方") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("当前连接：$connectionName", style = MaterialTheme.typography.titleMedium)
            Text("配置写入这个连接的 Hermes，密钥不在不同连接间自动复制。同一 Hermes 配置目录仍共享设置，外部 CLI 登录也可能在同一机器上共享账号。", style = MaterialTheme.typography.bodySmall)
            if (!ready) {
                Text("远程模型设置需要 SSH 管理权限。请先为此连接配置 SSH 并连接成功。")
                Button(onClick = onEditConnection) { Text("配置当前连接") }
            } else {
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1 · 选择模型提供方", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(enabled = !busy && snapshot != null, onClick = { chooseProvider = true }) {
                        Text(selection?.name ?: "选择提供方、订阅账号或本地模型")
                    }
                    if (selection != null) {
                        Text(if (selection.authenticated) "已连接此提供方" else if (provider == "local") "不需要云端 API 密钥" else "尚未连接此提供方")
                        if (selection.flow == "device_code") {
                            Button(enabled = !busy && login?.status !in setOf("starting", "pending"), onClick = { confirmLogin = true }) { Text("连接订阅账号") }
                        }
                        if (selection.flow == "external") {
                            Text("此提供方沿用 Hermes 的命令行登录。复制后在运行 Hermes 的机器执行，完成后返回刷新。", style = MaterialTheme.typography.bodySmall)
                            val home = snapshot.home
                            val prefix = if (home.isNotBlank()) "HERMES_HOME=" + shellQuote(home) + " " else ""
                            val command = prefix + if (provider == "claude-code") "claude setup-token" else "hermes auth add " + shellQuote(provider) + if (provider == "openrouter") " --type oauth" else ""
                            SetupCommandCard("登录账号", if (openTermux != null) "在 Termux 中粘贴执行一次，按提示授权。" else "在当前连接的服务器终端粘贴执行。",
                                command, copyLabel = if (openTermux != null) "复制并打开 Termux" else "复制登录命令", next = "授权完成后返回本页，点刷新配置。", onCopied = openTermux ?: {})
                        }
                        if (selection.apiKey || provider in setOf("custom", "local")) {
                            OutlinedTextField(key, { key = it }, label = { Text(if (provider == "local") "本地服务访问密钥（可选）" else "模型服务 API 密钥") },
                                placeholder = { Text(if (snapshot.hasKey && snapshot.provider == provider) "留空保留已有密钥" else "粘贴服务商提供的密钥") },
                                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                            TextButton(onClick = { read()?.let { key = it.trim() } }) { Text("粘贴 API 密钥") }
                        }
                    }
                } }
                if (login != null) ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("账号授权", style = MaterialTheme.typography.titleMedium)
                    Text(when (login.status) {
                        "starting" -> "正在请求授权码…"
                        "pending" -> "在浏览器完成登录，返回后会自动检查。"
                        "approved" -> "授权成功，请选择模型并保存。"
                        "expired" -> "授权码已过期，请重新登录。"
                        "cancelled" -> "授权已取消。"
                        else -> "授权未完成，可以重试或使用 Hermes 原有登录方式。"
                    })
                    if (login.code.isNotBlank() && login.status == "pending") {
                        SetupCommandCard("复制授权码", "浏览器要求输入代码时，粘贴下面的授权码。", login.code,
                            sensitive = true, copyLabel = "复制授权码并去授权", next = "在服务商的授权页面粘贴代码、确认后返回。", contentName = "授权码",
                            onCopied = { runCatching { uriHandler.openUri(login.url) } })
                        Button(onClick = { runCatching { uriHandler.openUri(login.url) } }) { Text("打开浏览器授权") }
                    }
                    if (login.status in setOf("starting", "pending")) {
                        TextButton(enabled = !busy, onClick = { manager.submit(ModelSettingsRequest("poll", sessionId = login.id)) }) { Text("检查登录结果") }
                        TextButton(enabled = !busy, onClick = { manager.submit(ModelSettingsRequest("cancel", sessionId = login.id)) }) { Text("取消本次授权") }
                    }
                } }
                if (selection != null) ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("2 · 选择模型和接口", style = MaterialTheme.typography.titleMedium)
                    if (provider in setOf("custom", "local")) {
                        if (provider == "local") {
                            Text("填写运行在 Hermes 所在机器上的模型服务地址。远程 Hermes 中的 localhost 指远程机器；此处配置已有服务，不下载模型或安装 Ollama。", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilterChip(url.contains(":11434"), { url = "http://127.0.0.1:11434/v1" }, label = { Text("Ollama") })
                                FilterChip(url.contains(":1234"), { url = "http://127.0.0.1:1234/v1" }, label = { Text("LM Studio") })
                                FilterChip(url.contains(":8080"), { url = "http://127.0.0.1:8080/v1" }, label = { Text("llama.cpp") })
                            }
                        }
                        OutlinedTextField(url, { url = it }, label = { Text("模型接口地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (provider == "custom") {
                            Text("接口格式")
                            listOf("chat_completions" to "OpenAI 兼容", "codex_responses" to "Responses", "anthropic_messages" to "Anthropic Messages").forEach { (value, label) ->
                                FilterChip(apiMode == value, { apiMode = value }, label = { Text(label) })
                            }
                        }
                    }
                    OutlinedTextField(model, { model = it }, label = { Text("模型名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (selection.models.isNotEmpty()) TextButton(onClick = { chooseModel = true }) { Text("从模型列表选择") }
                    TextButton(enabled = !busy, onClick = { manager.submit(ModelSettingsRequest("models", provider)) }) { Text("读取可选模型列表") }
                    Button(enabled = !busy && model.isNotBlank(), onClick = { confirmSave = true }) { Text(if (saveAndConnect) "保存并开始聊天" else "保存模型配置") }
                } }
                TextButton(enabled = !busy, onClick = { manager.submit(ModelSettingsRequest("inspect")) }) { Text("刷新配置") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (externalMessage.isNotBlank()) {
                Text(externalMessage)
                if (!externalBusy && onRetryConnect != null) TextButton(onClick = onRetryConnect) { Text("重新启动并连接") }
            }
            Text(state.message, color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("模型服务 API 密钥和手机连接密钥用途不同。保存只更新模型配置，不删除会话，也不自动重启正在工作的远程 Hermes。", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (chooseProvider) AlertDialog(onDismissRequest = { chooseProvider = false }, title = { Text("选择提供方") }, text = {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 460.dp)) {
            items(snapshot?.providers?.size ?: 0) { index ->
                val row = snapshot!!.providers[index]
                TextButton(onClick = {
                    provider = row.id; key = ""; model = if (row.id == snapshot.provider) snapshot.model else ""
                    url = if (row.id == snapshot.provider) snapshot.baseUrl else if (row.id == "local") "http://127.0.0.1:11434/v1" else ""
                    apiMode = if (row.id == snapshot.provider) snapshot.apiMode else "chat_completions"; chooseProvider = false
                }) { Text(row.name + if (row.authenticated) " · 已连接" else "") }
            }
        }
    }, confirmButton = { TextButton(onClick = { chooseProvider = false }) { Text("关闭") } })
    if (chooseModel) AlertDialog(onDismissRequest = { chooseModel = false }, title = { Text("选择模型") }, text = {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 460.dp)) {
            items(selection?.models?.size ?: 0) { index -> val value = selection!!.models[index]
                TextButton(onClick = { model = value; chooseModel = false }) { Text(value) }
            }
        }
    }, confirmButton = { TextButton(onClick = { chooseModel = false }) { Text("关闭") } })
    if (confirmSave) AlertDialog(onDismissRequest = { confirmSave = false }, title = { Text("保存到 $connectionName？") },
        text = { Text("将更新这个 Hermes 的模型提供方、模型名称和你填写的密钥／端点，首次修改前备份配置。已有会话和其它连接不会删除。${if (saveAndConnect) " 保存后会启动本机 Hermes，检查连接并进入聊天。" else ""}") },
        confirmButton = { TextButton(onClick = {
            manager.submit(ModelSettingsRequest("save", provider, model.trim(), key.trim().takeIf(String::isNotEmpty), url.trim(), apiMode = apiMode)) { key = ""; onModelSaved() }
            confirmSave = false
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("取消") } })
    if (confirmLogin) AlertDialog(onDismissRequest = { confirmLogin = false }, title = { Text("连接 ${selection?.name.orEmpty()}？") },
        text = { Text("将在 $connectionName 的 Hermes 中发起服务商授权，登录凭据由该 Hermes 保存。手机只展示授权码和结果。") },
        confirmButton = { TextButton(onClick = { manager.submit(ModelSettingsRequest("login", provider)); confirmLogin = false }) { Text("开始授权") } },
        dismissButton = { TextButton(onClick = { confirmLogin = false }) { Text("取消") } })
}

private fun shellQuote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"

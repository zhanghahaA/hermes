package com.hermes.mobile.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.hermes.mobile.data.model.SshAuthMethod
import com.hermes.mobile.data.ssh.SshKeys
import com.hermes.mobile.platform.rememberTextFilePicker
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.model.ConnectionProfile
import com.hermes.mobile.data.model.ConnectionTestStep
import com.hermes.mobile.data.model.ConnectionType
import com.hermes.mobile.data.model.TestStepStatus
import com.hermes.mobile.ui.LocalShowMessage
import com.hermes.mobile.ui.components.icon
import com.hermes.mobile.ui.theme.CodeTextStyle
import com.hermes.mobile.ui.theme.LocalHermesColors
import kotlinx.coroutines.launch
import com.hermes.mobile.data.setup.ConnectionSetup
import com.hermes.mobile.platform.rememberTextCopier
import com.hermes.mobile.platform.rememberConnectionSetupProgram
import com.hermes.mobile.ui.components.SetupCommandCard
import com.hermes.mobile.data.store.DiagnosticRedactor
import java.util.UUID

@Composable
fun ConnectionEditorScreen(
    repository: HermesRepository,
    connectionId: String?,
    initialType: String? = null,
    onOpenLocalRuntime: (() -> Unit)? = null,
    onClose: () -> Unit,
) {
    val connections by repository.connections.collectAsState()
    val existing = remember(connectionId) { connections.firstOrNull { it.id == connectionId } }
    val defaultType = existing?.type ?: initialType?.let { runCatching { ConnectionType.valueOf(it) }.getOrNull() } ?: ConnectionType.Ssh

    var type by rememberSaveable { mutableStateOf(defaultType) }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var host by rememberSaveable { mutableStateOf(existing?.host.orEmpty()) }
    var port by rememberSaveable { mutableStateOf((existing?.port ?: 8642).toString()) }
    var apiKey by remember { mutableStateOf("") }
    var profileName by rememberSaveable { mutableStateOf(existing?.profile.orEmpty()) }
    var sshUser by rememberSaveable { mutableStateOf(existing?.sshUser.orEmpty()) }
    var sshPort by rememberSaveable { mutableStateOf((existing?.sshPort ?: 22).toString()) }
    var sshAuth by rememberSaveable { mutableIntStateOf(if (existing?.sshAuth == SshAuthMethod.Password) 1 else 0) }
    // 这次编辑里新生成/导入的私钥，和输入的 SSH 密码；留空表示沿用已保存的
    var privateKey by remember { mutableStateOf("") }
    var publicKey by remember { mutableStateOf(existing?.id?.let(repository::sshPublicKey).orEmpty()) }
    var sshPassword by remember { mutableStateOf("") }
    var resetHostKey by rememberSaveable { mutableStateOf(false) }
    // 本机 Termux / 直连：可选的“文件互通”SSH
    var fileSsh by rememberSaveable { mutableStateOf(existing != null && existing.type != ConnectionType.Ssh && !existing.sshUser.isNullOrBlank()) }
    var showPaste by rememberSaveable { mutableStateOf(false) }
    val showMessage = LocalShowMessage.current
    val copyText = rememberTextCopier()
    val readText = com.hermes.mobile.platform.rememberTextReader()
    val setupProgram = rememberConnectionSetupProgram()
    val draftId = rememberSaveable { existing?.id ?: "c-${UUID.randomUUID()}" }
    val setupNonce = remember { UUID.randomUUID().toString() }
    var choosingType by rememberSaveable { mutableStateOf(initialType == null && existing == null) }
    var advanced by rememberSaveable { mutableStateOf(existing != null) }
    var showQuickSetup by rememberSaveable { mutableStateOf(existing == null) }
    var showSetupResult by remember { mutableStateOf(false) }
    var copiedSetup by remember { mutableStateOf(false) }
    var setupKeyAtCopy by remember { mutableStateOf("") }
    var discoveredHostKey by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    fun useKey(text: String?) {
        if (text.isNullOrBlank()) {
            showMessage("没读到内容（文件太大或不是文本）")
            return
        }
        try {
            publicKey = SshKeys.publicKeyLine(text)
            privateKey = text
        } catch (e: Exception) {
            showMessage(e.message ?: "看不懂这个私钥")
        }
    }
    val pickKeyFile = rememberTextFilePicker(::useKey)
    var steps by remember { mutableStateOf<List<ConnectionTestStep>>(emptyList()) }
    val scope = rememberCoroutineScope()

    fun currentProfile() = ConnectionProfile(
        id = draftId,
        name = name.ifBlank { type.label },
        type = type,
        host = if (type == ConnectionType.Local) "127.0.0.1" else host,
        port = port.toIntOrNull() ?: -1,
        profile = profileName.ifBlank { null },
        sshUser = sshUser.ifBlank { null }?.takeIf { type == ConnectionType.Ssh || (fileSsh && (type == ConnectionType.Local || type == ConnectionType.Direct)) },
        sshPort = sshPort.toIntOrNull() ?: -1,
        sshAuth = if (sshAuth == 0) SshAuthMethod.Key else SshAuthMethod.Password,
        sshHostKey = if (resetHostKey) null else discoveredHostKey ?: existing?.sshHostKey,
    )

    fun sshSecret(): String? {
        val usesSsh = type == ConnectionType.Ssh || (fileSsh && (type == ConnectionType.Local || type == ConnectionType.Direct))
        return if (!usesSsh) null else if (sshAuth == 0) privateKey.ifBlank { null } else sshPassword.ifBlank { null }
    }

    fun report(error: Throwable) {
        showMessage(DiagnosticRedactor.redact(error.message ?: "未完成，请重试", listOf(apiKey, privateKey, sshPassword).filter(String::isNotBlank)))
    }
    fun makeSetupCommand(reuseHelper: Boolean = false) {
        runCatching {
            val profile = currentProfile()
            ConnectionSetup.validate(profile)
            if (apiKey.isBlank()) apiKey = ConnectionSetup.generateApiKey()
            if (profile.type == ConnectionType.Ssh && profile.sshAuth == SshAuthMethod.Key && publicKey.isBlank()) {
                val pair = SshKeys.generate()
                privateKey = pair.privateKey
                publicKey = pair.publicKey
            }
            val command = ConnectionSetup.command(setupProgram, profile, apiKey,
                publicKey.takeIf { profile.type == ConnectionType.Ssh && profile.sshAuth == SshAuthMethod.Key }.orEmpty(), setupNonce, reuseHelper)
            copyText(command, true)
            copiedSetup = true
            setupKeyAtCopy = apiKey
            showMessage("已复制。请在运行 Hermes 的机器上粘贴并执行。")
        }.onFailure(::report)
    }
    fun checkAndConnect() {
        if (testing) return
        runCatching {
            var candidate = currentProfile()
            ConnectionSetup.validate(candidate)
            var key = apiKey.ifBlank { null }
            val secret = sshSecret()
            require(key != null || existing != null) { "请先复制配置命令，或填写已有的 API Key" }
            require(key == null || key.length >= 16) { "API Key 至少需要 16 位" }
            if (candidate.sshUser != null) require(secret != null || existing?.sshUser != null) { "请先生成／导入密钥，或填写 SSH 密码" }
            testing = true
            steps = emptyList()
            scope.launch {
                try {
                    if (candidate.type == ConnectionType.Ssh && copiedSetup && apiKey == setupKeyAtCopy) {
                        val original = candidate
                        val originalKey = key
                        val result = repository.readConnectionSetup(candidate, secret, setupNonce)
                        if (result != null) {
                            check(currentProfile() == original && apiKey.ifBlank { null } == originalKey && sshSecret() == secret) { "设置已变化，请按当前设置重新检查" }
                            apiKey = result.apiKey
                            key = result.apiKey
                            port = result.port.toString()
                            discoveredHostKey = result.hostKey
                            resetHostKey = false
                            candidate = currentProfile()
                        }
                    }
                    for (attempt in 0..2) {
                        repository.testConnection(candidate, key, secret).collect { steps = it }
                        if (!copiedSetup || steps.none { it.status == TestStepStatus.Failed && it.title == "网络可达" } || attempt == 2) break
                        kotlinx.coroutines.delay(1500)
                    }
                    val passed = steps.isNotEmpty() && steps.all { it.status == TestStepStatus.Passed || it.status == TestStepStatus.Skipped } &&
                        steps.any { it.status == TestStepStatus.Passed }
                    if (passed) {
                        check(currentProfile() == candidate && apiKey.ifBlank { null } == key && sshSecret() == secret) { "设置已变化，请按当前设置重新检查" }
                        repository.saveConnection(candidate, key, secret)
                        if (repository.activeConnectionId.value != candidate.id) repository.switchConnection(candidate.id)
                        showMessage("检查通过，已保存并连接")
                        onClose()
                    } else showMessage("尚未连接成功，请按检查结果处理后再试")
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { report(e) }
                finally { testing = false }
            }
        }.onFailure(::report)
    }

    // SSH 账号（SSH 隧道用；本机 Termux / 直连的“文件互通”也用这一套）
    val sshCredentials: @Composable () -> Unit = {
        OutlinedTextField(
            value = sshUser,
            onValueChange = { sshUser = it },
            label = { Text("用户名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (advanced) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("密钥", "密码").forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = sshAuth == index,
                        onClick = { sshAuth = index },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                    ) { Text(label) }
                }
            }
            if (sshAuth == 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = pickKeyFile, modifier = Modifier.weight(1f)) { Text("导入文件") }
                    OutlinedButton(onClick = { showPaste = true }, modifier = Modifier.weight(1f)) { Text("粘贴") }
                }
                FilledTonalButton(
                    onClick = {
                        val pair = SshKeys.generate()
                        privateKey = pair.privateKey
                        publicKey = pair.publicKey
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Key, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (publicKey.isEmpty()) "在手机上生成新密钥" else "重新生成密钥")
                }
                if (publicKey.isNotEmpty()) {
                    PublicKeyCard(publicKey, saved = privateKey.isEmpty(), user = sshUser.ifBlank { "用户" })
                }
            } else {
                SecretField(
                    "SSH 密码",
                    sshPassword,
                    { sshPassword = it },
                    if (!existing?.sshUser.isNullOrBlank() && existing?.sshAuth == SshAuthMethod.Password) "已保存。留空表示不修改" else null,
                )
            }
        } else Text("登录密钥会在复制配置命令时自动生成，无需创建文件。", style = MaterialTheme.typography.bodySmall)
        existing?.sshHostKey?.let { pinned ->
            HostKeyRow(pinned, reset = resetHostKey, onReset = { resetHostKey = true })
        }
    }

    if (showPaste) {
        PasteKeyDialog(onDismiss = { showPaste = false }, onDone = { showPaste = false; useKey(it) })
    }

    if (showSetupResult) {
        var resultText by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showSetupResult = false }, title = { Text("粘贴配置结果") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("复制终端中以 HERMES-MOBILE-SETUP: 开头的完整一行。它含连接密钥，请勿发到公开群聊。")
                OutlinedTextField(resultText, { resultText = it }, label = { Text("连接码") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            } }, confirmButton = { TextButton(enabled = resultText.isNotBlank(), onClick = {
                runCatching { val result = ConnectionSetup.readResult(resultText, currentProfile(), setupNonce); apiKey = result.apiKey; port = result.port.toString(); showSetupResult = false; showMessage("已填入服务器的已有密钥，可检查并连接") }.onFailure(::report)
            }) { Text("自动填入") } }, dismissButton = { TextButton(onClick = { showSetupResult = false }) { Text("取消") } })
    }

    Scaffold(
        bottomBar = { Surface(tonalElevation = 3.dp) {
            Button(onClick = ::checkAndConnect, enabled = !testing,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.NetworkCheck, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (testing) "正在检查…" else "检查并连接")
            }
        } },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "关闭") } },
                title = { Text(if (existing == null) "添加连接" else "编辑连接") },
                actions = {
                    if (advanced) Button(
                        enabled = !testing,
                        onClick = {
                            runCatching {
                                ConnectionSetup.validate(currentProfile())
                                repository.saveConnection(currentProfile(), apiKey.ifBlank { null }, sshSecret())
                                onClose()
                            }.onFailure(::report)
                        },
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text("保存") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("连接方式", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (choosingType) TypeGrid(type, onSelect = { type = it; steps = emptyList(); choosingType = false })
            else Row(verticalAlignment = Alignment.CenterVertically) {
                Text(type.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { choosingType = true }) { Text("更换连接方式") }
            }

            Text("1 · 填写连接地址", style = MaterialTheme.typography.titleMedium)
            Text("端口和连接名称已有默认值。只填你知道的信息，其余放在高级设置中。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级设置" else "高级设置／已有密钥") }
            if (advanced) OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                placeholder = { Text("例如：家里 NAS") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            AnimatedContent(targetState = type, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "type-fields") { current ->
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (current) {
                        ConnectionType.Direct -> {
                            HostPortFields(host, { host = it }, port, { port = it }, "地址", "电脑的局域网或 VPN 地址", showPort = advanced)
                            if (advanced) FileSshSection(
                                enabled = fileSsh,
                                onEnabled = { fileSsh = it },
                                hint = "同一台机器的 SSH 账号。配好后能显示 Hermes 生成的图片、发送任意文件（聊天仍然走直连）",
                                sshPort = sshPort,
                                onSshPort = { sshPort = it },
                                credentials = sshCredentials,
                            )
                        }
                        ConnectionType.Https -> {
                            OutlinedTextField(
                                value = host,
                                onValueChange = { host = it },
                                label = { Text("网址") },
                                placeholder = { Text("https://hermes.example.com") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth(),
                            )

                        }
                        ConnectionType.Ssh -> {
                            HostPortFields(host, { host = it }, sshPort, { sshPort = it }, "SSH 主机", "电脑或服务器地址", "SSH 端口", showPort = advanced)
                            sshCredentials()
                            if (advanced) OutlinedTextField(
                                value = port,
                                onValueChange = { port = it },
                                label = { Text("远端 Hermes 端口") },
                                supportingText = { Text("隧道会把手机本地端口转发到远端 127.0.0.1 的这个端口") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        ConnectionType.Local -> {
                            if (advanced) OutlinedTextField(
                                value = port,
                                onValueChange = { port = it },
                                label = { Text("Hermes 端口") },
                                supportingText = { Text("Hermes API Server 的端口，默认 8642（不是 Termux SSH 的 8022）") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (advanced) FileSshSection(
                                enabled = fileSsh,
                                onEnabled = { on ->
                                    fileSsh = on
                                    if (on && sshPort == "22") sshPort = "8022"
                                    if (on && sshUser.isBlank()) sshUser = "termux"
                                },
                                hint = "填 Termux 自己的 SSH（sshd，默认端口 8022）。配好后能显示 Hermes 生成的图片、发送任意文件",
                                sshPort = sshPort,
                                onSshPort = { sshPort = it },
                                credentials = sshCredentials,
                            )
                            if (advanced && fileSsh) {
                                sshPort.toIntOrNull()?.takeIf { it in 1..65535 }?.let { localSshPort ->
                                    SetupCommandCard("准备手机文件互通", "在这台手机的 Termux 中粘贴并执行，只开放本机回环地址。",
                                        "pkg install -y openssh && (ssh-keyscan -T 2 -p $localSshPort 127.0.0.1 >/dev/null 2>&1 || sshd -p $localSshPort -o ListenAddress=127.0.0.1)",
                                        copyLabel = "复制文件互通准备命令", next = "再执行上方公钥安装命令，返回检查连接。")
                                }
                                SetupCommandCard("允许访问下载文件", "在 Termux 中执行，出现系统存储授权时选择允许。",
                                    "termux-setup-storage", copyLabel = "复制存储授权命令", next = "授权完成后返回 Hermes，重新发送或打开文件。")
                            }
                        }
                    }
                }
            }

            val managedLocal = type == ConnectionType.Local && existing?.id == com.hermes.mobile.data.local.LocalRuntimeManager.ConnectionId
            if (managedLocal) {
                Text("这台手机的连接由本机运行中心管理，请在那里修复或修改配置，避免改到其它 Hermes 目录。", style = MaterialTheme.typography.bodyMedium)
                onOpenLocalRuntime?.let { open -> Button(onClick = open) { Text("打开本机运行中心") } }
            }
            if (!managedLocal && !showQuickSetup) TextButton(onClick = { showQuickSetup = true }) { Text("连接不上？打开配置向导") }
            if (!managedLocal && showQuickSetup) Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("2 · 复制一次，完成配置", style = MaterialTheme.typography.titleMedium)
                    Text(if (type == ConnectionType.Local) "在这台手机的 Termux 中粘贴，按回车运行。需已安装 Hermes。"
                        else "在运行 Hermes 的机器上${if (type == ConnectionType.Ssh && sshUser.isNotBlank()) "以 $sshUser 登录后" else ""}打开终端，粘贴并按回车（Linux／macOS，需已安装 Hermes）。")
                    Text("自动备份配置、开通手机连接并授权本手机，保留已有密钥和端口。配置改变或服务异常时才请求重启；服务正常且配置未变时不重启。", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { makeSetupCommand() }, enabled = !testing) { Text(if (copiedSetup) "重新复制配置命令" else "生成并复制配置命令") }
                    if (copiedSetup) TextButton(onClick = { makeSetupCommand(reuseHelper = true) }, enabled = !testing) { Text("已执行过？复制简短重试命令") }
                    if (copiedSetup) Text("已复制。执行完成后，直接点底部「检查并连接」。SSH 会安全读取连接结果；直连若已有密钥，请填写原 API Server Key。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        val text = readText()
                        if (text != null && text.contains(ConnectionSetup.ReceiptPrefix)) {
                            runCatching { val result = ConnectionSetup.readResult(text, currentProfile(), setupNonce); apiKey = result.apiKey; port = result.port.toString(); showMessage("已填入服务器的已有密钥") }.onFailure(::report)
                        } else showSetupResult = true
                    }) { Text("粘贴配置结果") }
                }
            }
            if (advanced) SecretField(
                "API Key",
                apiKey,
                { apiKey = it },
                if (existing != null) "已保存。留空表示不修改" else "会在复制配置命令时自动填好；已有密钥也可直接粘贴",
            )
            if (!advanced && apiKey.isNotBlank()) Text(if (type == ConnectionType.Ssh && copiedSetup) "检查时会通过 SSH 自动获取连接密钥，无需手动填写。" else "连接密钥已填入，检查通过后会加密保存。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                val text = readText()?.trim()
                if (text != null && text.length in 16..4096 && text.none { it.isWhitespace() || it.isISOControl() }) {
                    apiKey = text
                    showMessage("API Key 已粘贴")
                } else showMessage("请先复制有效的 API Key；终端生成的连接码请用「粘贴配置结果」")
            }) { Text("粘贴已有 API Key") }
            if (advanced) OutlinedTextField(
                value = profileName,
                onValueChange = { profileName = it },
                label = { Text("Hermes Profile（可选）") },
                placeholder = { Text("default") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            ConnectionTestPanel(steps) {
                checkAndConnect()
            }

            if (existing != null) {
                OutlinedButton(
                    onClick = {
                        repository.deleteConnection(existing.id)
                        onClose()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text("删除这个连接", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TypeGrid(selected: ConnectionType, onSelect: (ConnectionType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ConnectionType.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { type ->
                    TypeCard(type, type == selected, { onSelect(type) }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun TypeCard(type: ConnectionType, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier.height(96.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) colors.primaryContainer else colors.surfaceContainerLow,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(type.icon, null, tint = if (selected) colors.primary else colors.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(type.label, style = MaterialTheme.typography.titleSmall)
            Text(
                type.description,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun HostPortFields(
    host: String,
    onHost: (String) -> Unit,
    port: String,
    onPort: (String) -> Unit,
    hostLabel: String,
    hostHint: String,
    portLabel: String = "端口",
    showPort: Boolean = true,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = host,
            onValueChange = onHost,
            label = { Text(hostLabel) },
            placeholder = { Text(hostHint) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.weight(1f),
        )
        if (showPort) OutlinedTextField(
            value = port,
            onValueChange = onPort,
            label = { Text(portLabel) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(104.dp),
        )
    }
}

/** 本机 Termux / 直连的可选 SSH：只用来传文件，聊天不走它。 */
@Composable
private fun FileSshSection(
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    hint: String,
    sshPort: String,
    onSshPort: (String) -> Unit,
    credentials: @Composable () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("文件互通（可选）", style = MaterialTheme.typography.titleSmall)
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = enabled, onCheckedChange = onEnabled)
            }
            if (enabled) {
                OutlinedTextField(
                    value = sshPort,
                    onValueChange = onSshPort,
                    label = { Text("SSH 端口") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                credentials()
            }
        }
    }
}

/** 公钥：用户要把它加到服务器上。给出可以直接粘贴到服务器终端执行的命令。 */
@Composable
private fun PublicKeyCard(publicKey: String, saved: Boolean, user: String) {
    val copy = rememberTextCopier()
    val message = LocalShowMessage.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SetupCommandCard("把手机公钥加入服务器", "以 $user 登录运行 Hermes 的机器，在终端粘贴并按回车。",
            ConnectionSetup.publicKeyCommand(publicKey), copyLabel = "复制公钥安装命令")
        TextButton(onClick = { copy(publicKey, false); message("公钥已复制") }) { Text("复制公钥") }
        TextButton(onClick = { copy(ConnectionSetup.revokeKeyCommand(publicKey), false); message("撤销命令已复制。在服务器执行后，本公钥将无法建立新连接；共用这把私钥的设备也会受影响。") }) { Text("复制本手机授权撤销命令") }
        if (saved) Text("私钥已加密保存在手机。可以重复执行安装命令，不会重复添加相同公钥。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HostKeyRow(pinned: String, reset: Boolean, onReset: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Rounded.Fingerprint, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(if (reset) "主机指纹已重置" else "已记住主机指纹", style = MaterialTheme.typography.labelLarge)
            Text(
                if (reset) "保存后，下次连接时重新记住" else pinned,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!reset) OutlinedButton(onClick = onReset) { Text("重置") }
    }
}

/** 粘贴私钥全文。 */
@Composable
private fun PasteKeyDialog(onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴私钥") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----\n…") },
                textStyle = CodeTextStyle,
                minLines = 6,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { Button(onClick = { onDone(text) }, enabled = text.isNotBlank()) { Text("使用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit, hint: String? = null) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = if (hint != null) { { Text(hint) } } else null,
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = if (visible) "隐藏" else "显示")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ConnectionTestPanel(steps: List<ConnectionTestStep>, onTest: () -> Unit) {
    val running = steps.any { it.status == TestStepStatus.Running }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.animateContentSize(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("测试连接", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "逐项检查，失败时告诉你卡在哪一步",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = onTest, enabled = !running) {
                    Icon(Icons.Rounded.NetworkCheck, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (steps.isEmpty()) "开始" else "重新测试")
                }
            }
            AnimatedVisibility(steps.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                    steps.forEach { step -> TestStepRow(step) }
                }
            }
        }
    }
}

@Composable
private fun TestStepRow(step: ConnectionTestStep) {
    // 短的说明（如 “14 ms”）放在右边；长的（失败原因、指纹）放到标题下面，别把标题挤成竖排
    val inline = step.detail != null && step.detail.length <= 16 && step.status != TestStepStatus.Failed
    Row(verticalAlignment = if (inline || step.detail == null) Alignment.CenterVertically else Alignment.Top) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when (step.status) {
                TestStepStatus.Running -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                TestStepStatus.Passed -> Icon(Icons.Rounded.CheckCircle, null, tint = LocalHermesColors.current.online)
                TestStepStatus.Failed -> Icon(Icons.Rounded.Cancel, null, tint = MaterialTheme.colorScheme.error)
                TestStepStatus.Pending, TestStepStatus.Skipped ->
                    Icon(Icons.Rounded.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                step.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (step.status == TestStepStatus.Pending) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (step.detail != null && !inline) {
                Text(
                    step.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (step.status == TestStepStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (inline) {
            Spacer(Modifier.width(8.dp))
            Text(step.detail!!, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

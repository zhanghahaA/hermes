package com.hermes.mobile.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
import androidx.compose.material.icons.rounded.ContentCopy
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
import com.hermes.mobile.ui.rememberPlaceholder
import com.hermes.mobile.ui.theme.CodeTextStyle
import com.hermes.mobile.ui.theme.LocalHermesColors
import kotlinx.coroutines.launch

@Composable
fun ConnectionEditorScreen(
    repository: HermesRepository,
    connectionId: String?,
    initialType: String? = null,
    onClose: () -> Unit,
) {
    val connections by repository.connections.collectAsState()
    val existing = remember(connectionId) { connections.firstOrNull { it.id == connectionId } }
    val defaultType = existing?.type ?: initialType?.let { runCatching { ConnectionType.valueOf(it) }.getOrNull() } ?: ConnectionType.Direct

    var type by rememberSaveable { mutableStateOf(defaultType) }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var host by rememberSaveable { mutableStateOf(existing?.host.orEmpty()) }
    var port by rememberSaveable { mutableStateOf((existing?.port ?: 8642).toString()) }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var profileName by rememberSaveable { mutableStateOf(existing?.profile.orEmpty()) }
    var sshUser by rememberSaveable { mutableStateOf(existing?.sshUser.orEmpty()) }
    var sshPort by rememberSaveable { mutableStateOf((existing?.sshPort ?: 22).toString()) }
    var sshAuth by rememberSaveable { mutableIntStateOf(if (existing?.sshAuth == SshAuthMethod.Password) 1 else 0) }
    // 这次编辑里新生成/导入的私钥，和输入的 SSH 密码；留空表示沿用已保存的
    var privateKey by rememberSaveable { mutableStateOf("") }
    var publicKey by rememberSaveable { mutableStateOf(existing?.id?.let(repository::sshPublicKey).orEmpty()) }
    var sshPassword by rememberSaveable { mutableStateOf("") }
    var resetHostKey by rememberSaveable { mutableStateOf(false) }
    var showPaste by rememberSaveable { mutableStateOf(false) }
    val showMessage = LocalShowMessage.current
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
    val placeholder = rememberPlaceholder()

    fun currentProfile() = ConnectionProfile(
        id = existing?.id ?: "c-${System.currentTimeMillis()}",
        name = name.ifBlank { type.label },
        type = type,
        host = if (type == ConnectionType.Local) "127.0.0.1" else host,
        port = port.toIntOrNull() ?: 8642,
        profile = profileName.ifBlank { null },
        sshUser = sshUser.ifBlank { null },
        sshPort = sshPort.toIntOrNull() ?: 22,
        sshAuth = if (sshAuth == 0) SshAuthMethod.Key else SshAuthMethod.Password,
        sshHostKey = if (resetHostKey) null else existing?.sshHostKey,
    )

    fun sshSecret(): String? = if (type != ConnectionType.Ssh) null else if (sshAuth == 0) privateKey.ifBlank { null } else sshPassword.ifBlank { null }

    if (showPaste) {
        PasteKeyDialog(onDismiss = { showPaste = false }, onDone = { showPaste = false; useKey(it) })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "关闭") } },
                title = { Text(if (existing == null) "添加连接" else "编辑连接") },
                actions = {
                    Button(
                        onClick = {
                            repository.saveConnection(currentProfile(), apiKey.ifBlank { null }, sshSecret())
                            onClose()
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
            TypeGrid(type, onSelect = { type = it; steps = emptyList() })

            OutlinedTextField(
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
                        ConnectionType.Direct -> HostPortFields(host, { host = it }, port, { port = it }, "地址", "100.x.x.x 或 192.168.x.x")
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
                            OutlinedTextField(
                                value = "",
                                onValueChange = {},
                                label = { Text("证书指纹（可选）") },
                                supportingText = { Text("自签证书时填写 SHA-256 指纹") },
                                leadingIcon = { Icon(Icons.Rounded.Fingerprint, null) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        ConnectionType.Ssh -> {
                            HostPortFields(host, { host = it }, sshPort, { sshPort = it }, "SSH 主机", "nas.local 或 100.x.x.x", "SSH 端口")
                            OutlinedTextField(
                                value = sshUser,
                                onValueChange = { sshUser = it },
                                label = { Text("用户名") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
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
                                    if (existing?.type == ConnectionType.Ssh && existing.sshAuth == SshAuthMethod.Password) "已保存。留空表示不修改" else null,
                                )
                            }
                            existing?.sshHostKey?.let { pinned ->
                                HostKeyRow(pinned, reset = resetHostKey, onReset = { resetHostKey = true })
                            }
                            OutlinedTextField(
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
                            OutlinedTextField(
                                value = port,
                                onValueChange = { port = it },
                                label = { Text("Hermes 端口") },
                                supportingText = { Text("Hermes API Server 的端口，默认 8642（不是 Termux SSH 的 8022）") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TermuxGuide()
                        }
                    }
                }
            }

            SecretField(
                "API Key",
                apiKey,
                { apiKey = it },
                if (existing != null) "已保存。留空表示不修改" else "对应服务器上的 API_SERVER_KEY（至少 16 位）",
            )
            OutlinedTextField(
                value = profileName,
                onValueChange = { profileName = it },
                label = { Text("Hermes Profile（可选）") },
                placeholder = { Text("default") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            ConnectionTestPanel(steps) {
                scope.launch { repository.testConnection(currentProfile(), apiKey.ifBlank { null }, sshSecret()).collect { steps = it } }
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
        OutlinedTextField(
            value = port,
            onValueChange = onPort,
            label = { Text(portLabel) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(104.dp),
        )
    }
}

/** 公钥：用户要把它加到服务器上。给出可以直接粘贴到服务器终端执行的命令。 */
@Composable
private fun PublicKeyCard(publicKey: String, saved: Boolean, user: String) {
    val clipboard = LocalClipboardManager.current
    val showMessage = LocalShowMessage.current
    val command = "mkdir -p ~/.ssh && echo '$publicKey' >> ~/.ssh/authorized_keys"
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (saved) "已保存私钥。对应的公钥：" else "把公钥加到服务器上（以 $user 登录后执行下面的命令）：",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (saved) publicKey else command,
                style = CodeTextStyle,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LocalHermesColors.current.codeBackground, MaterialTheme.shapes.small)
                    .padding(10.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(publicKey)); showMessage("公钥已复制") }) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("复制公钥")
                }
                if (!saved) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(command)); showMessage("命令已复制") }) { Text("复制命令") }
                }
            }
        }
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
private fun TermuxGuide() {
    val showMessage = LocalShowMessage.current
    @Suppress("DEPRECATION")
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val commands = listOf(
        "安装 Hermes" to "# 按 Hermes 官方文档在 Termux 中安装",
        "开启 API Server" to "echo 'API_SERVER_ENABLED=true' >> ~/.hermes/.env",
        "设置 API Key" to "echo 'API_SERVER_KEY=<随机长密钥>' >> ~/.hermes/.env",
        "防止被系统杀掉" to "termux-wake-lock",
        "启动" to "hermes gateway",
        "检查（应该返回 platform: hermes-agent）" to "curl http://127.0.0.1:8642/health",
    )
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("在 Termux 里运行 Hermes", style = MaterialTheme.typography.titleMedium)
            Text(
                "按顺序在 Termux 中执行下面的命令（示意，具体以官方文档为准）。还需要在系统设置里关闭 Termux 的电池优化。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            commands.forEachIndexed { index, (title, command) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${index + 1}. $title", style = MaterialTheme.typography.labelLarge)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            color = LocalHermesColors.current.codeBackground,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(command, style = CodeTextStyle, modifier = Modifier.padding(10.dp))
                        }
                        IconButton(onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(command))
                            showMessage("已复制命令")
                        }) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = "复制命令")
                        }
                    }
                }
            }
        }
    }
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

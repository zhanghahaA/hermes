package com.hermes.mobile.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.notifications.NotificationAssistant
import com.hermes.mobile.data.notifications.NotificationConfig
import com.hermes.mobile.data.notifications.NotificationTarget
import com.hermes.mobile.platform.rememberNotificationAccess
import com.hermes.mobile.platform.rememberNotificationApps
import com.hermes.mobile.ui.components.NoInsets
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun NotificationAssistantScreen(repository: HermesRepository, assistant: NotificationAssistant, onBack: () -> Unit) {
    val state by assistant.state.collectAsState()
    val busy by assistant.busy.collectAsState()
    val connections by repository.connections.collectAsState()
    val active by repository.activeConnectionId.collectAsState()
    val bot by repository.activeBot.collectAsState()
    val sessions by repository.sessions.collectAsState()
    val scope = rememberCoroutineScope()
    val (granted, openAccess) = rememberNotificationAccess()
    val apps = rememberNotificationApps()
    var confirmEnable by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    var showRecords by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val config = state.config
    var keywordText by remember { mutableStateOf(config.blockedWords.joinToString("，")) }
    fun configure(next: NotificationConfig) {
        runCatching { assistant.configure(next) }.onFailure { error = it.message ?: "设置未保存，请重试" }
    }
    Scaffold(
        contentWindowInsets = NoInsets,
        topBar = { TopAppBar(title = { Text("通知助手 · 开发版") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
        }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("把选定应用的新通知保存在手机，定时发到固定的 Hermes 会话，整理待办和提醒。",
                Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium)
            SettingsGroup("读取与授权") {
                SwitchRow(Icons.Rounded.Notifications, "启用通知助手", "默认关闭，随时暂停读取和发送",
                    checked = config.enabled, onCheckedChange = { if (it) confirmEnable = true else configure(config.copy(enabled = false)) })
                GroupDivider()
                SettingsRow(Icons.Rounded.Security, "系统通知使用权", if (granted) "已授权" else "未授权，点此去系统设置开启",
                    onClick = openAccess)
                GroupDivider()
                SettingsRow(Icons.Rounded.Apps, "允许读取的应用", "已选 " + config.packages.size + " 个；银行和支付类仍会过滤",
                    onClick = { showApps = true })
            }
            SettingsGroup("摘要发送") {
                SettingsRow(Icons.Rounded.Forum, "固定会话", config.target?.label ?: "尚未绑定",
                    enabled = !busy && !creating, onClick = { choosing = true })
                GroupDivider()
                Text("绑定后切换连接或 Bot 不会改变发送目标。已有待发批次时，需先完成或清除本地记录再换目标。",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(15, 30, 60).forEach { minutes ->
                        FilterChip(selected = config.intervalMinutes == minutes,
                            onClick = { configure(config.copy(intervalMinutes = minutes)) }, label = { Text(minutes.toString() + " 分钟") })
                    }
                }
                Text("由系统安排，省电或锁屏时可能延迟；vivo 上需允许后台运行。断网保留，恢复后重试。",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = keywordText,
                    onValueChange = { keywordText = it; configure(config.copy(blockedWords = it.split('，', ',', '\n'))) },
                    label = { Text("不读取的关键词（逗号分隔）") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                Text(state.status, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = { scope.launch {
                        try { assistant.summarize(granted, force = true) }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { error = "本地记录未保存，请检查手机存储空间后重试" }
                    } },
                    enabled = config.enabled && granted && config.target != null && !busy,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text(if (busy) "正在总结…" else "立即总结 / 重试") }
            }
            SettingsGroup("手机上的记录") {
                SettingsRow(Icons.Rounded.List, "查看记录",
                    "待处理 " + state.records.count { !it.sent } + " 条 · 共 " + state.records.size + " 条",
                    onClick = { showRecords = !showRecords })
                if (showRecords) {
                    state.records.asReversed().take(50).forEach { row ->
                        Text(row.appName + " · " + DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(row.time)) +
                            (if (row.sent) " · 已总结" else " · 待处理") + "\n" + row.title + "\n" + row.text,
                            Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                    }
                    if (state.records.size > 50) Text("仅显示最近 50 条", Modifier.padding(16.dp))
                }
                SettingsRow(Icons.Rounded.DeleteOutline, "清除本地记录和摘要", "已发往 Hermes 的内容需在对应会话另行删除",
                    enabled = !busy, onClick = { confirmClear = true })
            }
            if (state.lastSummary.isNotBlank()) SettingsGroup("最近摘要") {
                Text(state.lastSummary, Modifier.padding(16.dp))
            }
            Text("只采集开启后的新通知；验证码、银行和支付类默认过滤，长数字与凭据会隐藏。过滤仍可能遗漏，请谨慎选择应用。" +
                "记录在手机加密保存，最多 500 条，下次处理时清理超过 7 天的记录。不会写入长期记忆。" +
                "摘要会发给绑定的 Hermes，并由它配置的模型处理；仅请求总结，不自动批准任何操作。",
                Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirmEnable) AlertDialog(onDismissRequest = { confirmEnable = false },
        title = { Text("开启通知助手？") },
        text = { Text("将读取你勾选应用的新通知，过滤后在手机加密保存，并定时发送到绑定的 Hermes 会话及其模型生成摘要。" +
            "系统授权本身允许访问通知，但本 App 只保存白名单内且通过过滤的内容。请避免选择含隐私的应用。关闭开关即可暂停。") },
        confirmButton = { TextButton(onClick = { configure(config.copy(enabled = true)); confirmEnable = false }) { Text("同意并开启") } },
        dismissButton = { TextButton(onClick = { confirmEnable = false }) { Text("取消") } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清除手机记录？") },
        text = { Text("删除本地通知、待发批次和摘要。已经发到 Hermes 的内容不受影响。") },
        confirmButton = { TextButton(onClick = {
            runCatching { assistant.clear() }.onFailure { error = it.message }; confirmClear = false
        }) { Text("清除") } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
    if (showApps) AlertDialog(onDismissRequest = { showApps = false }, title = { Text("允许读取的应用") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            (apps.map { it.packageName to it.name } + config.packages.filter { selected -> apps.none { it.packageName == selected } }.map { it to it })
                .forEach { (pkg, name) ->
                    Row { Checkbox(checked = pkg in config.packages,
                        modifier = Modifier.semantics { contentDescription = "允许读取" + name },
                        onCheckedChange = { checked ->
                        configure(config.copy(packages = if (checked) config.packages + pkg else config.packages - pkg))
                    }); Text(name, Modifier.padding(top = 12.dp)) }
                }
        } }, confirmButton = { TextButton(onClick = { showApps = false }) { Text("完成") } })
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text("绑定当前连接与 Bot") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            val connection = connections.firstOrNull { it.id == active }
            val targetBot = bot ?: connection?.profile ?: "default"
            Text((connection?.name ?: "请先添加连接") + " · " + targetBot)
            Text("可新建专用会话，或选择下面的现有会话。")
            TextButton(enabled = connection != null && !creating, onClick = {
                val selected = connection ?: return@TextButton
                creating = true
                scope.launch {
                    try {
                        val target = NotificationTarget(selected.id, targetBot, "", selected.name + " · " + targetBot + " · 通知助手")
                        val id = repository.createNotificationSession(target) ?: error("暂不支持")
                        configure(config.copy(target = target.copy(sessionId = id)))
                        choosing = false
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { error = "创建失败，请检查连接是否可用、Bot 是否已开通访问" }
                    finally { creating = false }
                }
            }) { Text(if (creating) "正在创建…" else "新建通知助手会话") }
            sessions.filter { !it.id.startsWith("draft") && !it.readOnly }.forEach { session ->
                TextButton(enabled = connection != null && !creating, onClick = {
                    val selected = connection ?: return@TextButton
                    configure(config.copy(target = NotificationTarget(selected.id, targetBot, session.id,
                        selected.name + " · " + targetBot + " · " + session.title)))
                    choosing = false
                }) { Text(session.title) }
            }
        } }, confirmButton = { TextButton(onClick = { choosing = false }) { Text("关闭") } })
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("未完成") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } }) }
}

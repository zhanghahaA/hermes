package com.hermes.mobile.ui.admin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.model.GatewayStatus
import com.hermes.mobile.data.model.McpServer
import com.hermes.mobile.data.model.MemoryBook
import com.hermes.mobile.data.model.MemoryTarget
import com.hermes.mobile.data.remote.AdminApi
import com.hermes.mobile.data.remote.AdminException
import com.hermes.mobile.ui.components.EmptyState
import com.hermes.mobile.ui.components.TagLabel
import com.hermes.mobile.ui.theme.CodeTextStyle
import com.hermes.mobile.ui.theme.LocalHermesColors
import kotlinx.coroutines.launch

/** “能力 → 更多”里的几个管理页面。 */
enum class AdminPage { Memory, Mcp, Gateway, Config, Screen }

/** 按页面打开。 */
@Composable
fun AdminScreen(repository: HermesRepository, page: AdminPage, onBack: () -> Unit) = when (page) {
    AdminPage.Memory -> MemoryScreen(repository, onBack)
    AdminPage.Mcp -> McpScreen(repository, onBack)
    AdminPage.Gateway -> GatewayScreen(repository, onBack)
    AdminPage.Config -> ConfigEditorScreen(repository, onBack)
    AdminPage.Screen -> BotScreenScreen(repository, onBack)
}

/** 管理页面共用的外框：返回按钮 + 标题 + 右上角操作。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdminPage(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回") } },
                title = { Text(title) },
                actions = { actions() },
            )
        },
    ) { padding -> content(padding) }
}

/** 一次读取的状态。 */
internal sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

internal suspend fun <T> attempt(block: suspend () -> T): Load<T> = try {
    Load.Ready(block())
} catch (e: AdminException) {
    Load.Failed(e.message ?: "出错了")
}

/** 没配 SSH 时的说明。 */
@Composable
internal fun NeedsSsh(padding: PaddingValues) {
    Box(Modifier.padding(padding)) {
        EmptyState(
            Icons.Rounded.Terminal,
            "需要 SSH",
            "这个功能要在服务器上读写 Hermes 的文件，需要给当前连接配上 SSH。" +
                "本机 Termux：在 Termux 里运行 sshd（端口 8022），然后在连接设置里填上 SSH 用户名和密钥或密码。",
        )
    }
}

@Composable
internal fun <T> LoadBody(state: Load<T>, padding: PaddingValues, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (state) {
        Load.Loading -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is Load.Failed -> Box(Modifier.padding(padding)) {
            EmptyState(Icons.Rounded.ErrorOutline, "没读到", state.message) { Button(onClick = onRetry) { Text("重试") } }
        }
        is Load.Ready -> content(state.value)
    }
}

// ---- 记忆 ----

@Composable
fun MemoryScreen(repository: HermesRepository, onBack: () -> Unit) {
    val admin = remember { repository.admin() }
    AdminPage("记忆", onBack) { padding ->
        if (admin == null) return@AdminPage NeedsSsh(padding)
        MemoryContent(admin, padding)
    }
}

@Composable
internal fun MemoryContent(admin: AdminApi, padding: PaddingValues) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<Load<List<MemoryBook>>>(Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reload) { state = attempt { admin.memory() } }

    LoadBody(state, padding, onRetry = { state = Load.Loading; reload++ }) { books ->
        val book = books[tab]
        fun save(entries: List<String>) {
            saving = true
            scope.launch {
                try {
                    admin.saveMemory(book.target, entries)
                    state = Load.Ready(books.map { if (it.target == book.target) it.copy(entries = entries.map(String::trim).filter(String::isNotEmpty)) else it })
                    editing = null
                } catch (e: AdminException) {
                    error = e.message
                } finally {
                    saving = false
                }
            }
        }
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                books.forEachIndexed { i, b -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text("${b.target.label} · ${b.entries.size}") }) }
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                item(key = "usage") { UsageCard(book) }
                if (book.entries.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            if (book.target == MemoryTarget.User) "Hermes 还没记下关于你的事。聊天时它会自己记，也可以在这里直接添加。"
                            else "Hermes 还没有笔记。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                itemsIndexed(book.entries, key = { i, e -> "$i-${e.hashCode()}" }) { index, entry ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
                            Text(entry, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(top = 8.dp))
                            IconButton(onClick = { editing = index to entry }, enabled = !saving) { Icon(Icons.Rounded.Edit, contentDescription = "编辑") }
                            IconButton(onClick = { save(book.entries.filterIndexed { i, _ -> i != index }) }, enabled = !saving) {
                                Icon(Icons.Rounded.Delete, contentDescription = "删除")
                            }
                        }
                    }
                }
                item(key = "add") {
                    TextButton(onClick = { editing = -1 to "" }, enabled = !saving) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("添加一条")
                    }
                }
            }
        }
        editing?.let { (index, text) ->
            EntryDialog(
                title = if (index < 0) "添加到${book.target.label}" else "编辑",
                initial = text,
                saving = saving,
                onSave = { value -> save(if (index < 0) book.entries + value else book.entries.mapIndexed { i, e -> if (i == index) value else e }) },
                onDismiss = { editing = null },
            )
        }
    }
    error?.let { message -> ErrorDialog(message) { error = null } }
}

@Composable
private fun UsageCard(book: MemoryBook) {
    val fraction = (book.used.toFloat() / book.charLimit).coerceIn(0f, 1f)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (book.target == MemoryTarget.User) "Hermes 记住的关于你的事，每次对话都会带上" else "Hermes 给自己记的笔记（环境、习惯、做过的事）",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (!book.enabled) TagLabel("已关闭")
            }
            LinearProgressIndicator(
                progress = { fraction },
                color = if (fraction > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${book.used} / ${book.charLimit} 字", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EntryDialog(title: String, initial: String, saving: Boolean, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 3, maxLines = 10, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank() && !saving) { Text(if (saving) "保存中…" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ErrorDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.ErrorOutline, null) },
        title = { Text("没成功") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

// ---- MCP ----

@Composable
fun McpScreen(repository: HermesRepository, onBack: () -> Unit) {
    val admin = remember { repository.admin() }
    var adding by remember { mutableStateOf(false) }
    AdminPage(
        "MCP 服务器",
        onBack,
        actions = { if (admin != null) IconButton(onClick = { adding = true }) { Icon(Icons.Rounded.Add, contentDescription = "添加") } },
    ) { padding ->
        if (admin == null) return@AdminPage NeedsSsh(padding)
        McpContent(admin, padding, adding, onAddingChange = { adding = it })
    }
}

@Composable
internal fun McpContent(admin: AdminApi, padding: PaddingValues, adding: Boolean = false, onAddingChange: (Boolean) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<Load<List<McpServer>>>(Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<McpServer?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(reload) { state = attempt { admin.mcpServers() } }

    fun act(name: String, block: suspend () -> Unit) {
        busy = name
        scope.launch {
            try {
                block()
                state = attempt { admin.mcpServers() }
            } catch (e: AdminException) {
                error = e.message
            } finally {
                busy = null
            }
        }
    }

    LoadBody(state, padding, onRetry = { state = Load.Loading; reload++ }) { servers ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "intro") {
                Text(
                    "MCP 服务器给 Hermes 加上额外的工具（读写文件、查数据库、控制别的软件……）。改动写进 config.yaml，Hermes 会自动重新连接。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (servers.isEmpty()) {
                item(key = "empty") {
                    Text("还没有 MCP 服务器，点右上角 + 添加。", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 24.dp))
                }
            }
            items(servers, key = { it.name }) { server ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(server.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    server.summary,
                                    style = CodeTextStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (busy == server.name) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Switch(
                                    checked = server.enabled,
                                    onCheckedChange = { on -> act(server.name) { admin.setMcpEnabled(server.name, on) } },
                                    enabled = busy == null,
                                )
                            }
                        }
                        Row {
                            TextButton(onClick = {
                                act(server.name) { testResult = server.name to admin.testMcpServer(server.name) }
                            }, enabled = busy == null) {
                                Icon(Icons.Rounded.NetworkCheck, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("测试")
                            }
                            TextButton(onClick = { editing = server }, enabled = busy == null) { Text("编辑") }
                            TextButton(onClick = { act(server.name) { admin.removeMcpServer(server.name) } }, enabled = busy == null) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding || editing != null) {
        McpDialog(
            initial = editing,
            saving = busy != null,
            onSave = { server ->
                val previous = editing?.name
                act(server.name) {
                    admin.saveMcpServer(server, previous)
                    editing = null
                    onAddingChange(false)
                }
            },
            onDismiss = { editing = null; onAddingChange(false) },
        )
    }
    testResult?.let { (name, output) ->
        AlertDialog(
            onDismissRequest = { testResult = null },
            title = { Text("测试 $name") },
            text = {
                Text(
                    output.ifBlank { "（没有输出）" },
                    style = CodeTextStyle,
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { testResult = null }) { Text("好") } },
        )
    }
    error?.let { message -> ErrorDialog(message) { error = null } }
}

@Composable
private fun McpDialog(initial: McpServer?, saving: Boolean, onSave: (McpServer) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var remote by rememberSaveable { mutableStateOf(initial?.url != null || initial == null) }
    var url by rememberSaveable { mutableStateOf(initial?.url.orEmpty()) }
    var command by rememberSaveable { mutableStateOf(initial?.command.orEmpty()) }
    var args by rememberSaveable { mutableStateOf(initial?.args.orEmpty().joinToString("\n")) }
    val valid = name.matches(Regex("[A-Za-z0-9_-]+")) && (if (remote) url.isNotBlank() else command.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加 MCP 服务器" else "编辑 ${initial.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it.trim() }, label = { Text("名字（英文）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = remote, onClick = { remote = true }, label = { Text("网址") })
                    FilterChip(selected = !remote, onClick = { remote = false }, label = { Text("本机命令") })
                }
                if (remote) {
                    OutlinedTextField(url, { url = it }, label = { Text("地址") }, placeholder = { Text("https://…/mcp") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(command, { command = it }, label = { Text("命令") }, placeholder = { Text("npx") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        args,
                        { args = it },
                        label = { Text("参数（每行一个）") },
                        placeholder = { Text("-y\n@modelcontextprotocol/server-filesystem\n/sdcard") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        McpServer(
                            name = name,
                            url = url.trim().takeIf { remote },
                            command = command.trim().takeIf { !remote },
                            args = if (remote) emptyList() else args.lines().map { it.trim() }.filter { it.isNotEmpty() },
                            enabled = initial?.enabled ?: true,
                        ),
                    )
                },
                enabled = valid && !saving,
            ) { Text(if (saving) "保存中…" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---- Gateway ----

@Composable
fun GatewayScreen(repository: HermesRepository, onBack: () -> Unit) {
    val admin = remember { repository.admin() }
    var reload by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<Load<GatewayStatus?>>(Load.Loading) }
    var logs by remember { mutableStateOf<Load<String>?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reload) { state = Load.Ready(repository.gatewayStatus()) }
    AdminPage(
        "Gateway",
        onBack,
        actions = { IconButton(onClick = { state = Load.Loading; reload++ }) { Icon(Icons.Rounded.Refresh, contentDescription = "刷新") } },
    ) { padding ->
        LoadBody(state, padding, onRetry = { reload++ }) { status ->
            GatewayContent(
                status = status,
                padding = padding,
                logs = logs,
                canReadLogs = admin != null,
                onLoadLogs = {
                    logs = Load.Loading
                    scope.launch { logs = attempt { admin!!.logs(200) } }
                },
            )
        }
    }
}

@Composable
internal fun GatewayContent(status: GatewayStatus?, padding: PaddingValues, logs: Load<String>?, canReadLogs: Boolean, onLoadLogs: () -> Unit) {
    val colors = LocalHermesColors.current
    Column(
        Modifier
            .padding(padding)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (status == null) {
            Text("这台 Hermes 没有提供详细状态（/health/detailed）。", style = MaterialTheme.typography.bodyLarge)
        } else {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (status.ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Row(Modifier.padding(20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (status.ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                        null,
                        tint = if (status.ok) colors.online else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(if (status.ok) "运行正常" else "有问题", style = MaterialTheme.typography.titleLarge)
                        Text(
                            listOfNotNull(
                                status.state?.let { "状态 $it" },
                                status.activeAgents?.let { "正在处理 $it 个" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            if (status.requestsToday != null || status.tokensToday != null) {
                SectionCard("今天") {
                    Row(Modifier.fillMaxWidth()) {
                        Metric("请求", status.requestsToday?.toString() ?: "—", Modifier.weight(1f))
                        Metric("消息", status.messagesToday?.toString() ?: "—", Modifier.weight(1f))
                        Metric("Token", status.tokensToday?.let(::compactNumber) ?: "—", Modifier.weight(1f))
                    }
                }
            }
            if (status.platforms.isNotEmpty()) {
                SectionCard("接入的平台") {
                    status.platforms.forEach { p ->
                        StatusRow(platformName(p.name), p.state == "connected", p.error ?: stateLabel(p.state))
                    }
                }
            }
            if (status.checks.isNotEmpty()) {
                SectionCard("自检") {
                    status.checks.forEach { c -> StatusRow(checkName(c.name), c.ok, c.detail) }
                }
            }
        }
        SectionCard("日志") {
            when {
                !canReadLogs -> Text("给连接配上 SSH 后可以在这里看最近的日志。", style = MaterialTheme.typography.bodyMedium)
                logs == null -> Button(onClick = onLoadLogs) { Text("读取最近 200 行") }
                logs == Load.Loading -> CircularProgressIndicator(Modifier.size(24.dp))
                logs is Load.Failed -> Text(logs.message, color = MaterialTheme.colorScheme.error)
                logs is Load.Ready -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp)
                            .verticalScroll(rememberScrollState(Int.MAX_VALUE))
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        Text(logs.value.ifBlank { "（日志是空的）" }, style = CodeTextStyle, softWrap = false)
                    }
                    TextButton(onClick = onLoadLogs) { Text("刷新日志") }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusRow(name: String, ok: Boolean, detail: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
            null,
            tint = if (ok) LocalHermesColors.current.online else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun compactNumber(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 10_000 -> "%.1fk".format(n / 1000.0)
    else -> n.toString()
}

private fun platformName(name: String) = when (name) {
    "api_server" -> "API Server（这个 App）"
    "telegram" -> "Telegram"
    "discord" -> "Discord"
    "slack" -> "Slack"
    "whatsapp" -> "WhatsApp"
    "webhook" -> "Webhook"
    else -> name
}

private fun stateLabel(state: String) = when (state) {
    "connected" -> "已连接"
    "connecting" -> "连接中"
    "retrying" -> "重试中"
    "disconnected" -> "已断开"
    else -> state
}

private fun checkName(name: String) = when (name) {
    "state_db" -> "数据库"
    "session_store" -> "会话存储"
    "config" -> "配置文件"
    "model" -> "模型"
    "disk" -> "磁盘"
    "gateway" -> "Gateway"
    "background_queues" -> "后台队列"
    else -> name
}

// ---- 配置文件 ----

@Composable
fun ConfigEditorScreen(repository: HermesRepository, onBack: () -> Unit) {
    val admin = remember { repository.admin() }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<Load<String>>(Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    var original by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    LaunchedEffect(reload) {
        if (admin == null) return@LaunchedEffect
        state = attempt { admin.readConfig() }
        (state as? Load.Ready)?.value?.let { text = it; original = it }
    }
    AdminPage(
        "config.yaml",
        onBack,
        actions = {
            if (admin != null) {
                Button(
                    onClick = {
                        saving = true
                        scope.launch {
                            message = try {
                                admin.saveConfig(text)
                                original = text
                                true to "已保存。原文件备份在 config.yaml.mobile-backup，Hermes 下一条消息起用新设置。"
                            } catch (e: AdminException) {
                                false to (e.message ?: "保存失败")
                            } finally {
                                saving = false
                            }
                        }
                    },
                    enabled = !saving && text != original && state is Load.Ready,
                    modifier = Modifier.padding(end = 8.dp),
                ) { Text(if (saving) "检查中…" else "保存") }
            }
        },
    ) { padding ->
        if (admin == null) return@AdminPage NeedsSsh(padding)
        LoadBody(state, padding, onRetry = { state = Load.Loading; reload++ }) {
            ConfigEditorContent(text, { text = it }, padding, message)
        }
    }
}

@Composable
internal fun ConfigEditorContent(text: String, onChange: (String) -> Unit, padding: PaddingValues, message: Pair<Boolean, String>?) {
    Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
        val (ok, note) = message ?: (true to "保存前会先让 Hermes 检查格式，有错就不会覆盖原文件。API Key 这类密钥在 .env 里，这里改不了。")
        Text(
            note,
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .horizontalScroll(rememberScrollState()),
        ) {
            BasicTextField(
                value = text,
                onValueChange = onChange,
                textStyle = CodeTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
            )
        }
    }
}

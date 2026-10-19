package com.hermes.mobile.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DesktopAccessDisabled
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.model.BotScreenStatus
import com.hermes.mobile.data.model.ScreenLease
import com.hermes.mobile.data.remote.AdminApi
import com.hermes.mobile.data.remote.AdminException
import com.hermes.mobile.data.screen.ScreenConnection
import com.hermes.mobile.data.screen.ScreenPageHost
import com.hermes.mobile.platform.ScreenWebView
import com.hermes.mobile.ui.LocalShowMessage
import com.hermes.mobile.ui.components.EmptyState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Bot 屏幕：Hermes 在服务器上给 bot 开的桌面（computer_use 和浏览器在上面操作）。
 * 和 Hermes 桌面版一样：默认只看，点“接管”后你的点按和键盘才生效（bot 的屏幕工具这时会被拒绝），用完“交还”。
 * 画面用打包进 App 的 noVNC 显示，数据经 SSH 直接接到 Hermes 的 RFB socket。
 */
@Composable
fun BotScreenScreen(repository: HermesRepository, onBack: () -> Unit) {
    val admin = remember { repository.admin() }
    var reload by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<Load<BotScreenStatus>>(Load.Loading) }
    var starting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reload) { if (admin != null) state = attempt { admin.screenStatus() } }

    AdminPage(
        "Bot 屏幕",
        onBack,
        actions = {
            if ((state as? Load.Ready)?.value?.running != true) {
                IconButton(onClick = { state = Load.Loading; reload++ }) { Icon(Icons.Rounded.Refresh, contentDescription = "刷新") }
            }
        },
    ) { padding ->
        if (admin == null) return@AdminPage NeedsSsh(padding)
        LoadBody(state, padding, onRetry = { state = Load.Loading; reload++ }) { status ->
            when {
                !status.supported -> Box(Modifier.padding(padding)) {
                    EmptyState(
                        Icons.Rounded.DesktopAccessDisabled,
                        "这台主机没有 Bot 屏幕",
                        "Bot 屏幕只在 Linux 主机（服务器、云主机）上有；macOS 和 Windows 本来就有真屏幕。",
                    )
                }
                !status.installed -> InstallHint(status, padding)
                !status.running -> Box(Modifier.padding(padding)) {
                    EmptyState(
                        Icons.Rounded.DesktopWindows,
                        "屏幕没开",
                        status.blocker ?: "开了之后，bot 的 computer_use 和浏览器会在这块屏幕上操作，你可以随时来看、接管。没人用 30 分钟会自动关。",
                    ) {
                        Button(
                            enabled = !starting && status.blocker == null,
                            onClick = {
                                starting = true
                                scope.launch {
                                    state = attempt { admin.startScreen() }
                                    starting = false
                                }
                            },
                        ) { Text(if (starting) "正在启动…" else "启动屏幕") }
                    }
                }
                else -> LiveScreen(admin, status, padding)
            }
        }
    }
}

@Composable
private fun InstallHint(status: BotScreenStatus, padding: PaddingValues) {
    Column(
        Modifier
            .padding(padding)
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("主机上还没装 Bot 屏幕要用的软件", style = MaterialTheme.typography.titleMedium)
        Text(
            "在那台主机上用 root 运行下面这条命令（或者在电脑版 Hermes 的 Screen 里点“Install on host”），装好后回来刷新：",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            SelectionContainer {
                Text(
                    status.installCommand ?: "hermes computer-use screen install",
                    style = com.hermes.mobile.ui.theme.CodeTextStyle,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

/** 离开页面时把控制权交还给 bot（转屏会重建页面，所以稍等一下，页面马上回来就不交还）。 */
private object ScreenLeases {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, Job>()

    fun handBackSoon(admin: AdminApi, viewerId: String) {
        pending[viewerId] = scope.launch {
            delay(1500)
            runCatching { admin.handBackScreen(viewerId) }
            pending.remove(viewerId)
        }
    }

    fun keep(viewerId: String) {
        pending.remove(viewerId)?.cancel()
    }
}

@Composable
private fun LiveScreen(admin: AdminApi, initial: BotScreenStatus, padding: PaddingValues) {
    val showMessage = LocalShowMessage.current
    val scope = rememberCoroutineScope()
    // 每次打开页面一个查看者 id（转屏不变）；Hermes 只公开它的哈希，用来认出“是不是我接管的”
    val viewerId = rememberSaveable { "hermes-mobile-" + UUID.randomUUID() }
    var lease by remember { mutableStateOf(initial.lease) }
    val held = lease.heldBy(viewerId)
    var fit by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var pageReady by remember { mutableStateOf(false) }
    var attemptNo by remember { mutableIntStateOf(0) }
    var connection by remember { mutableStateOf<ScreenConnection?>(null) }
    var openError by remember { mutableStateOf<String?>(null) }
    val host = remember { ScreenPageHost().apply { onEvent = { type, _ -> if (type == "ready") pageReady = true } } }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }

    // 页面好了就经 SSH 开画面通道；“重新连接”再开一次
    LaunchedEffect(pageReady, attemptNo) {
        if (!pageReady) return@LaunchedEffect
        openError = null
        val stream = try {
            admin.openScreen()
        } catch (e: AdminException) {
            openError = e.message
            return@LaunchedEffect
        }
        val c = ScreenConnection(stream, host, scope)
        c.extraEvents = { type, _ ->
            if (type == "mods-cleared") {
                ctrl = false
                alt = false
            }
        }
        c.start(fit = fit, viewOnly = !lease.heldBy(viewerId))
        connection = c
    }
    DisposableEffect(Unit) { onDispose { connection?.close() } }

    // 接管状态：自己接管的随时同步到页面；定时看看别人（电脑端）有没有抢走
    LaunchedEffect(held, connection) { host.call("hm.setViewOnly(${!held})") }
    LaunchedEffect(fit) { host.call("hm.setFit($fit)") }
    LaunchedEffect(Unit) {
        while (true) {
            delay(4000)
            val now = runCatching { admin.screenStatus() }.getOrNull() ?: continue
            val wasMine = lease.heldBy(viewerId)
            lease = now.lease
            if (wasMine && !now.lease.heldBy(viewerId)) {
                showMessage(if (now.lease.humanHolds) "别处（比如电脑版）接管了屏幕，你回到了只看模式" else "控制权已回到 bot")
            }
        }
    }
    val heldNow by rememberUpdatedState(held)
    DisposableEffect(viewerId) {
        ScreenLeases.keep(viewerId)
        onDispose { if (heldNow) ScreenLeases.handBackSoon(admin, viewerId) }
    }

    fun leaseCall(block: suspend () -> ScreenLease) {
        busy = true
        scope.launch {
            try {
                lease = block()
            } catch (e: AdminException) {
                showMessage(e.message ?: "操作失败")
            } finally {
                busy = false
            }
        }
    }

    val connState = connection?.state?.collectAsState()?.value
    Column(
        Modifier
            .padding(padding)
            .fillMaxSize()
            .imePadding(),
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF1B1B1F)),
        ) {
            ScreenWebView(host, Modifier.fillMaxSize())
            val overlay = openError ?: (connState as? ScreenConnection.State.Closed)?.reason
            when {
                overlay != null -> ScreenOverlay(overlay) {
                    connection?.close()
                    connection = null
                    attemptNo++
                }
                connState == null || connState == ScreenConnection.State.Connecting ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }
            // 右上角：适应屏幕 / 原始大小（原始大小时单指拖动画面）
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Surface(shape = MaterialTheme.shapes.small, color = Color.Black.copy(alpha = 0.45f), contentColor = Color.White) {
                    IconButton(onClick = { fit = !fit }) {
                        Icon(if (fit) Icons.Rounded.ZoomIn else Icons.Rounded.FitScreen, contentDescription = if (fit) "原始大小" else "适应屏幕")
                    }
                }
            }
        }
        ControlBar(
            lease = lease,
            held = held,
            busy = busy,
            ctrl = ctrl,
            alt = alt,
            onTake = { leaseCall { admin.takeScreen(viewerId) } },
            onHandBack = { leaseCall { admin.handBackScreen(viewerId) } },
            onForceHandBack = { leaseCall { admin.handBackScreen(null) } },
            onCtrl = { ctrl = it; host.call("hm.setMod('ctrl',$it)") },
            onAlt = { alt = it; host.call("hm.setMod('alt',$it)") },
            onKey = { keysym -> host.call("hm.key($keysym)") },
            onType = { text -> host.call("hm.type(" + ScreenConnection.jsString(text) + ")") },
        )
    }
}

@Composable
private fun ScreenOverlay(reason: String, onReconnect: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(reason, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.size(12.dp))
        FilledTonalButton(onClick = onReconnect) { Text("重新连接") }
    }
}

/** 常用但手机键盘上没有的键（X 的 keysym）。 */
private val SpecialKeys = listOf(
    "Esc" to 0xff1b, "Tab" to 0xff09, "←" to 0xff51, "↑" to 0xff52, "↓" to 0xff54, "→" to 0xff53,
    "回车" to 0xff0d, "退格" to 0xff08, "Del" to 0xffff, "Home" to 0xff50, "End" to 0xff57,
)

@Composable
internal fun ControlBar(
    lease: ScreenLease,
    held: Boolean,
    busy: Boolean,
    ctrl: Boolean,
    alt: Boolean,
    onTake: () -> Unit,
    onHandBack: () -> Unit,
    onForceHandBack: () -> Unit,
    onCtrl: (Boolean) -> Unit,
    onAlt: (Boolean) -> Unit,
    onKey: (Int) -> Unit,
    onType: (String) -> Unit,
) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var text by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.PanTool,
                    contentDescription = null,
                    tint = if (held) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            held -> "你在控制（bot 暂停操作屏幕）"
                            lease.humanHolds -> "别处有人在控制"
                            else -> "bot 在控制，你在看"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (lease.reason.isNotBlank()) {
                        Text(lease.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                }
                if (held) {
                    Button(onClick = onHandBack, enabled = !busy) { Text("交还") }
                } else {
                    Button(onClick = onTake, enabled = !busy) { Text("接管") }
                }
                if (lease.humanHolds && !held) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "更多") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("强制交还给 bot") },
                                onClick = { menu = false; onForceHandBack() },
                            )
                        }
                    }
                }
            }
            if (held) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(selected = ctrl, onClick = { onCtrl(!ctrl) }, label = { Text("Ctrl") })
                    FilterChip(selected = alt, onClick = { onAlt(!alt) }, label = { Text("Alt") })
                    SpecialKeys.forEach { (label, keysym) ->
                        AssistChip(onClick = { onKey(keysym) }, label = { Text(label) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("要打到屏幕上的字") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (text.isNotEmpty()) onType(text)
                            text = ""
                        }),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        if (text.isNotEmpty()) onType(text)
                        text = ""
                    }) { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "打出去") }
                    OutlinedButton(
                        onClick = { clipboard.getText()?.text?.takeIf { it.isNotEmpty() }?.let(onType) },
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("粘贴")
                    }
                }
            }
        }
    }
}

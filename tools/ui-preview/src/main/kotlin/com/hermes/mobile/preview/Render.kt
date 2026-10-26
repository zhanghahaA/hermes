package com.hermes.mobile.preview

import androidx.compose.foundation.background
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.fake.FakeHermesRepository
import com.hermes.mobile.data.fake.SampleData
import com.hermes.mobile.data.model.ConnectionType
import com.hermes.mobile.data.model.ThemeMode
import com.hermes.mobile.ui.capabilities.CapabilitiesScreen
import com.hermes.mobile.ui.chat.AttachSheetContent
import com.hermes.mobile.ui.chat.ChatScreen
import com.hermes.mobile.ui.chat.ModelPickerContent
import com.hermes.mobile.ui.chats.ActionSheetContent
import com.hermes.mobile.ui.chats.ConnectionSwitcherContent
import com.hermes.mobile.ui.chats.SheetAction
import com.hermes.mobile.ui.jobs.JobEditorScreen
import com.hermes.mobile.ui.admin.AdminPage
import com.hermes.mobile.ui.admin.AdminScreen
import com.hermes.mobile.ui.capabilities.SkillSheetContent
import com.hermes.mobile.ui.main.MainScreen
import com.hermes.mobile.ui.main.MainTab
import com.hermes.mobile.ui.navigation.topPage
import com.hermes.mobile.ui.navigation.underPage
import com.hermes.mobile.ui.settings.ConnectionEditorScreen
import com.hermes.mobile.ui.theme.HermesTheme
import com.hermes.mobile.ui.welcome.WelcomeScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.PushPin

// 典型 Android 手机：412 x 892 dp，2.5 倍密度
private const val WIDTH_DP = 412
private const val HEIGHT_DP = 892
private const val DENSITY = 2.5f

private class Scene(
    val name: String,
    val dark: Boolean = false,
    /** 渲染前对假数据做的操作，返回值传给 content（比如新建会话的 id）。 */
    val prepare: (FakeHermesRepository) -> String? = { null },
    /** prepare 之后等待的真实时间，让假仓库里的 delay 走完。 */
    val waitMs: Long = 0,
    /** 假仓库的回复延迟；截“正在输入”时调大，避免渲染期间回复已经到达。 */
    val replyDelayMs: Long = 1200,
    /** 动画场景在尚未结束时截帧；普通场景默认等到稳定。 */
    val frames: Int = 60,
    val content: @Composable (HermesRepository, String?) -> Unit,
)

private fun mainScreen(repo: HermesRepository, tab: MainTab) = @Composable {
    MainScreen(repo, onOpenChat = {}, onEditConnection = {}, onEditJob = {}, onOpenWelcome = {}, initialTab = tab)
}

private val scenes = listOf(
    Scene("51-runtime-missing") { _, _ -> RuntimePreview(false) },
    Scene("51b-runtime-ready") { _, _ -> RuntimePreview(true) },
    Scene("51c-runtime-dark", dark = true) { _, _ -> RuntimePreview(true) },
    Scene("41-streaming-keeps-text") { repo, _ -> StreamingPreview(repo, process = false) },
    Scene("41b-work-process") { repo, _ -> StreamingPreview(repo, process = true) },
    Scene("41c-work-process-dark", dark = true) { repo, _ -> StreamingPreview(repo, process = true) },
    Scene("41d-streaming-completed") { repo, _ -> StreamingPreview(repo, process = false, completed = true) },
    Scene("41e-process-completed") { repo, _ -> StreamingPreview(repo, process = true, completed = true) },
    Scene("42-dock-glass") { repo, _ -> mainScreen(repo, MainTab.Chats)() },
    Scene("42b-dock-glass-dark", dark = true) { repo, _ -> mainScreen(repo, MainTab.Chats)() },
    Scene("43-process-settings") { repo, _ -> mainScreen(repo, MainTab.Settings)() },
    Scene("43b-process-settings-dark", dark = true) { repo, _ -> mainScreen(repo, MainTab.Settings)() },
    Scene("01-chats") { repo, _ -> mainScreen(repo, MainTab.Chats)() },
    Scene("02-chats-dark", dark = true) { repo, _ -> mainScreen(repo, MainTab.Chats)() },
    Scene("03-chat") { repo, _ -> ChatScreen(repo, "s-1", onBack = {}, onOpenChat = {}) },
    Scene("04-chat-dark", dark = true) { repo, _ -> ChatScreen(repo, "s-1", onBack = {}, onOpenChat = {}) },
    Scene(
        "05-chat-typing",
        prepare = { it.sendMessage("s-2", "第二天下午有没有安静一点的咖啡馆？", emptyList()); null },
        waitMs = 600,
        replyDelayMs = 60_000,
    ) { repo, _ -> ChatScreen(repo, "s-2", onBack = {}, onOpenChat = {}) },
    Scene(
        "06-chat-approval",
        prepare = { it.sendMessage("s-3", "脚本没问题的话，帮我重启一下 hermes gateway", emptyList()); null },
        waitMs = 2200,
    ) { repo, _ -> ChatScreen(repo, "s-3", onBack = {}, onOpenChat = {}) },
    Scene("07-chat-new", prepare = { it.createSession()!! }) { repo, id -> ChatScreen(repo, id!!, onBack = {}, onOpenChat = {}) },
    Scene("08-chat-readonly") { repo, _ -> ChatScreen(repo, "s-4", onBack = {}, onOpenChat = {}) },
    Scene("09-sheet-model") { repo, _ ->
        SheetOver({ ChatScreen(repo, "s-1", onBack = {}, onOpenChat = {}) }) {
            ModelPickerContent(SampleData.models, isSelected = { it.name == "Claude Sonnet" }, onSelect = {})
        }
    },
    Scene("10-sheet-attach") { repo, _ ->
        SheetOver({ ChatScreen(repo, "s-2", onBack = {}, onOpenChat = {}) }) { AttachSheetContent({}, {}) }
    },
    Scene("11-sheet-message-actions") { repo, _ ->
        SheetOver({ ChatScreen(repo, "s-2", onBack = {}, onOpenChat = {}) }) {
            ActionSheetContent(
                null,
                listOf(
                    SheetAction("复制", Icons.Rounded.ContentCopy, {}),
                    SheetAction("分叉这个会话", Icons.Rounded.AccountTree, {}),
                    SheetAction("删除", Icons.Rounded.Delete, {}, destructive = true),
                ),
                {},
            )
        }
    },
    Scene("12-sheet-connections") { repo, _ ->
        SheetOver({ mainScreen(repo, MainTab.Chats)() }) {
            ConnectionSwitcherContent(SampleData.connections, "c-home", repo.connectionState.value, {}, {})
        }
    },
    Scene("13-jobs") { repo, _ -> mainScreen(repo, MainTab.Jobs)() },
    Scene("37-bots") { repo, _ -> mainScreen(repo, MainTab.Bots)() },
    Scene("38-bot-new") { repo, _ -> com.hermes.mobile.ui.bots.BotEditorScreen(repo, null, onClose = {}) },
    Scene("38b-bot-edit", waitMs = 300) { repo, _ -> com.hermes.mobile.ui.bots.BotEditorScreen(repo, "coder", onClose = {}) },
    Scene("39-group-chat", waitMs = 500) { repo, _ -> com.hermes.mobile.ui.bots.GroupChatScreen(repo, "r-trip", onBack = {}) },
    Scene("14-job-editor") { repo, _ -> JobEditorScreen(repo, "j-brief", onClose = {}) },
    Scene("14b-job-editor-failed") { repo, _ -> JobEditorScreen(repo, "j-backup", onClose = {}) },
    Scene("14c-job-new") { repo, _ -> JobEditorScreen(repo, null, onClose = {}) },
    Scene("15-capabilities-models") { repo, _ -> mainScreen(repo, MainTab.Capabilities)() },
    Scene("16-capabilities-skills") { repo, _ -> CapabilitiesScreen(repo, initialTab = 1) },
    Scene("16b-capabilities-toolsets") { repo, _ -> CapabilitiesScreen(repo, initialTab = 2) },
    Scene("16d-capabilities-more") { repo, _ -> CapabilitiesScreen(repo, initialTab = 3) },
    Scene("24-admin-memory", waitMs = 300) { repo, _ -> AdminScreen(repo, AdminPage.Memory, onBack = {}) },
    Scene("25-admin-mcp", waitMs = 300) { repo, _ -> AdminScreen(repo, AdminPage.Mcp, onBack = {}) },
    Scene("26-admin-gateway", waitMs = 300) { repo, _ -> AdminScreen(repo, AdminPage.Gateway, onBack = {}) },
    Scene("27-admin-config", waitMs = 300) { repo, _ -> AdminScreen(repo, AdminPage.Config, onBack = {}) },
    Scene("16c-sheet-skill") { repo, _ ->
        SheetOver({ CapabilitiesScreen(repo, initialTab = 1) }) { SkillSheetContent(SampleData.skills[3], onUse = {}) }
    },
    Scene("17-settings") { repo, _ -> mainScreen(repo, MainTab.Settings)() },
    Scene("40-notification-assistant") { repo, _ -> NotificationPreview(repo) },
    Scene("40b-notification-assistant-dark", dark = true) { repo, _ -> NotificationPreview(repo) },
    Scene("40c-notification-enter", frames = 8) { repo, _ -> NotificationTransitionPreview(repo, exiting = false) },
    Scene("40d-notification-exit", frames = 8) { repo, _ -> NotificationTransitionPreview(repo, exiting = true) },
    Scene("18-settings-dark", dark = true) { repo, _ -> mainScreen(repo, MainTab.Settings)() },
    Scene("19-connection-ssh") { repo, _ -> ConnectionEditorScreen(repo, "c-ssh", onClose = {}) },
    Scene("20-connection-termux") { repo, _ -> ConnectionEditorScreen(repo, null, ConnectionType.Local.name, onClose = {}) },
    Scene("29-glass-test") { _, _ -> GlassTest() },
    Scene("30-chats-swipe") { repo, _ -> SwipeStill(repo) },
    Scene("35-bot-screen", waitMs = 300) { repo, _ -> com.hermes.mobile.ui.admin.BotScreenScreen(repo, onBack = {}) },
    Scene("36-bot-screen-control") { _, _ ->
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                com.hermes.mobile.platform.ScreenWebView(remember { com.hermes.mobile.data.screen.ScreenPageHost() }, Modifier.fillMaxSize())
            }
            com.hermes.mobile.ui.admin.ControlBar(
                lease = com.hermes.mobile.data.model.ScreenLease(true, "x", "请帮我登录 GitHub（需要验证码）"), held = true, busy = false,
                ctrl = true, alt = false, onTake = {}, onHandBack = {}, onForceHandBack = {}, onCtrl = {}, onAlt = {}, onKey = {}, onType = {},
            )
        }
    },
    Scene("33-markdown") { _, _ ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp)) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                com.hermes.mobile.ui.components.MarkdownText(MarkdownSample, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    },
    Scene("33b-markdown-weather") { _, _ ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp)) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                com.hermes.mobile.ui.components.MarkdownText(WeatherSample, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    },
    Scene("33c-markdown-stress") { _, _ ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp).verticalScroll(rememberScrollState())) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                com.hermes.mobile.ui.components.MarkdownText(StressSample, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    },
    Scene("34-md-file-preview") { _, _ ->
        com.hermes.mobile.ui.chat.TextFilePreview("周报.md", MarkdownSample, onDismiss = {}, onShare = {})
    },
    Scene("31-sheet-move-project") { repo, _ ->
        SheetOver({ mainScreen(repo, MainTab.Chats)() }) {
            com.hermes.mobile.ui.chats.ProjectPickerContent(
                "家里 NAS 的备份脚本", repo.sessionGroups.value.projects, current = "家庭网络", onPick = {}, onNewProject = {},
            )
        }
    },
    Scene("31b-project-bar") { repo, _ ->
        com.hermes.mobile.ui.chats.ChatsScreen(repo, onOpenChat = {}, onManageConnections = {}, group = "读书笔记")
    },
    Scene("32-new-project", waitMs = 200) { repo, _ ->
        Box(Modifier.fillMaxSize()) {
            mainScreen(repo, MainTab.Chats)()
            com.hermes.mobile.ui.chats.NewProjectDialog(repo.admin()!!, onDismiss = {}, onCreated = { _, _ -> })
        }
    },
    Scene("23-predictive-back") { repo, _ -> PredictiveBackStill(repo, progress = 0.4f) },
    Scene("23b-system-back-left") { repo, _ -> PredictiveBackStill(repo, progress = 0f, systemBack = 0.8f) },
    Scene("23c-system-back-right") { repo, _ -> PredictiveBackStill(repo, progress = 0f, systemBack = 0.8f, fromRight = true) },
    Scene(
        "24-chat-quote",
        prepare = { repo ->
            val reply = SampleData.chat("s-2", System.currentTimeMillis()).filterIsInstance<com.hermes.mobile.data.model.ChatItem.Message>().last()
            repo.chatItems("s-2")
            repo.sendMessage("s-2", "灵隐寺需要提前预约吗？", emptyList(), com.hermes.mobile.data.model.Quote(reply.id, "第二天上午建议先去灵隐寺，人少一些。", partial = true))
            null
        },
        waitMs = 2000,
    ) { repo, _ -> ChatScreen(repo, "s-2", onBack = {}, onOpenChat = {}) },
    Scene("21-welcome") { _, _ -> WelcomeScreen(onPickType = {}, onSkip = {}) },
    Scene("22-welcome-dark", dark = true) { _, _ -> WelcomeScreen(onPickType = {}, onSkip = {}) },
)

/** 演示固定的服务器事件快照；正文、过程卡片和三个点都走真正聊天页。 */
@Composable
private fun StreamingPreview(base: HermesRepository, process: Boolean, completed: Boolean = false) {
    val repo = remember(base, process, completed) {
        val now = System.currentTimeMillis()
        val activity = com.hermes.mobile.data.model.ChatItem.Activity(
            "preview-work", now,
            steps = listOf(
                com.hermes.mobile.data.model.WorkStep("r", com.hermes.mobile.data.model.WorkStep.Kind.Reasoning, "思考", "先检查现有的回复事件，再核对工具调用时的文字保留。", finished = true),
                com.hermes.mobile.data.model.WorkStep("t", com.hermes.mobile.data.model.WorkStep.Kind.Tool, "读取项目", "查看聊天页面和事件处理代码", finished = true),
            ),
            earlierReply = if (completed) "我已经找到文字消失的位置，会保留之前出现的内容。" else "",
            status = if (completed) com.hermes.mobile.data.model.ActivityStatus.Completed else com.hermes.mobile.data.model.ActivityStatus.Working,
        )
        object : HermesRepository by base {
            override val settings = kotlinx.coroutines.flow.MutableStateFlow(base.settings.value.copy(streamReplies = true, showWorkProcess = process))
            override fun chatItems(sessionId: String) = kotlinx.coroutines.flow.flowOf(listOfNotNull(
                com.hermes.mobile.data.model.ChatItem.Message("preview-user", now, com.hermes.mobile.data.model.Role.User, "帮我修复逐字回复消失的问题。"),
                activity,
                if (completed) com.hermes.mobile.data.model.ChatItem.Message("preview-final", now, com.hermes.mobile.data.model.Role.Assistant, "已经修复。工具调用后，前面的句子会继续保留，后续回复接着显示。") else null,
            ))
            override fun runPhase(sessionId: String) = kotlinx.coroutines.flow.flowOf(if (completed) com.hermes.mobile.data.model.RunPhase.Idle else com.hermes.mobile.data.model.RunPhase.Working)
            override fun streamingText(sessionId: String) = kotlinx.coroutines.flow.flowOf(if (completed) null else "我已经找到文字消失的位置。\n\n工具调用时，前面出现的句子会保留，后面的回复接着显示。")
        }
    }
    ChatScreen(repo, "s-1", {}, {})
}

@Composable
private fun NotificationPreview(repository: HermesRepository) {
    val assistant = remember {
        val store = object : com.hermes.mobile.data.notifications.NotificationStore {
            private var value: com.hermes.mobile.data.notifications.NotificationState? = null
            override fun load() = value
            override fun save(state: com.hermes.mobile.data.notifications.NotificationState) { value = state }
        }
        com.hermes.mobile.data.notifications.NotificationAssistant(store, sender = { _, _ -> "需要处理：取件" }).apply {
            configure(com.hermes.mobile.data.notifications.NotificationConfig(
                packages = setOf("example.delivery", "example.calendar"),
                target = com.hermes.mobile.data.notifications.NotificationTarget("example", "default", "fixed", "我的电脑 · 默认 Bot · 通知助手"),
            ))
        }
    }
    com.hermes.mobile.ui.settings.NotificationAssistantScreen(repository, assistant, onBack = {})
}

/** 实际驱动全局导航的进入 / 返回动画后截帧，不手动设置页面位移。 */
@Composable
private fun NotificationTransitionPreview(repository: HermesRepository, exiting: Boolean) {
    val navigator = remember {
        com.hermes.mobile.ui.navigation.Navigator("settings").apply {
            if (exiting) push(com.hermes.mobile.ui.NotificationAssistantRoute)
        }
    }
    com.hermes.mobile.ui.navigation.SwipeBackHost(navigator) { route ->
        if (route == "settings") mainScreen(repository, MainTab.Settings)()
        else NotificationPreview(repository)
    }
    androidx.compose.runtime.LaunchedEffect(navigator) {
        if (exiting) navigator.pop() else navigator.push(com.hermes.mobile.ui.NotificationAssistantRoute)
    }
}

/** 聊天列表里左滑展开的一行（置顶 / 归档 / 删除）。 */
@Composable
private fun SwipeStill(repo: HermesRepository) {
    val sessions by repo.sessions.collectAsState()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        sessions.take(6).forEachIndexed { i, session ->
            com.hermes.mobile.ui.components.SwipeActionsRow(
                isOpen = i == 1,
                onOpenChange = {},
                actions = listOf(
                    com.hermes.mobile.ui.components.SwipeAction("置顶", Icons.Rounded.PushPin, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer) {},
                    com.hermes.mobile.ui.components.SwipeAction("归档", Icons.Rounded.Archive, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary) {},
                    com.hermes.mobile.ui.components.SwipeAction("删除", Icons.Rounded.Delete, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError) {},
                ),
            ) { com.hermes.mobile.ui.chats.SessionRow(session, onClick = {}, onLongPress = {}) }
        }
    }
}

/** 向右滑返回拖到一半的静帧，用的就是 SwipeBackHost 里的同一套页面变换。 */
@Composable
private fun PredictiveBackStill(repo: HermesRepository, progress: Float, systemBack: Float = 0f, fromRight: Boolean = false) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().underPage { maxOf(progress, systemBack * 0.02f) }) { mainScreen(repo, MainTab.Chats)() }
        Box(
            Modifier
                .fillMaxSize()
                .topPage(fraction = { progress }, back = { systemBack }, backFromRight = { fromRight })
                .background(MaterialTheme.colorScheme.background),
        ) {
            ChatScreen(repo, "s-1", onBack = {}, onOpenChat = {})
        }
    }
}

/** 模拟一个展开的底部面板：底下是页面 + 半透明遮罩。 */
@Composable
private fun SheetOver(background: @Composable () -> Unit, sheet: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        background()
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            Column {
                Box(
                    Modifier
                        .padding(vertical = 16.dp)
                        .size(width = 32.dp, height = 4.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                        .align(Alignment.CenterHorizontally),
                )
                sheet()
            }
        }
    }
}

/** 假的状态栏和手势条，让截图更像手机。 */
@Composable
private fun PhoneFrame(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("9:41", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Box(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(Icons.Rounded.SignalCellularAlt, Icons.Rounded.Wifi, Icons.Rounded.BatteryFull).forEach {
                    Icon(it, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        Box(Modifier.fillMaxWidth().height(20.dp).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .width(108.dp)
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
            )
        }
    }
}

fun main(args: Array<String>) {
    val outDir = File(args.firstOrNull() ?: "screenshots").apply { mkdirs() }
    val only = args.drop(1).toSet()
    val width = (WIDTH_DP * DENSITY).toInt()
    val height = (HEIGHT_DP * DENSITY).toInt()

    for (scene in scenes) {
        if (only.isNotEmpty() && only.none { scene.name.contains(it) }) continue
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo = FakeHermesRepository(scope, replyDelayMs = scene.replyDelayMs)
        val arg = scene.prepare(repo)
        if (scene.waitMs > 0) Thread.sleep(scene.waitMs)

        val composeScene = ImageComposeScene(width, height, Density(DENSITY)) {
            HermesTheme(themeMode = if (scene.dark) ThemeMode.Dark else ThemeMode.Light) {
                // 与 HermesApp 的根 Surface 一致，深色预览不能沿用桌面默认的黑色文字。
                Surface(color = MaterialTheme.colorScheme.background) {
                    PhoneFrame { scene.content(repo, arg) }
                }
            }
        }
        // 多渲染几帧，让进入动画、LaunchedEffect 和 Flow 收集都稳定下来
        var nanos = 0L
        repeat(scene.frames) {
            composeScene.render(nanos)
            nanos += 16_666_666L
        }
        val image = composeScene.render(nanos)
        val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("PNG 编码失败：${scene.name}")
        File(outDir, "${scene.name}.png").writeBytes(png.bytes)
        println("rendered ${scene.name}")
        composeScene.close()
        scope.cancel()
    }
}

/** 独立假后端，只展示运行中心布局，不执行 Android 命令。 */
@Composable
private fun RuntimePreview(ready: Boolean) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val manager = remember {
        com.hermes.mobile.data.local.LocalRuntimeManager(scope, object : com.hermes.mobile.data.local.LocalRuntimeBackend {
            override val name = "Termux（演示环境）"
            override val externalApps = true
            override val hermesInstallWarning = com.hermes.mobile.data.local.TermuxRuntimeBackend.PackageWarning
            override val initializationCommand = com.hermes.mobile.data.local.TermuxRuntimeBackend.EnableExternalApps
            override fun environment() = com.hermes.mobile.data.local.RuntimeEnvironment(
                installed = ready, compatible = ready, permission = ready, arm64 = true,
                freeBytes = 4L * 1024 * 1024 * 1024, viewerInstalled = ready, version = if (ready) "0.118.3" else "",
            )
            override suspend fun execute(request: com.hermes.mobile.data.local.RuntimeRequest, progress: (String) -> Unit) =
                com.hermes.mobile.data.local.RuntimeResponse(com.hermes.mobile.data.local.RuntimeSnapshot(toolsReady = true))
        }, object : com.hermes.mobile.data.local.LocalConnectionAccess {
            override fun save(apiKey: String) = Unit
            override suspend fun verifyAndActivate() = Unit
        })
    }
    com.hermes.mobile.ui.settings.LocalRuntimeScreen(manager, {})
}

/** 液态玻璃对比用：彩色内容上放 Tab 栏和输入栏，看通透程度。 */
@Composable
private fun GlassTest() {
    val backdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop),
        ) {
            val colors = listOf(Color(0xFFE53935), Color(0xFFFFB300), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA))
            repeat(14) { i ->
                Row(Modifier.fillMaxWidth().height(64.dp).background(colors[i % colors.size].copy(alpha = 0.85f)), verticalAlignment = Alignment.CenterVertically) {
                    Text("第 ${i + 1} 行  Hermes 液态玻璃 ABC 123", color = Color.White, fontSize = 20.sp, modifier = Modifier.padding(start = 24.dp))
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            com.hermes.mobile.ui.chat.ChatInputBar(
                backdrop = backdrop, text = "", onTextChange = {}, attachments = emptyList(), onRemoveAttachment = {}, onAttach = {},
                mode = com.hermes.mobile.ui.chat.SendButtonMode.Send, onSend = {}, onStop = {}, enterToSend = false, quote = null, onClearQuote = {},
                focusRequester = remember { androidx.compose.ui.focus.FocusRequester() },
            )
            com.hermes.mobile.ui.main.FloatingTabBar(backdrop, MainTab.entries, MainTab.Chats, {}, mapOf(MainTab.Chats to 2), null)
        }
    }
}

private val MarkdownSample = """
## 家里网络排查结果

**结论：**是路由器的 DNS 出了问题，已经改好。前面**“断网”**其实是解析失败。

1. **第一步（已完成）**：把 DNS 改回 `223.5.5.5`
   - 主路由 ✅
   - 子路由 *也改了*
2. 重启了 ~~光猫~~ 路由器

| 设备 | 状态 | 延迟 |
|---|---|---|
| **NAS** | 在线 | 3 ms |
| 电视盒子 | 离线 | — |

- [x] 备份配置
- [ ] 换掉旧网线

> 下次再断，先看 https://192.168.1.1 的日志。

```bash
nslookup baidu.com 223.5.5.5
```
""".trimIndent()

private val WeatherSample = """
这是结合官方预报和两个模型对过之后的结果：
日期 ｜ 天气 ｜ 气温 ｜ 风 ｜ 空气
10/19 周一 ｜ 阴转多云 ｜ 13~22°C ｜ 南风2级 ｜ AQI 54 良
10/20 周二 ｜ 小雨 ｜ 13~21°C ｜ 东南风2级 ｜ AQI 58 良
10/21 周三 ｜ 晴 ｜ 8~18°C ｜ 东风2级 ｜ AQI 45 优
10/22 周四 ｜ 小雨 ｜ 9~16°C ｜ 南风2级 ｜ AQI 66 良
10/23 周五 ｜ 晴 ｜ 11~19°C ｜ 南风2级 ｜ AQI 70 良

这几处不太一致的地方，我得如实说一下，不糊弄你：

10/20 的小雨，官方报了，但 open-meteo 给 0 mm、降水概率 4%，met.no 也是 0 mm。
""".trimIndent()

private val StressSample = """
###三步搞定
勾股定理：${'$'}a^2 + b^2 = c^2${'$'}，所以 \(c = \sqrt{a^2+b^2}\)。

${'$'}${'$'}
E = mc^2 \times \frac{1}{2}
${'$'}${'$'}

价格是 ${'$'}5 到 ${'$'}10 之间。

1. **准备**：先备份
  - 子项一（两格缩进）
  - 子项二
2. *注意：*别删错

| 项目 | 说明 |
|---|---|
| A | 第一行<br>第二行 |
| B | <b>加粗</b> 和 <code>code</code> |

<details><summary>展开看详情</summary>里面的内容</details>

脚注示例[^1]。

[^1]: 这是脚注。
""".trimIndent()

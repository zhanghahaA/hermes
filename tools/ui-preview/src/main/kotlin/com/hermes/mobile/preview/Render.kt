package com.hermes.mobile.preview

import androidx.compose.foundation.background
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
    val content: @Composable (HermesRepository, String?) -> Unit,
)

private fun mainScreen(repo: HermesRepository, tab: MainTab) = @Composable {
    MainScreen(repo, onOpenChat = {}, onEditConnection = {}, onEditJob = {}, onOpenWelcome = {}, initialTab = tab)
}

private val scenes = listOf(
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
                    SheetAction("重新生成", Icons.Rounded.Refresh, {}),
                    SheetAction("从这里分叉", Icons.Rounded.AccountTree, {}),
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
    Scene("14-job-editor") { repo, _ -> JobEditorScreen(repo, "j-brief", onClose = {}) },
    Scene("14b-job-editor-failed") { repo, _ -> JobEditorScreen(repo, "j-backup", onClose = {}) },
    Scene("14c-job-new") { repo, _ -> JobEditorScreen(repo, null, onClose = {}) },
    Scene("15-capabilities-models") { repo, _ -> mainScreen(repo, MainTab.Capabilities)() },
    Scene("16-capabilities-skills") { repo, _ -> CapabilitiesScreen(repo, initialTab = 1) },
    Scene("16b-capabilities-toolsets") { repo, _ -> CapabilitiesScreen(repo, initialTab = 2) },
    Scene("16c-sheet-skill") { repo, _ ->
        SheetOver({ CapabilitiesScreen(repo, initialTab = 1) }) { SkillSheetContent(SampleData.skills[3], onUse = {}) }
    },
    Scene("17-settings") { repo, _ -> mainScreen(repo, MainTab.Settings)() },
    Scene("18-settings-dark", dark = true) { repo, _ -> mainScreen(repo, MainTab.Settings)() },
    Scene("19-connection-ssh") { repo, _ -> ConnectionEditorScreen(repo, "c-ssh", onClose = {}) },
    Scene("20-connection-termux") { repo, _ -> ConnectionEditorScreen(repo, null, ConnectionType.Local.name, onClose = {}) },
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
                PhoneFrame { scene.content(repo, arg) }
            }
        }
        // 多渲染几帧，让进入动画、LaunchedEffect 和 Flow 收集都稳定下来
        var nanos = 0L
        repeat(60) {
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

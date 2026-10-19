package com.hermes.mobile.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.model.Draft
import com.hermes.mobile.ui.admin.AdminPage
import com.hermes.mobile.ui.capabilities.CapabilitiesScreen
import com.hermes.mobile.ui.chats.ChatsScreen
import com.hermes.mobile.ui.jobs.JobsScreen
import com.hermes.mobile.ui.settings.SettingsScreen
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

enum class MainTab(val label: String, val selectedIcon: ImageVector, val icon: ImageVector) {
    Chats("聊天", Icons.Rounded.Forum, Icons.Outlined.Forum),
    Bots("Bot", Icons.Rounded.SmartToy, Icons.Outlined.SmartToy),
    Jobs("任务", Icons.Rounded.Schedule, Icons.Outlined.Schedule),
    Capabilities("能力", Icons.Rounded.Extension, Icons.Outlined.Extension),
    Settings("设置", Icons.Rounded.Settings, Icons.Outlined.Settings),
}

/** 悬浮底栏占掉的底部空间（含系统导航栏）。各个 Tab 的列表据此留白，内容才能滚到玻璃下面又不被挡住。 */
val LocalBottomBarSpace = compositionLocalOf { 0.dp }

@Composable
fun MainScreen(
    repository: HermesRepository,
    onOpenChat: (String) -> Unit,
    onEditConnection: (String?) -> Unit,
    onEditJob: (String?) -> Unit,
    onOpenWelcome: () -> Unit,
    onOpenAdmin: (AdminPage) -> Unit = {},
    onEditBot: (String?) -> Unit = {},
    initialTab: MainTab = MainTab.Chats,
) {
    var tabIndex by rememberSaveable { mutableIntStateOf(initialTab.ordinal) }
    val tab = MainTab.entries[tabIndex]
    val sessions by repository.sessions.collectAsState()
    val unread = sessions.sumOf { it.unread }
    val backdrop = rememberLayerBackdrop()
    val navBar: Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomSpace = FloatingBarHeight + FloatingBarMargin * 2 + navBar

    // 聊天列表选中的分组：选的是项目时，新对话直接建在项目里
    var chatGroup by rememberSaveable { mutableStateOf<String?>(null) }
    val grouping by repository.sessionGroups.collectAsState()
    val chatProject = grouping.projects.firstOrNull { it.name == chatGroup && it.path != null }

    val action = when (tab) {
        MainTab.Chats -> FloatingAction(Icons.Rounded.EditNote, chatProject?.let { "在「${it.name}」里新对话" } ?: "新对话") {
            repository.createSession()?.let { id ->
                if (chatProject != null) repository.setDraftProject(id, chatProject)
                onOpenChat(id)
            }
        }
        MainTab.Jobs -> FloatingAction(Icons.Rounded.Add, "新建任务") { onEditJob(null) }
        MainTab.Bots -> FloatingAction(Icons.Rounded.Add, "新建 bot") { onEditBot(null) }
        else -> null
    }

    Box(Modifier.fillMaxSize()) {
        // 内容层：悬浮栏从这里取样做玻璃效果
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(MaterialTheme.colorScheme.background),
        ) {
            CompositionLocalProvider(LocalBottomBarSpace provides bottomSpace) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "main-tab",
                ) { current ->
                    when (current) {
                        MainTab.Chats -> ChatsScreen(
                            repository = repository,
                            onOpenChat = onOpenChat,
                            onManageConnections = { tabIndex = MainTab.Settings.ordinal },
                            group = chatGroup,
                            onGroupChange = { chatGroup = it },
                        )
                        MainTab.Bots -> com.hermes.mobile.ui.bots.BotsScreen(
                            repository = repository,
                            onOpenChat = onOpenChat,
                            onShowChats = { tabIndex = MainTab.Chats.ordinal },
                            onEditBot = onEditBot,
                        )
                        MainTab.Jobs -> JobsScreen(repository = repository, onEditJob = onEditJob)
                        MainTab.Capabilities -> CapabilitiesScreen(
                            repository = repository,
                            onUseSkill = { skill ->
                                repository.createSession()?.let { id ->
                                    repository.saveDraft(id, Draft(text = skill.invocation()))
                                    onOpenChat(id)
                                }
                            },
                            onOpenAdmin = onOpenAdmin,
                        )
                        MainTab.Settings -> SettingsScreen(
                            repository = repository,
                            onEditConnection = onEditConnection,
                            onOpenWelcome = onOpenWelcome,
                        )
                    }
                }
            }
        }
        FloatingTabBar(
            backdrop = backdrop,
            tabs = MainTab.entries,
            selected = tab,
            onSelect = { tabIndex = it.ordinal },
            badges = mapOf(MainTab.Chats to unread),
            action = action,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

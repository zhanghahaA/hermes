package com.hermes.mobile.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.ui.capabilities.CapabilitiesScreen
import com.hermes.mobile.ui.chats.ChatsScreen
import com.hermes.mobile.ui.jobs.JobsScreen
import com.hermes.mobile.ui.settings.SettingsScreen

enum class MainTab(val label: String, val selectedIcon: ImageVector, val icon: ImageVector) {
    Chats("聊天", Icons.Rounded.Forum, Icons.Outlined.Forum),
    Jobs("任务", Icons.Rounded.Schedule, Icons.Outlined.Schedule),
    Capabilities("能力", Icons.Rounded.Extension, Icons.Outlined.Extension),
    Settings("设置", Icons.Rounded.Settings, Icons.Outlined.Settings),
}

@Composable
fun MainScreen(
    repository: HermesRepository,
    onOpenChat: (String) -> Unit,
    onEditConnection: (String?) -> Unit,
    onEditJob: (String?) -> Unit,
    onOpenWelcome: () -> Unit,
    initialTab: MainTab = MainTab.Chats,
) {
    var tabIndex by rememberSaveable { mutableIntStateOf(initialTab.ordinal) }
    val tab = MainTab.entries[tabIndex]
    val sessions by repository.sessions.collectAsState()
    val unread = sessions.sumOf { it.unread }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "main-tab",
            ) { current ->
                when (current) {
                    MainTab.Chats -> ChatsScreen(
                        repository = repository,
                        onOpenChat = onOpenChat,
                        onNewChat = { onOpenChat(repository.createSession()) },
                        onManageConnections = { tabIndex = MainTab.Settings.ordinal },
                    )
                    MainTab.Jobs -> JobsScreen(repository = repository, onEditJob = onEditJob)
                    MainTab.Capabilities -> CapabilitiesScreen(repository = repository)
                    MainTab.Settings -> SettingsScreen(
                        repository = repository,
                        onEditConnection = onEditConnection,
                        onOpenWelcome = onOpenWelcome,
                    )
                }
            }
        }
        NavigationBar {
            MainTab.entries.forEach { item ->
                val selected = item == tab
                NavigationBarItem(
                    selected = selected,
                    onClick = { tabIndex = item.ordinal },
                    icon = {
                        BadgedBox(badge = { if (item == MainTab.Chats && unread > 0) Badge { Text("$unread") } }) {
                            Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null)
                        }
                    },
                    label = { Text(item.label) },
                )
            }
        }
    }
}

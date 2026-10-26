package com.hermes.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.ui.admin.AdminPage
import com.hermes.mobile.ui.admin.AdminScreen
import com.hermes.mobile.ui.chat.ChatScreen
import com.hermes.mobile.ui.jobs.JobEditorScreen
import com.hermes.mobile.ui.main.MainScreen
import com.hermes.mobile.ui.navigation.Navigator
import com.hermes.mobile.ui.navigation.SwipeBackHost
import com.hermes.mobile.ui.settings.ConnectionEditorScreen
import com.hermes.mobile.ui.theme.HermesTheme
import com.hermes.mobile.ui.welcome.WelcomeScreen
import kotlinx.coroutines.launch

data object MainRoute
data object LocalRuntimeRoute
data object WelcomeRoute
data object NotificationAssistantRoute
data class ChatRoute(val sessionId: String)
data class ConnectionEditRoute(val connectionId: String? = null, val type: String? = null)
data class JobEditRoute(val jobId: String? = null)
data class AdminRoute(val page: AdminPage)
data class BotEditRoute(val botName: String? = null)
data class RoomRoute(val roomId: String)

/** 全局轻提示，主要用来标注“这个功能还是占位”。 */
val LocalShowMessage = staticCompositionLocalOf<(String) -> Unit> { {} }

/** 占位功能统一提示。 */
@Composable
fun rememberPlaceholder(): (String) -> Unit {
    val show = LocalShowMessage.current
    return remember(show) { { feature: String -> show("「$feature」还在规划中，当前是占位") } }
}

@Composable
fun HermesApp(repository: HermesRepository, navigator: Navigator) {
    val settings by repository.settings.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = remember(snackbarHostState) {
        { message ->
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(message)
            }
        }
    }

    LaunchedEffect(repository) { repository.errors.collect { showMessage(it) } }
    // 上次闪退了：把记下来的出错位置给用户看，方便反馈
    var lastCrash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { lastCrash = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.hermes.mobile.data.store.CrashLog.takeUnseenCrash() } }

    HermesTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
        CompositionLocalProvider(
            LocalShowMessage provides showMessage,
            com.hermes.mobile.ui.chat.LocalMediaFetcher provides repository::fetchRemoteMedia,
        ) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    HermesNavHost(repository, navigator)
                    lastCrash?.let { crash -> CrashDialog(crash, onDismiss = { lastCrash = null }) }
                    SnackbarHost(
                        snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(bottom = 100.dp),
                    ) { data -> Snackbar(data, shape = MaterialTheme.shapes.medium) }
                }
            }
        }
    }
}

@Composable
private fun HermesNavHost(repository: HermesRepository, navigator: Navigator) {
    SwipeBackHost(navigator) { route ->
        when (route) {
            MainRoute -> MainScreen(
                repository = repository,
                onOpenChat = { navigator.push(ChatRoute(it)) },
                onEditConnection = { id -> navigator.push(ConnectionEditRoute(id)) },
                onEditJob = { id -> navigator.push(JobEditRoute(id)) },
                onOpenWelcome = { navigator.push(WelcomeRoute) },
                onOpenAdmin = { navigator.push(AdminRoute(it)) },
                onEditBot = { navigator.push(BotEditRoute(it)) },
                onOpenRoom = { navigator.push(RoomRoute(it)) },
                onOpenNotificationAssistant = { navigator.push(NotificationAssistantRoute) },
                onOpenLocalRuntime = { navigator.push(LocalRuntimeRoute) },
            )
            LocalRuntimeRoute -> repository.localRuntime?.let {
                com.hermes.mobile.ui.settings.LocalRuntimeScreen(it, onBack = navigator::pop)
            }
            NotificationAssistantRoute -> repository.notificationAssistant?.let {
                com.hermes.mobile.ui.settings.NotificationAssistantScreen(repository, it, onBack = navigator::pop)
            }
            is AdminRoute -> AdminScreen(repository, route.page, onBack = navigator::pop)
            is ChatRoute -> ChatScreen(
                repository = repository,
                sessionId = route.sessionId,
                onBack = navigator::pop,
                onOpenChat = { id -> navigator.push(ChatRoute(id), clearAboveRoot = true) },
                onOpenScreen = { navigator.push(AdminRoute(AdminPage.Screen)) }.takeIf { repository.admin() != null },
            )
            is ConnectionEditRoute -> ConnectionEditorScreen(
                repository = repository,
                connectionId = route.connectionId,
                initialType = route.type,
                onOpenLocalRuntime = { navigator.push(LocalRuntimeRoute, clearAboveRoot = true) }.takeIf { repository.localRuntime != null },
                onClose = navigator::pop,
            )
            is RoomRoute -> com.hermes.mobile.ui.bots.GroupChatScreen(repository, route.roomId, onBack = navigator::pop)
            is BotEditRoute -> com.hermes.mobile.ui.bots.BotEditorScreen(repository, route.botName, onClose = navigator::pop)
            is JobEditRoute -> JobEditorScreen(
                repository = repository,
                jobId = route.jobId,
                onClose = navigator::pop,
                onOpenChat = { navigator.push(ChatRoute(it)) },
            )
            WelcomeRoute -> WelcomeScreen(
                onPickType = { type ->
                    val route = if (type == com.hermes.mobile.data.model.ConnectionType.Local && repository.localRuntime != null)
                        LocalRuntimeRoute else ConnectionEditRoute(type = type.name)
                    navigator.push(route, clearAboveRoot = true)
                },
                onSkip = navigator::pop,
            )
        }
    }
}

/** 上次闪退的记录：可以复制下来发给开发者。 */
@Composable
private fun CrashDialog(crash: String, onDismiss: () -> Unit) {
    @Suppress("DEPRECATION")
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上次 App 意外退出了") },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Text("下面是出错的位置。点“复制”发给开发者，能很快找到原因（里面没有密码和密钥）。", style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.heightIn(max = 260.dp),
                ) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            crash,
                            style = com.hermes.mobile.ui.theme.CodeTextStyle.copy(fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp)),
                            modifier = Modifier
                                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                                .padding(8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(crash))
                onDismiss()
            }) { Text("复制") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.HermesRepository
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
data object WelcomeRoute
data class ChatRoute(val sessionId: String)
data class ConnectionEditRoute(val connectionId: String? = null, val type: String? = null)
data class JobEditRoute(val jobId: String? = null)

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

    HermesTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
        CompositionLocalProvider(LocalShowMessage provides showMessage) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    HermesNavHost(repository, navigator)
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
            )
            is ChatRoute -> ChatScreen(
                repository = repository,
                sessionId = route.sessionId,
                onBack = navigator::pop,
                onOpenChat = { id -> navigator.push(ChatRoute(id), clearAboveRoot = true) },
            )
            is ConnectionEditRoute -> ConnectionEditorScreen(
                repository = repository,
                connectionId = route.connectionId,
                initialType = route.type,
                onClose = navigator::pop,
            )
            is JobEditRoute -> JobEditorScreen(
                repository = repository,
                jobId = route.jobId,
                onClose = navigator::pop,
            )
            WelcomeRoute -> WelcomeScreen(
                onPickType = { type -> navigator.push(ConnectionEditRoute(type = type.name), clearAboveRoot = true) },
                onSkip = navigator::pop,
            )
        }
    }
}

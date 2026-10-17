package com.hermes.mobile.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.ui.chat.ChatScreen
import com.hermes.mobile.ui.jobs.JobEditorScreen
import com.hermes.mobile.ui.main.MainScreen
import com.hermes.mobile.ui.settings.ConnectionEditorScreen
import com.hermes.mobile.ui.theme.HermesTheme
import com.hermes.mobile.ui.welcome.WelcomeScreen
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data object MainRoute
@Serializable data object WelcomeRoute
@Serializable data class ChatRoute(val sessionId: String)
@Serializable data class ConnectionEditRoute(val connectionId: String? = null, val type: String? = null)
@Serializable data class JobEditRoute(val jobId: String? = null)

/** 全局轻提示，主要用来标注“这个功能还是占位”。 */
val LocalShowMessage = staticCompositionLocalOf<(String) -> Unit> { {} }

/** 占位功能统一提示。 */
@Composable
fun rememberPlaceholder(): (String) -> Unit {
    val show = LocalShowMessage.current
    return remember(show) { { feature: String -> show("「$feature」还在规划中，当前是占位") } }
}

@Composable
fun HermesApp(repository: HermesRepository) {
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
                    HermesNavHost(repository)
                    SnackbarHost(
                        snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(bottom = 84.dp),
                    ) { data -> Snackbar(data, shape = MaterialTheme.shapes.medium) }
                }
            }
        }
    }
}

@Composable
private fun HermesNavHost(repository: HermesRepository) {
    val navController = rememberNavController()
    val slide = tween<androidx.compose.ui.unit.IntOffset>(320)

    NavHost(
        navController = navController,
        startDestination = MainRoute,
        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, slide) + fadeIn(tween(220)) },
        exitTransition = { fadeOut(tween(200)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, slide) + fadeOut(tween(220)) },
    ) {
        composable<MainRoute> {
            MainScreen(
                repository = repository,
                onOpenChat = { navController.navigate(ChatRoute(it)) },
                onEditConnection = { id -> navController.navigate(ConnectionEditRoute(id)) },
                onEditJob = { id -> navController.navigate(JobEditRoute(id)) },
                onOpenWelcome = { navController.navigate(WelcomeRoute) },
            )
        }
        composable<ChatRoute> { entry ->
            val route = entry.toRoute<ChatRoute>()
            ChatScreen(
                repository = repository,
                sessionId = route.sessionId,
                onBack = { navController.popBackStack() },
                onOpenChat = { id ->
                    navController.navigate(ChatRoute(id)) { popUpTo<MainRoute>() }
                },
            )
        }
        composable<ConnectionEditRoute> { entry ->
            val route = entry.toRoute<ConnectionEditRoute>()
            ConnectionEditorScreen(
                repository = repository,
                connectionId = route.connectionId,
                initialType = route.type,
                onClose = { navController.popBackStack() },
            )
        }
        composable<JobEditRoute> { entry ->
            JobEditorScreen(
                repository = repository,
                jobId = entry.toRoute<JobEditRoute>().jobId,
                onClose = { navController.popBackStack() },
            )
        }
        composable<WelcomeRoute> {
            WelcomeScreen(
                onPickType = { type ->
                    navController.navigate(ConnectionEditRoute(type = type.name)) { popUpTo<MainRoute>() }
                },
                onSkip = { navController.popBackStack() },
            )
        }
    }
}

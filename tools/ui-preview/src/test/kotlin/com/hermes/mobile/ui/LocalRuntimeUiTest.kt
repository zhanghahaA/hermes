package com.hermes.mobile.ui

import androidx.compose.ui.test.*
import com.hermes.mobile.data.local.*
import com.hermes.mobile.ui.settings.LocalRuntimeScreen
import com.hermes.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class LocalRuntimeUiTest {
    @Test fun installRequiresExplicitConsentAndModelCredentials() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = CopyOnWriteArrayList<RuntimeAction>()
        val backend = object : LocalRuntimeBackend {
            override val name = "演示环境"
            override val externalApps = true
            override val hermesInstallWarning = "实验安装可能失败"
            override fun environment() = RuntimeEnvironment(true, true, true, true, 4L * 1024 * 1024 * 1024, true, "0.118.3")
            override suspend fun execute(request: RuntimeRequest, progress: (String) -> Unit): RuntimeResponse {
                calls += request.action
                return RuntimeResponse(RuntimeSnapshot(true, true, true, true), connectionKey = "test-key".takeIf { request.action == RuntimeAction.ConfigureHermes })
            }
        }
        val manager = LocalRuntimeManager(scope, backend, object : LocalConnectionAccess {
            override fun save(apiKey: String) = Unit
            override suspend fun verifyAndActivate() = Unit
        })
        try {
            setContent { HermesTheme { LocalRuntimeScreen(manager, {}) } }
            onNodeWithText("部署本机 Hermes（实验）").performScrollTo().performClick()
            onNodeWithText("确认并继续").assertIsNotEnabled()
            onNodeWithText("模型名称").performTextInput("fake-model")
            onNodeWithText("模型 API Key").performTextInput("test-key")
            onNode(isToggleable()).performScrollTo().performClick()
            onNodeWithText("确认并继续").performClick()
            waitUntil(timeoutMillis = 5000) { manager.state.value.checked && !manager.state.value.busy }
            assertEquals(listOf(RuntimeAction.InstallHermes, RuntimeAction.Prepare, RuntimeAction.ConfigureHermes, RuntimeAction.StartHermes), calls.toList())
        } finally { scope.cancel() }
    }

    @Test fun missingTermuxKeepsMutatingActionsDisabled() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = LocalRuntimeManager(scope, object : LocalRuntimeBackend {
            override val name = "Termux"
            override val externalApps = true
            override fun environment() = RuntimeEnvironment()
            override suspend fun execute(request: RuntimeRequest, progress: (String) -> Unit): RuntimeResponse = error("未授权时不执行")
        }, object : LocalConnectionAccess {
            override fun save(apiKey: String) = Unit
            override suspend fun verifyAndActivate() = Unit
        })
        try {
            setContent { HermesTheme { LocalRuntimeScreen(manager, {}) } }
            onNodeWithText("下载 Termux").assertIsDisplayed()
            onNodeWithText("检查环境").performScrollTo().assertIsNotEnabled()
            onNodeWithText("部署本机 Hermes（实验）").performScrollTo().assertIsNotEnabled()
        } finally { scope.cancel() }
    }
}

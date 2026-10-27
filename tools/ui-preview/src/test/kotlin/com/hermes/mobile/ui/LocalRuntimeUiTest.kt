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
    @Test fun installNeedsConsentButNotModelCredentials() = runComposeUiTest {
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
            waitUntil(timeoutMillis = 5000) { manager.state.value.checked && !manager.state.value.busy }
            calls.clear()
            onNodeWithText("一键安装 Hermes").performScrollTo().performClick()
            onNodeWithText("模型 API Key").assertDoesNotExist()
            assertTrue(calls.isEmpty(), "只有明确确认后才可安装")
            onNodeWithText("确认并继续").assertIsEnabled()
            onNodeWithText("确认并继续").performClick()
            waitUntil(timeoutMillis = 5000) { calls.contains(RuntimeAction.ConfigureHermes) && !manager.state.value.busy }
            assertEquals(listOf(RuntimeAction.Prepare, RuntimeAction.InstallHermes, RuntimeAction.ConfigureHermes), calls.toList())
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
            onNodeWithText("下载 Termux").performScrollTo().assertIsDisplayed()
            onNodeWithText("检查环境").performScrollTo().assertIsEnabled()
            onNodeWithText("一键安装 Hermes").performScrollTo().assertIsNotEnabled()
        } finally { scope.cancel() }
    }
    @Test fun deniedPermissionStillOffersCheckAndSystemSettings() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = LocalRuntimeManager(scope, object : LocalRuntimeBackend {
            override val name = "Termux"
            override val externalApps = true
            override fun environment() = RuntimeEnvironment(true, true, false, true, 4L * 1024 * 1024 * 1024)
            override suspend fun execute(request: RuntimeRequest, progress: (String) -> Unit): RuntimeResponse = error("不应未授权就执行")
        }, object : LocalConnectionAccess {
            override fun save(apiKey: String) = Unit
            override suspend fun verifyAndActivate() = Unit
        })
        try {
            setContent { HermesTheme { LocalRuntimeScreen(manager, {}) } }
            onNodeWithText("Hermes 执行权限 · 未通过").performScrollTo().assertIsDisplayed()
            onNodeWithText("去授权执行权限").performScrollTo().performClick()
            assertEquals("permission", com.hermes.mobile.platform.DesktopRuntimeActions.lastAction)
            onNodeWithText("检查环境").performScrollTo().assertIsEnabled().performClick()
            assertEquals("permission", com.hermes.mobile.platform.DesktopRuntimeActions.lastAction)
            onNodeWithText("打开系统权限设置").performScrollTo().performClick()
            assertEquals("permission-settings", com.hermes.mobile.platform.DesktopRuntimeActions.lastAction)
        } finally { scope.cancel() }
    }

    @Test fun grantingPermissionRefreshesChecksAndEnablesPreparation() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var env = RuntimeEnvironment(true, true, false, true, 4L * 1024 * 1024 * 1024)
        val manager = LocalRuntimeManager(scope, object : LocalRuntimeBackend {
            override val name = "Termux"
            override val externalApps = true
            override fun environment() = env
            override suspend fun execute(request: RuntimeRequest, progress: (String) -> Unit) = RuntimeResponse(RuntimeSnapshot())
        }, object : LocalConnectionAccess {
            override fun save(apiKey: String) = Unit
            override suspend fun verifyAndActivate() = Unit
        })
        try {
            setContent { HermesTheme { LocalRuntimeScreen(manager, {}) } }
            onNodeWithText("检查并准备环境").performScrollTo().assertIsNotEnabled()
            env = env.copy(permission = true)
            runOnIdle { manager.refreshEnvironment() }
            waitUntil(timeoutMillis = 5000) { manager.state.value.probe == RuntimeProbe.Passed && !manager.state.value.busy }
            onNodeWithText("检查并准备环境").performScrollTo().assertIsEnabled()
            onNodeWithText("Hermes 执行权限 · 未通过").assertDoesNotExist()
        } finally { scope.cancel() }
    }

    @Test fun insufficientStorageHasAnActionAndDoesNotDisableReadOnlyChecks() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = LocalRuntimeManager(scope, object : LocalRuntimeBackend {
            override val name = "Termux"
            override val externalApps = true
            override fun environment() = RuntimeEnvironment(true, true, true, true, 100 * 1024 * 1024)
            override suspend fun execute(request: RuntimeRequest, progress: (String) -> Unit) = RuntimeResponse(RuntimeSnapshot())
        }, object : LocalConnectionAccess {
            override fun save(apiKey: String) = Unit
            override suspend fun verifyAndActivate() = Unit
        })
        try {
            setContent { HermesTheme { LocalRuntimeScreen(manager, {}) } }
            waitUntil(timeoutMillis = 5000) { manager.state.value.probe == RuntimeProbe.Passed && !manager.state.value.busy }
            onNodeWithText("安装空间 · 未通过").performScrollTo().assertIsDisplayed()
            onNodeWithText("打开存储设置").performScrollTo().performClick()
            assertEquals("storage-settings", com.hermes.mobile.platform.DesktopRuntimeActions.lastAction)
            onNodeWithText("检查环境").performScrollTo().assertIsEnabled()
            onNodeWithText("检查并准备环境").assertIsNotEnabled()
            onNodeWithText("复制检查结果").performScrollTo().assertIsEnabled()
        } finally { scope.cancel() }
    }

}

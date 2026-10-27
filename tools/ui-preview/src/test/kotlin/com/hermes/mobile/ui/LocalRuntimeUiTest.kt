package com.hermes.mobile.ui

import androidx.compose.ui.test.*
import com.hermes.mobile.data.local.*
import com.hermes.mobile.platform.DesktopRuntimeActions
import com.hermes.mobile.ui.settings.LocalRuntimeScreen
import com.hermes.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class LocalRuntimeUiTest {
    private val environment = RuntimeEnvironment(true, true, true, true, 4L * 1024 * 1024 * 1024, true, "0.118.3")
    private class Fixture(var env: RuntimeEnvironment) : LocalRuntimeBackend {
        override val name = "Termux"
        override val externalApps = true
        override val initializationCommand = "test initialization"
        val calls = CopyOnWriteArrayList<RuntimeAction>()
        var snapshot = RuntimeSnapshot()
        var failure: RuntimeCommandException? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = LocalRuntimeManager(scope, this, object : LocalConnectionAccess {
            override fun save(apiKey: String) = Unit
            override suspend fun verifyAndActivate() = Unit
        })
        override fun environment() = env
        override suspend fun execute(request: RuntimeRequest, progress: (String) -> Unit): RuntimeResponse {
            calls += request.action
            failure?.let { throw it }
            snapshot = when(request.action) {
                RuntimeAction.Prepare -> snapshot.copy(toolsReady = true)
                RuntimeAction.InstallHermes -> snapshot.copy(hermesInstalled = true)
                RuntimeAction.ConfigureHermes -> snapshot.copy(configured = true)
                RuntimeAction.StartHermes -> snapshot.copy(hermesRunning = true)
                else -> snapshot
            }
            return RuntimeResponse(snapshot, connectionKey = "fixture-key".takeIf { request.action == RuntimeAction.ConfigureHermes })
        }
    }

    @Test fun oneClickInstallsAndOpensModelsWithoutInstallingOnEntry() = runComposeUiTest {
        val f = Fixture(environment)
        var models = false
        try {
            setContent { HermesTheme { LocalRuntimeScreen(f.manager, {}, onConfigureModels = { models = true }) } }
            waitUntil(timeoutMillis = 5000) { f.manager.state.value.checked && !f.manager.state.value.busy }
            assertEquals(listOf(RuntimeAction.Inspect), f.calls.toList())
            onNodeWithText("检查并准备环境").assertDoesNotExist()
            onNodeWithText("可选 · 自己的服务").assertDoesNotExist()
            onNodeWithText("安装 Hermes").assertIsDisplayed().performClick()
            waitUntil(timeoutMillis = 5000) { models }
            assertEquals(listOf(RuntimeAction.Inspect, RuntimeAction.Inspect, RuntimeAction.Prepare,
                RuntimeAction.InstallHermes, RuntimeAction.ConfigureHermes), f.calls.toList())
            onNodeWithText("确认并继续").assertDoesNotExist()
        } finally { f.scope.cancel() }
    }

    @Test fun missingTermuxShowsOnlyDownloadAsNextStep() = runComposeUiTest {
        val f = Fixture(RuntimeEnvironment())
        try {
            setContent { HermesTheme { LocalRuntimeScreen(f.manager, {}) } }
            onNodeWithTag("runtime-primary").assertIsEnabled().performClick()
            assertEquals("download-termux", DesktopRuntimeActions.lastAction)
            assertTrue(f.calls.isEmpty())
            onNodeWithText("安装 Hermes").assertDoesNotExist()
        } finally { f.scope.cancel() }
    }

    @Test fun permissionReturnChecksAutomaticallyWithoutInstalling() = runComposeUiTest {
        val f = Fixture(environment.copy(permission = false))
        try {
            setContent { HermesTheme { LocalRuntimeScreen(f.manager, {}) } }
            onNodeWithText("授予权限").performClick()
            assertEquals("permission", DesktopRuntimeActions.lastAction)
            assertTrue(f.calls.isEmpty())
            f.env = environment
            runOnIdle { DesktopRuntimeActions.resume() }
            waitUntil(timeoutMillis = 5000) { f.manager.state.value.probe == RuntimeProbe.Passed && !f.manager.state.value.busy }
            onNodeWithText("安装 Hermes").assertIsDisplayed().assertIsEnabled()
            assertTrue(f.calls.all { it == RuntimeAction.Inspect })
        } finally { f.scope.cancel() }
    }

    @Test fun initializedTermuxIsRecheckedOnReturnEvenWithoutPermissionChange() = runComposeUiTest {
        val f = Fixture(environment)
        f.failure = RuntimeCommandException(RuntimeIssue.ExternalApps, 1500)
        try {
            setContent { HermesTheme { LocalRuntimeScreen(f.manager, {}) } }
            waitUntil(timeoutMillis = 5000) { f.manager.state.value.failed }
            onNodeWithText("复制命令并打开 Termux").performClick()
            assertEquals("termux", DesktopRuntimeActions.lastAction)
            f.failure = null
            runOnIdle { DesktopRuntimeActions.resume() }
            waitUntil(timeoutMillis = 5000) { f.manager.state.value.probe == RuntimeProbe.Passed && !f.manager.state.value.busy }
            onNodeWithText("安装 Hermes").assertIsEnabled()
            onNodeWithText("复制命令并打开 Termux").assertDoesNotExist()
            assertTrue(f.calls.all { it == RuntimeAction.Inspect })
        } finally { f.scope.cancel() }
    }

    @Test fun lowStorageHasDirectRemedyAndReadOnlyChecksRemainAvailable() = runComposeUiTest {
        val f = Fixture(environment.copy(freeBytes = 100 * 1024 * 1024))
        try {
            setContent { HermesTheme { LocalRuntimeScreen(f.manager, {}) } }
            waitUntil(timeoutMillis = 5000) { f.manager.state.value.checked && !f.manager.state.value.busy }
            onNodeWithText("释放存储空间").performClick()
            assertEquals("storage-settings", DesktopRuntimeActions.lastAction)
            onNodeWithText("查看检查详情").performClick()
            onNodeWithText("复制检查结果").performScrollTo().assertIsEnabled()
            onNodeWithText("重新检查环境").performScrollTo().assertIsEnabled()
            assertTrue(f.calls.all { it == RuntimeAction.Inspect })
        } finally { f.scope.cancel() }
    }

    @Test fun installedRuntimeShowsChatAndKeepsManagementCollapsed() = runComposeUiTest {
        val f = Fixture(environment)
        f.snapshot = RuntimeSnapshot(toolsReady = true, hermesInstalled = true, configured = true)
        var connected = false
        try {
            setContent { HermesTheme { LocalRuntimeScreen(f.manager, {}, onConnected = { connected = true }) } }
            waitUntil(timeoutMillis = 5000) { f.manager.state.value.checked && !f.manager.state.value.busy }
            onNodeWithText("安装 Hermes").assertDoesNotExist()
            onNodeWithText("停止 Hermes").assertDoesNotExist()
            onNodeWithText("开始聊天").performClick()
            waitUntil(timeoutMillis = 5000) { connected }
            assertTrue(f.calls.contains(RuntimeAction.StartHermes))
            assertFalse(f.calls.contains(RuntimeAction.InstallHermes))
            onNodeWithText("高级功能").performClick()
            onNodeWithText("停止 Hermes").performScrollTo().assertIsEnabled()
        } finally { f.scope.cancel() }
    }
}

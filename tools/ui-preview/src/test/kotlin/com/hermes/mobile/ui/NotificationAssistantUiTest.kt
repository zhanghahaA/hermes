package com.hermes.mobile.ui

import androidx.compose.ui.test.*
import com.hermes.mobile.data.fake.FakeHermesRepository
import com.hermes.mobile.data.notifications.*
import com.hermes.mobile.ui.settings.NotificationAssistantScreen
import com.hermes.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import com.hermes.mobile.platform.DesktopNotificationApps
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class NotificationAssistantUiTest {
    @Test fun enablingRequiresConfirmationAndMissingSystemAccessDisablesSummary() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = object : NotificationStore {
            var state: NotificationState? = null
            override fun load() = state
            override fun save(state: NotificationState) { this.state = state }
        }
        val assistant = NotificationAssistant(store, sender = { _, _ -> "" })
        try {
            setContent { HermesTheme { NotificationAssistantScreen(FakeHermesRepository(scope), assistant, {}) } }
            onNodeWithText("启用通知助手").performClick()
            assertFalse(assistant.state.value.config.enabled)
            onNodeWithText("开启通知助手？").assertIsDisplayed()
            onNodeWithText("同意并开启").performClick()
            waitForIdle()
            assertTrue(assistant.state.value.config.enabled)
            onNodeWithText("立即总结 / 重试").performScrollTo().assertIsNotEnabled()
            onNodeWithText("允许读取的应用").performScrollTo().performClick()
            waitUntil(timeoutMillis = 5000) { onAllNodesWithText("快递").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("快递").assertIsDisplayed()
            onNodeWithContentDescription("允许读取快递").performClick()
            waitForIdle()
            assertEquals(1, assistant.state.value.config.packages.size)
            onNodeWithText("完成").performClick()
        } finally { scope.cancel() }
    }
    @Test fun firstEntryDoesNotScanAppsAndPickerRemainsResponsiveDuringLoading() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = CompletableDeferred<Unit>()
        DesktopNotificationApps.loads = 0
        DesktopNotificationApps.beforeLoad = { gate.await() }
        val assistant = NotificationAssistant(object : NotificationStore {
            override fun load(): NotificationState? = null
            override fun save(state: NotificationState) = Unit
        }, sender = { _, _ -> "" })
        try {
            setContent { HermesTheme { NotificationAssistantScreen(FakeHermesRepository(scope), assistant, {}) } }
            onNodeWithText("通知助手 · 开发版").assertIsDisplayed()
            assertEquals(0, DesktopNotificationApps.loads, "进入页面时不应扫描应用")
            onNodeWithText("允许读取的应用").performScrollTo().performClick()
            onNodeWithText("正在读取应用列表…").assertIsDisplayed()
            // 后台查询尚未结束，关闭按钮仍能交互，不冻结页面。
            onNodeWithText("完成").performClick()
            onNodeWithText("正在读取应用列表…").assertDoesNotExist()
        } finally {
            gate.complete(Unit)
            DesktopNotificationApps.beforeLoad = {}
            scope.cancel()
        }
    }

}

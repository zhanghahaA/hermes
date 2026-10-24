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
            onNodeWithText("快递").assertIsDisplayed()
            onNodeWithContentDescription("允许读取快递").performClick()
            waitForIdle()
            assertEquals(1, assistant.state.value.config.packages.size)
            onNodeWithText("完成").performClick()
        } finally { scope.cancel() }
    }
}

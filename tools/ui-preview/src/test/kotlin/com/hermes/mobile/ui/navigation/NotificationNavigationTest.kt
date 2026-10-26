package com.hermes.mobile.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.fake.FakeHermesRepository
import com.hermes.mobile.data.notifications.NotificationAssistant
import com.hermes.mobile.data.notifications.NotificationState
import com.hermes.mobile.data.notifications.NotificationStore
import com.hermes.mobile.ui.HermesApp
import com.hermes.mobile.ui.MainRoute
import com.hermes.mobile.ui.NotificationAssistantRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.*

/** 从真正的设置入口进入，验证通知助手复用全局页面栈和连续动画。 */
@OptIn(ExperimentalTestApi::class)
class NotificationNavigationTest {
    private class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val store = object : NotificationStore {
            private var value: NotificationState? = null
            override fun load() = value
            override fun save(state: NotificationState) { value = state }
        }
        private val assistant = NotificationAssistant(store, sender = { _, _ -> "" })
        val repository = object : HermesRepository by FakeHermesRepository(scope) {
            override val notificationAssistant = assistant
        }
        val navigator = Navigator(MainRoute)
    }

    @Test fun settingsEntryAndBackButtonAnimateAndKeepSettingsSelected() = runComposeUiTest {
        val fixture = Fixture()
        try {
            setContent {
                Box(Modifier.size(412.dp, 892.dp).testTag("notification-navigation")) {
                    HermesApp(fixture.repository, fixture.navigator)
                }
            }
            onNodeWithText("设置").performClick()
            onNodeWithText("通知助手 · 开发版").performScrollTo()
            mainClock.autoAdvance = false
            // 直接触发真实入口的点击语义，避免滚到边缘时被悬浮底栏挡住坐标。
            onNodeWithText("通知助手 · 开发版").performSemanticsAction(SemanticsActions.OnClick) { it() }
            mainClock.advanceTimeBy(64)
            assertEquals(NotificationAssistantRoute, fixture.navigator.current)
            assertEquals(2, fixture.navigator.entries.size)
            assertTrue(fixture.navigator.entries.last().offset.value in 0.01f..0.99f,
                "进入动画中间仍应有连续位移")
            mainClock.autoAdvance = true
            waitForIdle()
            assertEquals(0f, fixture.navigator.entries.last().offset.value)
            mainClock.autoAdvance = false
            onNodeWithContentDescription("返回").performClick()
            mainClock.advanceTimeBy(64)
            assertEquals(NotificationAssistantRoute, fixture.navigator.current, "退出动画结束前应保留页面")
            assertTrue(fixture.navigator.entries.last().offset.value in 0.01f..0.99f,
                "返回按钮应驱动同一套连续退出动画")
            mainClock.autoAdvance = true
            waitForIdle()
            assertEquals(MainRoute, fixture.navigator.current)
            onNodeWithText("通知助手 · 开发版").assertIsDisplayed()
        } finally { fixture.scope.cancel() }
    }

    @Test fun notificationPageSwipeCanCancelThenReturnToSettings() = runComposeUiTest {
        val fixture = Fixture()
        try {
            setContent {
                Box(Modifier.size(412.dp, 892.dp).testTag("notification-navigation")) {
                    HermesApp(fixture.repository, fixture.navigator)
                }
            }
            onNodeWithText("设置").performClick()
            onNodeWithText("通知助手 · 开发版").performScrollTo()
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            waitForIdle()
            val host = onNodeWithTag("notification-navigation")
            val width = host.fetchSemanticsNode().size.width.toFloat()
            val height = host.fetchSemanticsNode().size.height.toFloat()
            host.performTouchInput {
                down(Offset(width * 0.15f, height * 0.42f))
                repeat(5) { moveBy(Offset(width * 0.03f, 0f)) }
                repeat(5) { moveBy(Offset(-width * 0.02f, 0f)) }
                up()
            }
            waitForIdle()
            assertEquals(NotificationAssistantRoute, fixture.navigator.current)
            assertEquals(0f, fixture.navigator.entries.last().offset.value)
            host.performTouchInput {
                down(Offset(width * 0.15f, height * 0.42f))
                repeat(14) { moveBy(Offset(width * 0.04f, 0f)) }
            }
            waitForIdle()
            assertTrue(fixture.navigator.entries.last().offset.value in 0.5f..0.6f, "拖动时页面就应跟随手指")
            host.performTouchInput { up() }
            waitForIdle()
            assertEquals(MainRoute, fixture.navigator.current)
            onNodeWithText("通知助手 · 开发版").assertIsDisplayed()
        } finally { fixture.scope.cancel() }
    }
}

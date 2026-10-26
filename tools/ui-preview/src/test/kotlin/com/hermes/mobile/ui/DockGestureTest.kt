package com.hermes.mobile.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.test.*
import com.hermes.mobile.data.fake.FakeHermesRepository
import com.hermes.mobile.ui.navigation.Navigator
import kotlinx.coroutines.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class DockGestureTest {
    @Test fun indicatorFollowsFingerAndCommitsOnlyOnReleaseInActualMainScreen() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val nav = Navigator(MainRoute)
        try {
            setContent { HermesApp(FakeHermesRepository(scope), nav) }
            val dock = onNodeWithTag("tab-pill")
            val width = dock.fetchSemanticsNode().size.width.toFloat()
            val y = dock.fetchSemanticsNode().size.height / 2f
            val before = onNodeWithTag("dock-indicator").getUnclippedBoundsInRoot().left
            dock.performTouchInput {
                down(Offset(width * 0.1f, y))
                repeat(5) { moveBy(Offset(width * 0.07f, 0f)) }
            }
            waitForIdle()
            val during = onNodeWithTag("dock-indicator").getUnclippedBoundsInRoot().left
            assertTrue(during > before, "松手前蓝色背景就应跟随手指")
            onNode(hasText("聊天") and hasAnyAncestor(hasTestTag("tab-pill"))).assertIsSelected()
            assertEquals(MainRoute, nav.current, "Dock 不触发侧滑返回")
            dock.performTouchInput { moveTo(Offset(width * 0.9f, y)); up() }
            waitForIdle()
            onNode(hasText("设置") and hasAnyAncestor(hasTestTag("tab-pill"))).assertIsSelected()
            onNodeWithText("逐字显示回复").assertExists()
            dock.performTouchInput { swipe(Offset(width * 0.9f, y), Offset(width * 0.1f, y), durationMillis = 320) }
            waitForIdle()
            onNode(hasText("聊天") and hasAnyAncestor(hasTestTag("tab-pill"))).assertIsSelected()
        } finally { scope.cancel() }
    }

    @Test fun losingGestureReturnsIndicatorAndTapStillWorks() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val steal = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            setContent {
                // 由父组件抢走已开始的手势，真实触发取消分支；桌面 cancel 注入不能代表 Android 系统取消。
                Box(Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (steal.get()) event.changes.forEach { it.consume() }
                        }
                    }
                }) { HermesApp(FakeHermesRepository(scope), Navigator(MainRoute)) }
            }
            val dock = onNodeWithTag("tab-pill")
            val width = dock.fetchSemanticsNode().size.width.toFloat()
            val y = dock.fetchSemanticsNode().size.height / 2f
            val before = onNodeWithTag("dock-indicator").getUnclippedBoundsInRoot().left
            dock.performTouchInput {
                down(Offset(width * 0.1f, y))
                moveTo(Offset(width * 0.6f, y), delayMillis = 160)
            }
            waitForIdle()
            steal.set(true)
            dock.performTouchInput { moveBy(Offset(10f, 0f)); up() }
            waitForIdle()
            steal.set(false)
            assertEquals(before, onNodeWithTag("dock-indicator").getUnclippedBoundsInRoot().left)
            onNode(hasText("聊天") and hasAnyAncestor(hasTestTag("tab-pill"))).assertIsSelected()
            onNode(hasText("设置") and hasAnyAncestor(hasTestTag("tab-pill"))).performClick()
            onNode(hasText("设置") and hasAnyAncestor(hasTestTag("tab-pill"))).assertIsSelected()
        } finally { scope.cancel() }
    }
}

package com.hermes.mobile.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 用模拟触摸验证返回手势真的跟手、能被打断，而不是松手后才播放。 */
@OptIn(ExperimentalTestApi::class)
class SwipeBackTest {

    private class Ctx(
        val navigator: Navigator,
        val width: Float,
        val touch: (androidx.compose.ui.test.TouchInjectionScope.() -> Unit) -> Unit,
        val idle: () -> Unit,
        val advance: (Long) -> Unit,
        val autoAdvance: (Boolean) -> Unit,
    ) {
        val top get() = navigator.entries.last()
    }

    private fun runCase(case: Ctx.() -> Unit) = runComposeUiTest {
        val navigator = Navigator("root")
        setContent {
            Box(Modifier.size(400.dp, 800.dp).testTag("host")) {
                SwipeBackHost(navigator) { route ->
                    Box(Modifier.fillMaxSize().background(if (route == "root") Color.Gray else Color.White))
                }
            }
        }
        navigator.push("chat")
        waitForIdle()
        val host = onNodeWithTag("host")
        val width = host.fetchSemanticsNode().size.width.toFloat()
        Ctx(navigator, width, { b -> host.performTouchInput(b) }, { waitForIdle() }, { ms -> mainClock.advanceTimeBy(ms) }, { mainClock.autoAdvance = it }).case()
    }

    /** 手指按住拖动时，页面实时跟着手指移动（松手前就已经在动）。 */
    @Test
    fun pageFollowsFingerWhileDragging() = runCase {
        touch {
            down(Offset(100f, 400f))
            repeat(10) { moveBy(Offset(width * 0.03f, 0f)) }
        }
        idle()
        assertEquals(2, navigator.entries.size)
        assertTrue(top.offset.value in 0.25f..0.32f, "拖了 30% 屏宽，页面应该跟到约 0.3，实际 ${top.offset.value}")
        touch {
            repeat(10) { moveBy(Offset(width * 0.03f, 0f)) }
        }
        idle()
        assertTrue(top.offset.value in 0.55f..0.62f, "继续拖到 60%，实际 ${top.offset.value}")
        touch { up() }
        idle()
        assertEquals(1, navigator.entries.size, "拖过一半松手应该完成返回")
    }

    /** 拖一小段又拉回来松手：取消返回，页面弹回原位。 */
    @Test
    fun shortDragCancels() = runCase {
        touch {
            down(Offset(100f, 400f))
            repeat(5) { moveBy(Offset(width * 0.03f, 0f)) }
            repeat(5) { moveBy(Offset(-width * 0.02f, 0f)) }
            up()
        }
        idle()
        assertEquals(2, navigator.entries.size)
        assertEquals(0f, top.offset.value)
    }

    /** 返回动画播到一半时手指按住：动画立刻停下，可以把页面推回去。 */
    @Test
    fun animationCanBeCaughtMidway() = runCase {
        autoAdvance(false)
        navigator.pop()
        advance(64)
        val caughtAt = top.offset.value
        assertTrue(caughtAt in 0.05f..0.95f, "返回动画应该正在进行，实际 $caughtAt")
        touch { down(Offset(width * 0.7f, 400f)) }
        advance(200)
        assertTrue(kotlin.math.abs(top.offset.value - caughtAt) < 0.02f, "按住后应该停住，从 $caughtAt 变成了 ${top.offset.value}")
        touch {
            repeat(10) { moveBy(Offset(-width * 0.05f, 0f)) }
            up()
        }
        autoAdvance(true)
        idle()
        assertEquals(2, navigator.entries.size, "推回去之后不应该返回")
        assertEquals(0f, top.offset.value)
    }

    /** 竖着滑（滚动列表）不会触发返回。 */
    @Test
    fun verticalDragDoesNotGoBack() = runCase {
        touch {
            down(Offset(200f, 600f))
            repeat(10) { moveBy(Offset(3f, -30f)) }
            up()
        }
        idle()
        assertEquals(2, navigator.entries.size)
        assertEquals(0f, top.offset.value)
    }

    /** 快速一甩，即使距离很短也完成返回。 */
    @Test
    fun flingGoesBack() = runCase {
        touch {
            down(Offset(50f, 400f))
            repeat(4) { moveBy(Offset(width * 0.05f, 0f), delayMillis = 10) }
            up()
        }
        idle()
        assertEquals(1, navigator.entries.size)
    }
}

/** 在真实聊天页上（气泡可长按、列表可滚动、代码块可横向滚动）右滑也能返回。 */
@OptIn(ExperimentalTestApi::class)
class SwipeBackOnChatTest {
    @Test
    fun swipeOverChatContent() = runComposeUiTest {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val repo = com.hermes.mobile.data.fake.FakeHermesRepository(scope)
        val navigator = Navigator("root")
        setContent {
            com.hermes.mobile.ui.theme.HermesTheme {
                Box(Modifier.size(412.dp, 892.dp).testTag("host")) {
                    SwipeBackHost(navigator) { route ->
                        if (route == "root") Box(Modifier.fillMaxSize().background(Color.Gray))
                        else com.hermes.mobile.ui.chat.ChatScreen(repo, "s-2", onBack = navigator::pop, onOpenChat = {})
                    }
                }
            }
        }
        navigator.push("chat")
        waitForIdle()
        val host = onNodeWithTag("host")
        val width = host.fetchSemanticsNode().size.width.toFloat()
        val height = host.fetchSemanticsNode().size.height.toFloat()
        // 从屏幕中部（消息气泡上）开始拖
        host.performTouchInput {
            down(Offset(width * 0.3f, height * 0.5f))
            repeat(12) { moveBy(Offset(width * 0.04f, 0f)) }
        }
        waitForIdle()
        val mid = navigator.entries.last().offset.value
        assertTrue(mid in 0.42f..0.5f, "拖了 48% 屏宽，页面应该跟到约 0.48，实际 $mid")
        host.performTouchInput { up() }
        waitForIdle()
        assertEquals(1, navigator.entries.size)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}

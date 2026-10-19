package com.hermes.mobile.ui

import androidx.compose.ui.test.performTextReplacement

import androidx.compose.ui.test.hasText

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.fake.FakeHermesRepository
import com.hermes.mobile.data.model.ChatItem
import com.hermes.mobile.data.model.Role
import androidx.compose.ui.semantics.Role.Companion.Checkbox
import com.hermes.mobile.ui.chat.ChatScreen
import com.hermes.mobile.ui.chats.ChatsScreen
import com.hermes.mobile.ui.main.MainScreen
import com.hermes.mobile.ui.main.MainTab
import com.hermes.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ChatInteractionsTest {

    private fun withRepo(block: ComposeUiTest.(FakeHermesRepository) -> Unit) = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            block(FakeHermesRepository(scope, replyDelayMs = 60_000))
        } finally {
            scope.cancel()
        }
    }

    private fun ComposeUiTest.show(content: @Composable () -> Unit) = setContent {
        HermesTheme { Box(Modifier.size(412.dp, 892.dp)) { content() } }
    }

    // ---- 会话列表左滑 ----

    /** 左滑露出的按钮（排除顶部同名的筛选标签）。 */
    private fun ComposeUiTest.swipeAction(label: String) =
        onAllNodesWithText(label).filterToOne(!SemanticsMatcher.expectValue(SemanticsProperties.Role, Checkbox))

    @Test
    fun chatListSwipeRevealsActionsAndCloses() = withRepo { repo ->
        show { ChatsScreen(repo, onOpenChat = {}, onManageConnections = {}) }
        val title = onNodeWithText("周末去杭州的行程")
        val x0 = title.fetchSemanticsNode().positionInRoot.x

        title.performTouchInput { swipeLeft(startX = centerX + 200f, endX = centerX - 100f, durationMillis = 300) }
        waitForIdle()
        assertTrue(title.fetchSemanticsNode().positionInRoot.x < x0 - 100f, "左滑后这一行应该停在展开位置：$x0 → ${title.fetchSemanticsNode().positionInRoot.x}")
        swipeAction("置顶").assertIsDisplayed().performClick()
        waitForIdle()
        assertTrue(repo.sessions.value.first { it.id == "s-2" }.pinned, "点「置顶」后会话应被置顶")
        assertEquals(x0, onNodeWithText("周末去杭州的行程").fetchSemanticsNode().positionInRoot.x, 0.5f, "执行后这一行应自己收回")

        // 再滑一次依然正常，而且按钮文字变成“取消置顶”，点了能执行
        onNodeWithText("周末去杭州的行程").performTouchInput { swipeLeft(startX = centerX + 200f, endX = centerX - 100f, durationMillis = 300) }
        waitForIdle()
        swipeAction("取消置顶").performClick()
        waitForIdle()
        assertTrue(!repo.sessions.value.first { it.id == "s-2" }.pinned)
        assertEquals(x0, onNodeWithText("周末去杭州的行程").fetchSemanticsNode().positionInRoot.x, 0.5f)
    }

    @Test
    fun chatListSwipeHasInertia() = withRepo { repo ->
        show { ChatsScreen(repo, onOpenChat = {}, onManageConnections = {}) }
        val title = onNodeWithText("周末去杭州的行程")
        val x0 = title.fetchSemanticsNode().positionInRoot.x

        // 慢慢拖一小段就松手：不够一半，弹回去
        title.performTouchInput { swipeLeft(startX = centerX + 200f, endX = centerX + 120f, durationMillis = 900) }
        waitForIdle()
        assertEquals(x0, title.fetchSemanticsNode().positionInRoot.x, 0.5f, "慢拖一小段应该弹回")

        // 同样一小段但甩得快：靠惯性展开
        title.performTouchInput { swipeLeft(startX = centerX + 200f, endX = centerX + 120f, durationMillis = 40) }
        waitForIdle()
        assertTrue(title.fetchSemanticsNode().positionInRoot.x < x0 - 150f, "快速一甩应该展开：$x0 → ${title.fetchSemanticsNode().positionInRoot.x}")

        // 往右甩一下收回
        title.performTouchInput { swipeRight(startX = centerX + 40f, endX = centerX + 100f, durationMillis = 40) }
        waitForIdle()
        assertEquals(x0, title.fetchSemanticsNode().positionInRoot.x, 0.5f, "往右一甩应该收回")
    }

    @Test
    fun chatListArchiveAndUnarchive() = withRepo { repo ->
        show { ChatsScreen(repo, onOpenChat = {}, onManageConnections = {}) }
        onNodeWithText("周末去杭州的行程").performTouchInput { swipeLeft(startX = centerX + 200f, endX = centerX - 100f, durationMillis = 300) }
        waitForIdle()
        swipeAction("归档").assertIsDisplayed().performClick()
        waitForIdle()
        assertTrue(repo.sessions.value.none { it.id == "s-2" })
        assertEquals(listOf("s-2"), repo.archivedSessions.value.map { it.id })
        assertTrue(onAllNodesWithText("周末去杭州的行程").fetchSemanticsNodes().isEmpty(), "归档后不在列表里")

        // “已归档”里能找到，左滑可以取消归档
        onNodeWithText("已归档").performClick()
        waitForIdle()
        onNodeWithText("周末去杭州的行程").performTouchInput { swipeLeft(startX = centerX + 200f, endX = centerX - 100f, durationMillis = 300) }
        waitForIdle()
        swipeAction("取消归档").performClick()
        waitForIdle()
        assertTrue(repo.archivedSessions.value.isEmpty())
        assertTrue(repo.sessions.value.any { it.id == "s-2" })
    }

    @Test
    fun botScreenTakeOverAndHandBack() = withRepo { repo ->
        show { com.hermes.mobile.ui.admin.BotScreenScreen(repo, onBack = {}) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("bot 在控制，你在看").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithText("Ctrl").fetchSemanticsNodes().isEmpty(), "只看时没有按键栏")
        onNodeWithText("接管").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("你在控制（bot 暂停操作屏幕）").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Ctrl").assertIsDisplayed()
        onNodeWithText("交还").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("bot 在控制，你在看").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun chatListOpenRowClosesOnTap() = withRepo { repo ->
        var opened: String? = null
        show { ChatsScreen(repo, onOpenChat = { opened = it }, onManageConnections = {}) }
        val title = onNodeWithText("整理照片脚本")
        val x0 = title.fetchSemanticsNode().positionInRoot.x
        title.performTouchInput { swipeLeft(startX = centerX + 200f, endX = centerX - 100f, durationMillis = 300) }
        waitForIdle()
        title.performClick()
        waitForIdle()
        assertNull(opened, "展开时点内容只收回，不打开会话")
        assertEquals(x0, title.fetchSemanticsNode().positionInRoot.x, 0.5f)
        title.performClick()
        waitForIdle()
        assertEquals("s-3", opened)
    }

    // ---- 底栏宽度动画 ----

    @Test
    fun dockResizesWithAnimation() = withRepo { repo ->
        show { MainScreen(repo, onOpenChat = {}, onEditConnection = {}, onEditJob = {}, onOpenWelcome = {}) }
        val pill = onNodeWithTag("tab-pill")
        val start = pill.getBoundsInRoot().let { it.right - it.left }
        mainClock.autoAdvance = false
        onNodeWithText("能力").performClick()
        mainClock.advanceTimeBy(96)
        val mid = pill.getBoundsInRoot().let { it.right - it.left }
        mainClock.autoAdvance = true
        waitForIdle()
        val end = pill.getBoundsInRoot().let { it.right - it.left }
        assertTrue(end > start, "切到没有右侧按钮的页面，胶囊应该变长")
        assertTrue(mid > start && mid < end, "动画进行中宽度应在两者之间：$start → $mid → $end")
    }

    // ---- 引用 ----

    private fun ComposeUiTest.lastUserMessage(repo: FakeHermesRepository, session: String) =
        runBlocking { repo.chatItems(session).first() }.filterIsInstance<ChatItem.Message>().last { it.role == Role.User }

    private val reply = "第二天上午建议先去灵隐寺，人少一些。"

    private fun ComposeUiTest.swipeLeftOn(text: String) =
        onNodeWithText(text, substring = true)
            .performTouchInput { swipeLeft(startX = centerX + 100f, endX = centerX - 200f, durationMillis = 250) }

    @Test
    fun swipeLeftOnHermesReplyQuotesIt() = withRepo { repo ->
        show { ChatScreen(repo, "s-2", onBack = {}, onOpenChat = {}) }
        swipeLeftOn(reply)
        waitForIdle()
        onNodeWithText("针对这段追问…").assertIsDisplayed()
        onNodeWithContentDescription("取消引用").assertIsDisplayed().performClick()
        waitForIdle()
        onNodeWithText("给 Hermes 发消息").assertIsDisplayed()
    }

    @Test
    fun swipeOnMyOwnMessageDoesNotQuote() = withRepo { repo ->
        show { ChatScreen(repo, "s-2", onBack = {}, onOpenChat = {}) }
        swipeLeftOn("周末想去杭州玩两天")
        waitForIdle()
        assertEquals(0, onAllNodesWithText("针对这段追问…").fetchSemanticsNodes().size, "自己的消息不能引用")
    }

    @Test
    fun quotedReplyIsSentWithMessage() = withRepo { repo ->
        show { ChatScreen(repo, "s-2", onBack = {}, onOpenChat = {}) }
        swipeLeftOn(reply)
        waitForIdle()
        onNode(hasSetTextAction()).performTextInput("那里需要预约吗？")
        onNodeWithContentDescription("发送").performClick()
        waitForIdle()
        val sent = lastUserMessage(repo, "s-2")
        assertEquals("那里需要预约吗？", sent.text)
        val quote = assertNotNull(sent.quote, "发出的消息应该带着引用")
        val original = runBlocking { repo.chatItems("s-2").first() }.filterIsInstance<ChatItem.Message>().first { it.id == quote.messageId }
        assertEquals(Role.Assistant, original.role, "只能引用 Hermes 的回复")
        assertEquals(original.text.trim(), quote.text, "引用的是回复正文本身")
        assertTrue(!quote.partial)
        // 发送后引用清空（Hermes 还在回复，输入框提示变成“补充一句”）
        onNodeWithText("针对这段追问…").assertDoesNotExist()
        onNodeWithText("补充一句", substring = true).assertIsDisplayed()
    }

    @Test
    fun selectTextThenAskFollowUp() = withRepo { repo ->
        show { ChatScreen(repo, "s-2", onBack = {}, onOpenChat = {}) }
        onNodeWithText(reply, substring = true).performTouchInput { longClick(Offset(width * 0.3f, height * 0.5f)) }
        waitForIdle()
        onNodeWithText("追问").assertIsDisplayed().performClick()
        waitForIdle()
        onNodeWithText("Hermes · 片段").assertIsDisplayed()
        onNodeWithText("针对这段追问…").assertIsDisplayed()
        assertEquals(0, onAllNodesWithText("追问").fetchSemanticsNodes().size, "追问后选择操作栏应该收起")
    }

    @Test
    fun selectingMyOwnTextOffersCopyButNotAsk() = withRepo { repo ->
        show { ChatScreen(repo, "s-2", onBack = {}, onOpenChat = {}) }
        onNodeWithText("周末想去杭州玩两天", substring = true).performTouchInput { longClick(Offset(width * 0.3f, height * 0.5f)) }
        waitForIdle()
        onNodeWithContentDescription("复制").assertIsDisplayed()
        assertEquals(0, onAllNodesWithText("追问").fetchSemanticsNodes().size)
    }

    @Test
    fun searchInChatFindsAndStepsThroughMatches() = withRepo { repo ->
        show { ChatScreen(repo, "s-2", onBack = {}, onOpenChat = {}) }
        onNodeWithContentDescription("搜索").performClick()
        waitForIdle()
        onNode(hasSetTextAction() and hasText("搜索这个对话")).performTextInput("两")
        waitForIdle()
        onNodeWithText("1/2").assertIsDisplayed()
        onNodeWithContentDescription("上一条（更早）").performClick()
        waitForIdle()
        onNodeWithText("2/2").assertIsDisplayed()
        onNode(hasSetTextAction() and hasText("两")).performTextReplacement("不存在的词")
        waitForIdle()
        onNodeWithText("无结果").assertIsDisplayed()
        onNodeWithContentDescription("结束搜索").performClick()
        waitForIdle()
        onNodeWithText("周末去杭州的行程").assertIsDisplayed()
    }
}

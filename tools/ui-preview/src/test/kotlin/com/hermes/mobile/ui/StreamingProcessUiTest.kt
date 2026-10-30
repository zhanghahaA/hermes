package com.hermes.mobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.fake.FakeHermesRepository
import com.hermes.mobile.data.model.*
import com.hermes.mobile.ui.chat.ChatScreen
import com.hermes.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class StreamingProcessUiTest {
    @Test fun repliesAndDotsCoexistAndProcessCanCollapseWithoutHidingReply() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val base = FakeHermesRepository(scope)
        base.updateSettings { it.copy(streamReplies = true) }
        val list = MutableStateFlow<List<ChatItem>>(listOf(
            ChatItem.Message("u", 1, Role.User, "帮我研究一下"),
            ChatItem.Activity("a", 2, steps = listOf(WorkStep("t", WorkStep.Kind.Tool, "terminal", "正在读取项目"))),
        ))
        val text = MutableStateFlow<String?>("第一句保留。\n\n第二句接着出现。")
        val phase = MutableStateFlow(RunPhase.Working)
        val repo = object : HermesRepository by base {
            override fun chatItems(sessionId: String) = list
            override fun streamingText(sessionId: String) = text
            override fun runPhase(sessionId: String) = phase
        }
        try {
            setContent { HermesTheme { ChatScreen(repo, "s-1", {}, {}) } }
            onNodeWithText("第一句保留。", substring = true).assertIsDisplayed()
            onNodeWithText("第二句接着出现。", substring = true).assertIsDisplayed()
            onNodeWithTag("chat-typing").assertIsDisplayed()
            onNodeWithTag("work-process-a").assertDoesNotExist()
            base.updateSettings { it.copy(showWorkProcess = true) }
            onNodeWithText("正在读取项目").assertIsDisplayed()
            onNodeWithContentDescription("收起工作过程").performClick()
            onNodeWithText("正在读取项目").assertDoesNotExist()
            onNodeWithText("第一句保留。", substring = true).assertIsDisplayed()
            onNodeWithTag("chat-typing").assertIsDisplayed()
            onNodeWithContentDescription("展开工作过程").performClick()
            onNodeWithText("正在读取项目").assertIsDisplayed()
            base.updateSettings { it.copy(showTyping = false) }
            onNodeWithTag("chat-typing").assertDoesNotExist()
        } finally { scope.cancel() }
    }

    /** 收起后离开聊天页再进来，还是收起的；结束的过程默认收起，点开工具步骤能看到结果。 */
    @Test fun foldingSurvivesLeavingTheChatAndFinishedProcessStartsCollapsed() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val base = FakeHermesRepository(scope)
        base.updateSettings { it.copy(showWorkProcess = true) }
        val working = ChatItem.Activity("fold-a", 2, steps = listOf(
            WorkStep("t", WorkStep.Kind.Tool, "terminal", "ls -la", finished = true, output = "total 42", durationMs = 1200),
        ))
        val list = MutableStateFlow<List<ChatItem>>(listOf(ChatItem.Message("u", 1, Role.User, "看看目录"), working))
        val repo = object : HermesRepository by base {
            override fun chatItems(sessionId: String) = list
            override fun runPhase(sessionId: String) = MutableStateFlow(RunPhase.Working)
        }
        var open by mutableStateOf(true)
        try {
            setContent { HermesTheme { if (open) ChatScreen(repo, "s-1", {}, {}) } }
            onNodeWithText("运行命令").assertIsDisplayed()
            onNodeWithText("1.2 秒").assertIsDisplayed()
            onNodeWithTag("work-process-fold-a").performClick()
            onNodeWithText("运行命令").assertDoesNotExist()
            open = false
            waitForIdle()
            open = true
            onNodeWithTag("work-process-fold-a").assertIsDisplayed()
            onNodeWithText("运行命令").assertDoesNotExist()

            list.value = listOf(ChatItem.Message("u", 1, Role.User, "看看目录"),
                working.copy(id = "fold-b", status = ActivityStatus.Completed, finishedAt = 5_002))
            onNodeWithText("已完成 1 个步骤 · 5.0 秒").assertIsDisplayed()
            onNodeWithText("运行命令").assertDoesNotExist()
            onNodeWithTag("work-process-fold-b").performClick()
            onNodeWithText("total 42").assertDoesNotExist()
            onNodeWithContentDescription("运行命令").performClick()
            onNodeWithText("total 42").assertIsDisplayed()
        } finally { scope.cancel() }
    }

    /** 逐字回复时往上翻：新内容不能把正在看的地方往上推；停在底部时照旧跟着新内容走。 */
    @Test fun scrolledUpReaderIsNotPushedByStreamingButBottomStillFollows() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val base = FakeHermesRepository(scope)
        base.updateSettings { it.copy(streamReplies = true) }
        val list = MutableStateFlow<List<ChatItem>>((1..12).map { i ->
            ChatItem.Message("m$i", i.toLong(), if (i % 2 == 0) Role.Assistant else Role.User, "历史消息 $i")
        })
        val paragraphs = (1..30).map { "第 $it 段正在生成的回复内容。" }
        val text = MutableStateFlow<String?>(paragraphs.joinToString("\n\n"))
        val repo = object : HermesRepository by base {
            override fun chatItems(sessionId: String) = list
            override fun streamingText(sessionId: String) = text
            override fun runPhase(sessionId: String) = MutableStateFlow(RunPhase.Working)
        }
        fun top(label: String) = onNodeWithText(label, substring = true).getUnclippedBoundsInRoot().top.value
        fun grow(n: Int) {
            text.value = text.value + (1..n).joinToString("") { "\n\n又生成了一段新的内容 $it。" }
            waitForIdle()
        }
        try {
            setContent { HermesTheme { ChatScreen(repo, "s-1", {}, {}) } }
            waitForIdle()
            // 在底部：跟着新内容走，看得见的内容往上移
            val label = "第 30 段"
            val before = top(label)
            grow(3)
            assertTrue(top(label) < before - 10, "停在底部时应该跟着新内容走")

            // 往上翻一点（还在这条回复里）：之后再生成，正在看的位置不动
            onRoot().performTouchInput { swipe(center, center.copy(y = center.y + 300f), durationMillis = 800) }
            waitForIdle()
            val reading = top(label)
            repeat(4) { grow(2) }
            assertEquals(reading, top(label), 0.5f, "翻上去以后，新内容不能把正在看的内容推走")
        } finally { scope.cancel() }
    }
}

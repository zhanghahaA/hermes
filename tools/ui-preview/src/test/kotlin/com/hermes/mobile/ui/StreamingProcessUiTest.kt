package com.hermes.mobile.ui

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
}

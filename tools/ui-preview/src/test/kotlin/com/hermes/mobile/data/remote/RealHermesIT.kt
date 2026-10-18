package com.hermes.mobile.data.remote

import com.hermes.mobile.data.model.ConnectionProfile
import com.hermes.mobile.data.model.ConnectionState
import com.hermes.mobile.data.model.ConnectionType
import com.hermes.mobile.data.model.TestStepStatus
import com.hermes.mobile.platform.FakeSecretBox
import com.hermes.mobile.platform.MemoryLocalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.hermes.mobile.data.model.ChatItem
import com.hermes.mobile.data.model.Role
import com.hermes.mobile.data.model.RunPhase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 对真实运行的 Hermes 做集成测试。只有设置了环境变量才会跑：
 *   HERMES_IT_HOST=127.0.0.1 HERMES_IT_PORT=8642 HERMES_IT_KEY=... gradle test
 */
class RealHermesIT {
    private val host = System.getenv("HERMES_IT_HOST")
    private val port = System.getenv("HERMES_IT_PORT")?.toIntOrNull() ?: 8642
    private val key = System.getenv("HERMES_IT_KEY")

    @Test
    fun stageOneAgainstRealHermes() {
        if (host == null || key == null) {
            println("跳过：没有设置 HERMES_IT_HOST / HERMES_IT_KEY")
            return
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // App 还不能新建会话（阶段 2），先直接用接口建一个
            val title = "IT-${System.currentTimeMillis()}"
            OkHttpClient().newCall(
                Request.Builder().url("http://$host:$port/api/sessions")
                    .header("Authorization", "Bearer $key")
                    .post("""{"title":"$title"}""".toRequestBody("application/json".toMediaType()))
                    .build(),
            ).execute().use { assertTrue(it.isSuccessful, "建会话失败：${it.code}") }

            val repo = RemoteHermesRepository(scope, MemoryLocalStore(), FakeSecretBox())
            val profile = ConnectionProfile("real", "真实 Hermes", ConnectionType.Direct, host, port)

            val steps = runBlocking { withTimeout(15_000) { repo.testConnection(profile, key).toList().last() } }
            println("测试连接：" + steps.joinToString { "${it.title}=${it.status}(${it.detail ?: ""})" })
            assertTrue(steps.all { it.status == TestStepStatus.Passed }, steps.toString())

            repo.saveConnection(profile, key)
            val state = runBlocking { withTimeout(15_000) { repo.connectionState.first { it !is ConnectionState.Connecting && it != ConnectionState.Disconnected } } }
            assertTrue(state is ConnectionState.Connected, state.toString())

            val session = runBlocking { withTimeout(10_000) { repo.sessions.first { list -> list.any { it.title == title } } } }.first { it.title == title }
            println("读到会话：${session.id} / ${session.title} / ${session.source.label}")

            repo.renameSession(session.id, "$title-改名")
            repo.togglePin(session.id)
            Thread.sleep(1500)
            runBlocking { repo.refreshSessions() }
            val updated = repo.sessions.value.first { it.id == session.id }
            assertEquals("$title-改名", updated.title, "改名要真正写到服务器")
            assertTrue(updated.pinned, "置顶要真正写到服务器")

            val items = runBlocking { withTimeout(10_000) { repo.chatItems(session.id).first() } }
            assertTrue(items.isEmpty(), "新会话没有消息")

            repo.deleteSession(session.id)
            Thread.sleep(1500)
            runBlocking { repo.refreshSessions() }
            assertTrue(repo.sessions.value.none { it.id == session.id }, "删除要真正写到服务器")
        } finally {
            scope.cancel()
        }
    }

    /** 阶段 2：配合 tools/fake-llm 的剧本，发消息、审批、追问、停止，全程不能出现思考内容。 */
    @Test
    fun stageTwoChatAgainstRealHermes() {
        if (host == null || key == null) {
            println("跳过：没有设置 HERMES_IT_HOST / HERMES_IT_KEY")
            return
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = RemoteHermesRepository(scope, MemoryLocalStore(), FakeSecretBox())
            repo.updateSettings { it.copy(streamReplies = true) }
            repo.saveConnection(ConnectionProfile("real", "真实 Hermes", ConnectionType.Direct, host, port), key)
            runBlocking { withTimeout(15_000) { repo.connectionState.first { it is ConnectionState.Connected } } }
            assertTrue(com.hermes.mobile.data.model.AppFeature.SendMessages in repo.features.value)

            val chat = assertNotNullOr(repo.createSession(), "新建会话")
            val seenText = java.util.Collections.synchronizedList(mutableListOf<String>())
            scope.launch { repo.streamingText(chat).collect { it?.let(seenText::add) } }
            scope.launch { repo.chatItems(chat).collect { list -> list.filterIsInstance<ChatItem.Message>().forEach { seenText += it.text } } }

            fun awaitItems(what: String, timeoutMs: Long = 60_000, predicate: (List<ChatItem>) -> Boolean): List<ChatItem> = runBlocking {
                try {
                    withTimeout(timeoutMs) { repo.chatItems(chat).first(predicate) }
                } catch (e: Exception) {
                    throw AssertionError("等不到：$what；现在是 ${repo.chatItems(chat).first()}", e)
                }
            }
            fun replies(list: List<ChatItem>) = list.filterIsInstance<ChatItem.Message>().filter { it.role == Role.Assistant }.map { it.text }

            // 1. 普通消息
            repo.sendMessage(chat, "你好", emptyList())
            awaitItems("第一条回复") { "收到：你好" in replies(it) }
            println("1. 回复：" + replies(runBlocking { repo.chatItems(chat).first() }))

            // 2. 审批
            repo.sendMessage(chat, "帮我运行命令", emptyList())
            val approval = awaitItems("审批卡片") { list -> list.any { it is ChatItem.Approval && it.resolvedChoice == null } }
                .filterIsInstance<ChatItem.Approval>().first { it.resolvedChoice == null }
            println("2. 审批卡片：${approval.summary} / ${approval.command} / ${approval.choices}")
            assertEquals(listOf("once", "session", "always", "deny"), approval.choices, "选项顺序统一")
            repo.resolveApproval(chat, approval.id, "once")
            awaitItems("批准后的回复") { "命令执行完了。" in replies(it) }

            // 3. 追问
            // Hermes 默认不给 API Server 追问工具；config.yaml 里 platform_toolsets.api_server 加上 clarify 才会有
            repo.sendMessage(chat, "追问我一下", emptyList())
            val repliesBefore = replies(runBlocking { repo.chatItems(chat).first() }).size
            val clarify = runBlocking {
                withTimeoutOrNull(30_000) {
                    repo.chatItems(chat).first { list -> list.any { it is ChatItem.Clarify && it.answer == null } || replies(list).size > repliesBefore }
                }
            }?.filterIsInstance<ChatItem.Clarify>()?.firstOrNull { it.answer == null }
            if (clarify != null) {
                println("3. 追问卡片：${clarify.question} / ${clarify.options}")
                repo.answerClarify(chat, clarify.id, "蓝色")
                awaitItems("回答追问后的回复") { "好的，就按你选的来。" in replies(it) }
                println("3. 回答后：${replies(runBlocking { repo.chatItems(chat).first() }).last()}")
            } else {
                println("3. 这个 Hermes 没有把追问转给 App（回复：${replies(runBlocking { repo.chatItems(chat).first() }).lastOrNull()}）")
            }
            runBlocking { withTimeout(60_000) { repo.runPhase(chat).first { it == RunPhase.Idle } } }

            // 4. 停止
            repo.sendMessage(chat, "慢慢说", emptyList())
            runBlocking { withTimeout(20_000) { repo.streamingText(chat).first { !it.isNullOrBlank() } } }
            repo.stop(chat)
            runBlocking { withTimeout(30_000) { repo.runPhase(chat).first { it == RunPhase.Idle } } }
            val after = runBlocking { repo.chatItems(chat).first() }
            println("4. 停止后最后一条：${(after.lastOrNull() as? ChatItem.Message)?.text?.take(40)}")

            Thread.sleep(1500)
            val leaked = seenText.filter { "SECRET" in it }
            assertTrue(leaked.isEmpty(), "思考内容漏出来了：$leaked")
            println("全程看到 ${seenText.size} 段文字，没有思考内容")

            val serverId = assertNotNullOr(runBlocking { repo.session(chat).first() }?.id, "会话")
            repo.deleteSession(serverId)
            Thread.sleep(1000)
        } finally {
            scope.cancel()
        }
    }

    private fun <T : Any> assertNotNullOr(value: T?, what: String): T = value ?: throw AssertionError("$what 为空")

    /**
     * 接真实大模型的 Hermes（HERMES_IT_REAL_MODEL=1）：回复能到；模型真的思考过时，思考内容不出现在 App 里。
     * 审批要看模型愿不愿意调工具，所以只打印不强制。
     */
    @Test
    fun realModelReplyNeverShowsReasoning() {
        if (host == null || key == null || System.getenv("HERMES_IT_REAL_MODEL") == null) {
            println("跳过：没有设置 HERMES_IT_REAL_MODEL")
            return
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = RemoteHermesRepository(scope, MemoryLocalStore(), FakeSecretBox())
            repo.updateSettings { it.copy(streamReplies = true) }
            repo.saveConnection(ConnectionProfile("real", "真实 Hermes", ConnectionType.Direct, host, port), key)
            runBlocking { withTimeout(15_000) { repo.connectionState.first { it is ConnectionState.Connected } } }
            val chat = assertNotNullOr(repo.createSession(), "新建会话")
            val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
            scope.launch { repo.streamingText(chat).collect { it?.let(seen::add) } }
            scope.launch { repo.chatItems(chat).collect { list -> list.filterIsInstance<ChatItem.Message>().forEach { seen += it.text } } }
            fun replies() = runBlocking { repo.chatItems(chat).first() }.filterIsInstance<ChatItem.Message>().filter { it.role == Role.Assistant }

            repo.sendMessage(chat, "先想一想：17 乘以 23 等于多少？只回答数字。", emptyList())
            runBlocking { withTimeout(120_000) { repo.chatItems(chat).first { list -> list.any { it is ChatItem.Message && it.role == Role.Assistant } } } }
            runBlocking { withTimeout(120_000) { repo.runPhase(chat).first { it == RunPhase.Idle } } }
            println("真实模型回复：${replies().map { it.text }}")
            assertTrue(replies().any { "391" in it.text })

            repo.sendMessage(chat, "请用 terminal 工具执行这条命令：rm -rf /tmp/hermes-mobile-test-dir ，然后用一句话告诉我结果。", emptyList())
            val card = runBlocking {
                withTimeoutOrNull(120_000) {
                    repo.chatItems(chat).first { list -> list.any { it is ChatItem.Approval && it.resolvedChoice == null } || list.count { it is ChatItem.Message && it.role == Role.Assistant } >= 2 }
                }
            }?.filterIsInstance<ChatItem.Approval>()?.firstOrNull { it.resolvedChoice == null }
            if (card != null) {
                println("真实模型触发审批：${card.summary} / ${card.command}")
                repo.resolveApproval(chat, card.id, "deny")
            } else {
                println("模型没有触发审批")
            }
            runBlocking { withTimeout(120_000) { repo.runPhase(chat).first { it == RunPhase.Idle } } }
            Thread.sleep(2000)
            println("最后回复：${replies().lastOrNull()?.text}")

            // 服务器记录里的思考内容（App 解析时丢弃），一个字都不能出现在 App 看到的文字里
            val serverId = assertNotNullOr(runBlocking { repo.session(chat).first() }?.id, "会话")
            val raw = OkHttpClient().newCall(
                Request.Builder().url("http://$host:$port/api/sessions/$serverId/messages").header("Authorization", "Bearer $key").build(),
            ).execute().use { it.body.string() }
            val reasoning = Regex("\"reasoning(?:_content)?\": \"((?:[^\"\\\\]|\\\\.){12,})\"").findAll(raw).map { it.groupValues[1] }.toList()
            println("服务器上的思考片段：${reasoning.size} 段")
            val leaked = reasoning.filter { r -> val probe = r.take(24); seen.any { probe in it } }
            assertTrue(leaked.isEmpty(), "思考内容漏出来了：$leaked")
            println("App 一共看到 ${seen.size} 段文字，没有思考内容")
            repo.deleteSession(serverId)
            Thread.sleep(1000)
        } finally {
            scope.cancel()
        }
    }
}

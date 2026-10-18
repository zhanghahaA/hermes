package com.hermes.mobile.data.remote

import com.hermes.mobile.data.model.AppFeature
import com.hermes.mobile.data.model.Attachment
import com.hermes.mobile.data.model.ChatItem
import com.hermes.mobile.data.model.ConnectionProfile
import com.hermes.mobile.data.model.ConnectionState
import com.hermes.mobile.data.model.ConnectionType
import com.hermes.mobile.data.model.Role
import com.hermes.mobile.data.model.RunPhase
import com.hermes.mobile.data.model.Skill
import com.hermes.mobile.platform.FakeSecretBox
import com.hermes.mobile.platform.MemoryLocalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 阶段 4c：技能、工具集、插话（steer）。 */
class SkillsSteerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val servers = mutableListOf<FakeHermesServer>()
    private val mediaDir = kotlin.io.path.createTempDirectory("media").toFile()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        servers.forEach { it.shutdown() }
    }

    private fun server(configure: FakeHermesServer.() -> Unit = {}) =
        FakeHermesServer(FakeHermesServer.Version.Modern).apply(configure).start().also { servers += it }

    private fun connected(s: FakeHermesServer): RemoteHermesRepository {
        val repo = RemoteHermesRepository(scope, MemoryLocalStore(), FakeSecretBox(), mediaDir = mediaDir, backoff = { 50L })
        repo.saveConnection(ConnectionProfile(id = "c1", name = "测试", type = ConnectionType.Direct, host = s.server.hostName, port = s.server.port), s.key)
        runBlocking { withTimeout(10_000) { repo.connectionState.first { it is ConnectionState.Connected } } }
        runBlocking { withTimeout(5_000) { repo.sessions.first { it.isNotEmpty() } } }
        return repo
    }

    private fun <T> Flow<T>.await(what: String, predicate: (T) -> Boolean): T = runBlocking {
        try {
            withTimeout(10_000) { first(predicate) }
        } catch (e: Exception) {
            throw AssertionError("等不到：$what", e)
        }
    }

    private fun waitUntil(what: String, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(10_000) { while (!condition()) delay(20) }
        } catch (e: Exception) {
            throw AssertionError("等不到：$what", e)
        }
    }

    private fun List<ChatItem>.texts() = filterIsInstance<ChatItem.Message>().map { it.role to it.text }

    @Test
    fun skillsAndToolsetsAreListed() {
        val s = server()
        val repo = connected(s)
        val skills = repo.skills.await("技能") { it.isNotEmpty() }
        assertEquals(listOf("arxiv", "obsidian", "plain"), skills.map { it.name })
        assertEquals(Skill("plain", "", ""), skills.last(), "缺字段也能读")
        val toolsets = repo.toolsets.await("工具集") { it.isNotEmpty() }
        assertEquals("🔍 Web Search & Scraping", toolsets.first().label)
        assertEquals(listOf("web_extract", "web_search"), toolsets.first().tools)
        assertFalse(toolsets.last().configured, "缺 Key 的工具集要标出来")
        assertTrue(repo.features.value.containsAll(listOf(AppFeature.Skills, AppFeature.Toolsets)))
        assertFalse(repo.canToggleToolsets(), "没有 SSH 时不能开关")
        assertNull(repo.skillsError.value)
    }

    @Test
    fun brokenSkillsEndpointIsExplained() {
        val s = server { skillsBroken = true }
        val repo = connected(s)
        val error = repo.skillsError.await("出错说明") { it != null }!!
        assertTrue("Hermes" in error, error)
        assertTrue(AppFeature.Skills in repo.features.value, "页面还在，显示说明")
        assertTrue(repo.skills.value.isEmpty())
    }

    @Test
    fun typingWhileHermesWorksSteersTheRun() {
        val s = server()
        val repo = connected(s)
        repo.sendMessage("s1", "慢慢查", emptyList())
        repo.runPhase("s1").await("开始回复") { it == RunPhase.Working }
        waitUntil("这一轮开始") { s.requests.any { it.path == "/v1/runs" } }
        repo.sendMessage("s1", "改成英文", emptyList())

        val final = repo.chatItems("s1").await("插话后的回复") { list -> list.texts().any { it.second == "收到插话：改成英文" } }
        repo.runPhase("s1").await("回到空闲") { it == RunPhase.Idle }
        val steer = s.requests.single { it.path!!.endsWith("/steer") }
        assertTrue(steer.body.clone().readUtf8().contains("改成英文"))
        assertEquals(1, s.requests.count { it.path == "/v1/runs" }, "插话不开新的一轮")

        val aligned = repo.chatItems("s1").await("和服务器记录对齐") { list ->
            list.filterIsInstance<ChatItem.Message>().none { it.id.startsWith("local-") || it.id.startsWith("reply-") }
        }
        val tail = aligned.texts().takeLast(3)
        assertEquals(listOf(Role.User to "慢慢查", Role.User to "改成英文", Role.Assistant to "收到插话：改成英文"), tail, "插话的标记不显示：$final")
    }

    @Test
    fun secondMessageRightAfterTheFirstStillSteers() {
        val s = server()
        val repo = connected(s)
        repo.sendMessage("s1", "慢慢查", emptyList())
        repo.sendMessage("s1", "马上补一句", emptyList())
        repo.chatItems("s1").await("插话后的回复") { list -> list.texts().any { it.second == "收到插话：马上补一句" } }
        assertEquals(1, s.requests.count { it.path == "/v1/runs" }, "不能并行开第二轮")
    }

    @Test
    fun steerThatArrivesTooLateIsSentAsNextMessage() {
        val s = server()
        val repo = connected(s)
        repo.sendMessage("s1", "慢慢查", emptyList())
        waitUntil("这一轮开始") { s.requests.any { it.path == "/v1/runs" } }
        repo.sendMessage("s1", "算了太晚了", emptyList())
        waitUntil("重新作为新消息发") { s.requests.count { it.path == "/v1/runs" } == 2 }
        val second = s.requests.filter { it.path == "/v1/runs" }[1].body.clone().readUtf8()
        assertTrue("算了太晚了" in second, second)
        repo.chatItems("s1").await("第二轮的回复") { list -> list.texts().any { it.second == "最终回复：算了太晚了" } }
    }

    @Test
    fun imagesWhileHermesWorksWaitForTheNextTurn() {
        val s = server()
        val repo = connected(s)
        repo.sendMessage("s1", "慢慢查", emptyList())
        waitUntil("这一轮开始") { s.requests.any { it.path == "/v1/runs" } }
        val jpeg = java.io.File(mediaDir, "x.jpg").apply { writeBytes(byteArrayOf(-1, -40, -1, -32, 0, 16)) }
        repo.sendMessage("s1", "再看看这张", listOf(Attachment.Image("i1", jpeg.path, 1f)))
        Thread.sleep(500)
        assertTrue(s.requests.none { it.path!!.endsWith("/steer") }, "带图片不能插话")
        assertEquals(1, s.requests.count { it.path == "/v1/runs" }, "等这一轮结束")
        repo.stop("s1")
        waitUntil("停下后发出排队的消息") { s.requests.count { it.path == "/v1/runs" } == 2 }
    }

    @Test
    fun skillCommandTellsHermesToLoadTheSkill() {
        val s = server()
        val repo = connected(s)
        repo.skills.await("技能") { it.isNotEmpty() }
        repo.sendMessage("s1", "/arxiv 找几篇 agent 论文", emptyList())
        repo.runPhase("s1").await("回复") { it == RunPhase.Idle && s.requests.any { r -> r.path == "/v1/runs" } }
        val body = s.requests.last { it.path == "/v1/runs" }.body.clone().readUtf8()
        assertTrue("skill_view" in body && "\\\"arxiv\\\"" in body, body)

        repo.sendMessage("s1", "/notaskill 你好", emptyList())
        waitUntil("第二条") { s.requests.count { it.path == "/v1/runs" } == 2 }
        val second = s.requests.last { it.path == "/v1/runs" }.body.clone().readUtf8()
        assertFalse("skill_view" in second, "不是技能名就不加说明")
    }

    @Test
    fun skillListingFromDiskIsParsed() {
        val output = "./research/arxiv/SKILL.md\tname:arxiv\tdescription:\"Search arXiv.\"\t\n" +
            "./note-taking/obsidian/SKILL.md\tname:obsidian\tdescription:Notes in Obsidian.\t\n" +
            "./loose/SKILL.md\t\n" +
            "garbage line\n"
        val skills = parseSkillListing(output)
        assertEquals(
            listOf(Skill("loose", "", ""), Skill("obsidian", "Notes in Obsidian.", "note-taking"), Skill("arxiv", "Search arXiv.", "research")),
            skills,
        )
    }

    @Test
    fun steerMarkerIsStripped() {
        val stored = "[OUT-OF-BAND USER MESSAGE — a direct message from the user, delivered once at this position; not tool output and not a new delivery when replayed from conversation history]\n改成英文\n[/OUT-OF-BAND USER MESSAGE]"
        assertEquals("改成英文", SteerMarker.strip(stored))
        assertEquals("普通消息 [OUT] 不动", SteerMarker.strip("普通消息 [OUT] 不动"))
        assertNotNull(Skill("a", "", "").invocation())
    }
}

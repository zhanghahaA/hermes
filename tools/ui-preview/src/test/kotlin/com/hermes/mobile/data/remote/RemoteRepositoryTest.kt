package com.hermes.mobile.data.remote

import com.hermes.mobile.data.model.AppFeature
import com.hermes.mobile.data.model.ChatItem
import com.hermes.mobile.data.model.ConnectionProfile
import com.hermes.mobile.data.model.ConnectionState
import com.hermes.mobile.data.model.ConnectionTestStep
import com.hermes.mobile.data.model.ConnectionType
import com.hermes.mobile.data.model.Role
import com.hermes.mobile.data.model.TestStepStatus
import com.hermes.mobile.platform.FakeSecretBox
import com.hermes.mobile.platform.MemoryLocalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RemoteRepositoryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val servers = mutableListOf<FakeHermesServer>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        servers.forEach { it.shutdown() }
    }

    private fun server(version: FakeHermesServer.Version) = FakeHermesServer(version).start().also { servers += it }

    private fun FakeHermesServer.profile(profileName: String? = null) = ConnectionProfile(
        id = "c1", name = "测试", type = ConnectionType.Direct,
        host = server.hostName, port = server.port, profile = profileName,
    )

    private fun repo(store: MemoryLocalStore = MemoryLocalStore()) = RemoteHermesRepository(scope, store, FakeSecretBox())

    private fun steps(flow: kotlinx.coroutines.flow.Flow<List<ConnectionTestStep>>) = runBlocking {
        withTimeout(10_000) { flow.toList().last() }
    }

    private fun RemoteHermesRepository.awaitConnected(): ConnectionState = runBlocking {
        withTimeout(10_000) { connectionState.first { it is ConnectionState.Connected || it is ConnectionState.Failed } }
    }

    // ---- 测试连接 ----

    @Test
    fun testConnectionPassesOnModernHermes() {
        val s = server(FakeHermesServer.Version.Modern)
        val result = steps(repo().testConnection(s.profile(), s.key))
        assertTrue(result.all { it.status == TestStepStatus.Passed }, result.toString())
        assertEquals("版本 0.22.2", result[1].detail)
        assertEquals("会话 ✓ · 聊天 ✓ · 审批 ✓", result[3].detail)
    }

    @Test
    fun wrongKeyFailsAtAuthStep() {
        val s = server(FakeHermesServer.Version.Modern)
        val result = steps(repo().testConnection(s.profile(), "wrong-key-0000000000"))
        assertEquals(TestStepStatus.Passed, result[1].status)
        assertEquals(TestStepStatus.Failed, result[2].status)
        assertTrue(result[2].detail!!.contains("API Key"), result[2].detail)
        assertEquals(TestStepStatus.Skipped, result[3].status)
    }

    @Test
    fun missingKeyIsExplained() {
        val s = server(FakeHermesServer.Version.Modern)
        val result = steps(repo().testConnection(s.profile(), null))
        assertEquals(TestStepStatus.Failed, result[2].status)
        assertTrue(result[2].detail!!.contains("还没填"), result[2].detail)
    }

    @Test
    fun oldHermesWithoutSessionsIsDetected() {
        val s = server(FakeHermesServer.Version.Old)
        val result = steps(repo().testConnection(s.profile(), s.key))
        assertEquals(TestStepStatus.Passed, result[2].status, "旧版本没有 /v1/capabilities，应该改用 /v1/models 验证 Key")
        assertEquals(TestStepStatus.Failed, result[3].status)
        assertTrue(result[3].detail!!.contains("太旧"), result[3].detail)
    }

    @Test
    fun nonHermesServerIsRecognised() {
        val s = server(FakeHermesServer.Version.NotHermes)
        val result = steps(repo().testConnection(s.profile(), s.key))
        assertEquals(TestStepStatus.Passed, result[0].status)
        assertEquals(TestStepStatus.Failed, result[1].status)
        assertTrue(result[1].detail!!.contains("不是 Hermes"), result[1].detail)
    }

    @Test
    fun hermesServeOnThePortIsExplained() {
        val s = server(FakeHermesServer.Version.HermesServe)
        val result = steps(repo().testConnection(s.profile(), s.key))
        assertEquals(TestStepStatus.Passed, result[0].status)
        assertEquals(TestStepStatus.Failed, result[1].status)
        assertTrue(result[1].detail!!.contains("hermes serve") && result[1].detail!!.contains("hermes gateway"), result[1].detail)
    }

    @Test
    fun unreachableHostFailsAtFirstStep() {
        val s = server(FakeHermesServer.Version.Modern)
        val port = s.server.port
        s.shutdown()
        val profile = ConnectionProfile("c1", "x", ConnectionType.Direct, "127.0.0.1", port)
        val result = steps(repo().testConnection(profile, "k"))
        assertEquals(TestStepStatus.Failed, result[0].status)
        assertTrue(result[0].detail!!.contains("连不上"), result[0].detail)
        assertTrue(result.drop(1).all { it.status == TestStepStatus.Skipped })
    }

    // ---- 连接与会话 ----

    @Test
    fun sessionsAreLoadedAndFiltered() {
        val s = server(FakeHermesServer.Version.Modern)
        val repo = repo()
        repo.saveConnection(s.profile(), s.key)
        val state = repo.awaitConnected()
        assertTrue(state is ConnectionState.Connected, state.toString())
        assertEquals("0.22.2", state.serverVersion)
        val sessions = runBlocking { withTimeout(5_000) { repo.sessions.first { it.isNotEmpty() } } }
        assertEquals(listOf("s1", "s2", "s6"), sessions.map { it.id }, "隐藏、归档、子代理会话不显示")
        val s1 = sessions[0]
        assertEquals("家庭网络", s1.title)
        assertFalse(s1.external)
        assertEquals(1760003600250L, s1.updatedAt)
        val s2 = sessions[1]
        assertTrue(s2.pinned)
        assertEquals("Telegram", s2.source.label)
        assertEquals("周五开会提醒", s2.title, "没有标题时用预览的第一行")
        assertEquals("kanban", sessions[2].source.label, "不认识的来源原样显示")
        assertTrue(AppFeature.ManageSessions in repo.features.value)
        assertTrue(AppFeature.SendMessages in repo.features.value, "有 /v1/runs 和事件流就能发消息")
    }

    @Test
    fun midVersionWithStringTimestampsWorks() {
        val s = server(FakeHermesServer.Version.Mid)
        val repo = repo()
        repo.saveConnection(s.profile(), s.key)
        assertTrue(repo.awaitConnected() is ConnectionState.Connected)
        val sessions = runBlocking { withTimeout(5_000) { repo.sessions.first { it.isNotEmpty() } } }
        assertEquals(1781942400500L, sessions.single().updatedAt, "数字字符串形式的时间也能解析")
        assertEquals("CLI", sessions.single().source.label)
        assertFalse(AppFeature.ManageSessions in repo.features.value, "这个版本没列出 session_update，不显示重命名/置顶")
    }

    @Test
    fun oldVersionConnectsWithEmptySessions() {
        val s = server(FakeHermesServer.Version.Old)
        val repo = repo()
        repo.saveConnection(s.profile(), s.key)
        assertTrue(repo.awaitConnected() is ConnectionState.Connected)
        Thread.sleep(300)
        assertTrue(repo.sessions.value.isEmpty())
        assertTrue(s.requests.none { it.path!!.startsWith("/api/sessions?limit=100") }, "已知没有会话接口就不再请求")
    }

    @Test
    fun historyShowsOnlyConversationNeverThinking() {
        val s = server(FakeHermesServer.Version.Modern)
        val repo = repo()
        repo.saveConnection(s.profile(), s.key)
        repo.awaitConnected()
        val items = runBlocking { withTimeout(5_000) { repo.chatItems("s1").first { it.isNotEmpty() } } }
            .filterIsInstance<ChatItem.Message>()
        assertEquals(
            listOf(Role.User to "网络老断", Role.Assistant to "DNS 改好了。", Role.User to "看这张图", Role.Assistant to "图里是路由器后台。"),
            items.map { it.role to it.text },
        )
        assertEquals(1, items[2].images.size, "分段内容里的图片要保留")
        assertTrue(items.none { "SECRET-THINKING" in it.text }, "思考内容绝不能出现在消息里")
        assertTrue(items.none { "ping output" in it.text }, "工具输出不显示")
        assertEquals(1760000200000L, items[1].time)
    }

    @Test
    fun profilePrefixIsUsed() {
        val s = server(FakeHermesServer.Version.Modern)
        val repo = repo()
        repo.saveConnection(s.profile(profileName = "work"), s.key)
        assertTrue(repo.awaitConnected() is ConnectionState.Connected)
        Thread.sleep(300)
        assertTrue(s.requests.any { it.path!!.startsWith("/p/work/api/sessions") }, s.requests.map { it.path }.toString())
        assertTrue(s.requests.any { it.path == "/health" }, "/health 不加 Profile 前缀")
    }

    @Test
    fun unknownProfileSaysSo() {
        val s = server(FakeHermesServer.Version.Modern)
        val repo = repo()
        repo.saveConnection(s.profile(profileName = "nope"), s.key)
        val state = repo.awaitConnected()
        assertTrue(state is ConnectionState.Failed && "nope" in state.reason && "Profile" in state.reason, state.toString())
    }

    @Test
    fun renameAndPinSendPatchAndRollBackOnFailure() {
        val s = server(FakeHermesServer.Version.Modern)
        val repo = repo()
        repo.saveConnection(s.profile(), s.key)
        repo.awaitConnected()
        runBlocking { withTimeout(5_000) { repo.sessions.first { it.isNotEmpty() } } }

        repo.renameSession("s1", "新标题")
        assertEquals("新标题", repo.sessions.value.first { it.id == "s1" }.title, "界面立刻变")
        Thread.sleep(500)
        val patch = s.requests.last { it.method == "PATCH" }
        assertEquals("""{"title":"新标题"}""", patch.body.readUtf8())

        s.failPatch = true
        val errors = mutableListOf<String>()
        val job = scope.launch { repo.errors.collect { errors += it } }
        Thread.sleep(100)
        repo.togglePin("s1")
        assertTrue(repo.sessions.value.first { it.id == "s1" }.pinned)
        Thread.sleep(800)
        assertFalse(repo.sessions.value.first { it.id == "s1" }.pinned, "服务器失败后改回去")
        assertTrue(errors.any { "database is locked" in it }, errors.toString())
        job.cancel()
    }

    @Test
    fun archiveHidesTheSessionKeepsItFindableAndCanBeUndone() {
        val s = server(FakeHermesServer.Version.Modern)
        val repo = repo()
        repo.saveConnection(s.profile(), s.key)
        repo.awaitConnected()
        runBlocking { withTimeout(5_000) { repo.sessions.first { it.isNotEmpty() } } }

        repo.setArchived("s1", true)
        assertTrue(repo.sessions.value.none { it.id == "s1" }, "界面上立刻从列表里拿掉")
        val archived = runBlocking { withTimeout(5_000) { repo.archivedSessions.first { list -> list.any { it.id == "s1" } } } }
        assertEquals(listOf("s1"), archived.map { it.id }, "归档列表里能找到")
        Thread.sleep(500)
        assertEquals("""{"archived":true}""", s.requests.last { it.method == "PATCH" }.body.readUtf8())

        runBlocking { repo.refreshSessions() }
        assertTrue(repo.sessions.value.none { it.id == "s1" }, "刷新后也不回来")
        assertEquals(listOf("s1"), repo.archivedSessions.value.map { it.id })

        repo.setArchived("s1", false)
        runBlocking { withTimeout(5_000) { repo.archivedSessions.first { it.isEmpty() } } }
        runBlocking { withTimeout(5_000) { repo.sessions.first { list -> list.any { it.id == "s1" } } } }
        assertEquals("""{"archived":false}""", s.requests.last { it.method == "PATCH" }.body.readUtf8())

        // 服务器失败：退回列表，并提示
        s.failPatch = true
        val errors = mutableListOf<String>()
        val job = scope.launch { repo.errors.collect { errors += it } }
        Thread.sleep(100)
        repo.setArchived("s2", true)
        Thread.sleep(800)
        assertTrue(repo.sessions.value.any { it.id == "s2" }, "失败后回到列表")
        assertTrue(repo.archivedSessions.value.none { it.id == "s2" })
        assertTrue(errors.any { "归档失败" in it }, errors.toString())
        job.cancel()
    }

    @Test
    fun deleteCallsServer() {
        val s = server(FakeHermesServer.Version.Modern)
        val repo = repo()
        repo.saveConnection(s.profile(), s.key)
        repo.awaitConnected()
        runBlocking { withTimeout(5_000) { repo.sessions.first { it.isNotEmpty() } } }
        repo.deleteSession("s1")
        Thread.sleep(500)
        assertTrue(s.requests.any { it.method == "DELETE" && it.path == "/api/sessions/s1" })
        assertTrue(repo.sessions.value.none { it.id == "s1" })
    }

    // ---- 保存 ----

    @Test
    fun keyIsStoredEncryptedAndSurvivesRestart() {
        val s = server(FakeHermesServer.Version.Modern)
        val store = MemoryLocalStore()
        repo(store).saveConnection(s.profile(), s.key)
        val saved = assertNotNull(store.state)
        assertTrue(saved.keys.values.none { it.contains(s.key) }, "存的必须是密文")
        assertEquals("c1", saved.activeConnectionId)

        // 模拟重启：新的仓库从存储里读出连接和 Key，自动连上
        val restarted = repo(store)
        assertTrue(restarted.awaitConnected() is ConnectionState.Connected)
        assertEquals(listOf("c1"), restarted.connections.value.map { it.id })
    }
}

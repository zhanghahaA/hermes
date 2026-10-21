package com.hermes.mobile.data.remote

import com.hermes.mobile.data.model.Attachment
import com.hermes.mobile.data.model.ChatItem
import com.hermes.mobile.data.model.ConnectionProfile
import com.hermes.mobile.data.model.ConnectionState
import com.hermes.mobile.data.model.ConnectionType
import com.hermes.mobile.data.model.DeliveryStatus
import com.hermes.mobile.data.model.ProjectInfo
import com.hermes.mobile.data.model.Role
import com.hermes.mobile.data.model.RunPhase
import com.hermes.mobile.data.model.SessionGrouping
import com.hermes.mobile.data.store.ChatCache
import com.hermes.mobile.platform.FakeSecretBox
import com.hermes.mobile.platform.MemoryLocalStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 假 API + 真实 OkHttp：覆盖电脑项目的中文目录、草稿转正式会话和发送失败恢复。 */
class WorkspaceChatTest {
    private val unexpected = CopyOnWriteArrayList<Throwable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> unexpected += error })
    private val server = FakeHermesServer(FakeHermesServer.Version.Modern).start()
    private val cacheDir = Files.createTempDirectory("workspace-chat").toFile()

    @AfterTest fun close() {
        scope.cancel()
        server.shutdown()
        cacheDir.deleteRecursively()
    }

    private fun connect(folder: String? = null, exporter: ((String, String, String) -> String?)? = null): RemoteHermesRepository = runBlocking {
        if (folder != null) ChatCache(cacheDir).saveGroups("workspace-test", SessionGrouping(
            groups = listOf("示例项目"), of = mapOf("s1" to "示例项目"), folders = mapOf("s1" to folder),
        ))
        val repo = RemoteHermesRepository(scope, MemoryLocalStore(), FakeSecretBox(), cacheDir = cacheDir, localFileExporter = exporter)
        repo.saveConnection(ConnectionProfile("workspace-test", "本机测试", ConnectionType.Local, "127.0.0.1", server.server.port), server.key)
        withTimeout(5000) {
            repo.connectionState.first { it is ConnectionState.Connected }
            repo.sessions.first { it.isNotEmpty() }
        }
        repo
    }

    private suspend fun awaitReply(repo: RemoteHermesRepository, id: String, text: String) = withTimeout(4000) {
        repo.chatItems(id).first { items -> items.any { it is ChatItem.Message && it.role == Role.Assistant && it.text == "最终回复：$text" } }
        repo.runPhase(id).first { it == RunPhase.Idle }
        assertTrue(unexpected.isEmpty(), "发送不能逃逸到未处理异常：${unexpected.map { it.javaClass.simpleName }}")
    }

    @Test fun unicodeWorkspaceIsPreservedInJsonWithoutAnInvalidHeader() = runBlocking {
        val client = HermesClient(server.baseUrl, server.key)
        for ((index, folder) in listOf("/srv/示例项目", "/srv/café", "/srv/项目 🚀", "/srv/a\nb").withIndex()) {
            val instructions = "Work in $folder"
            assertNotNull(client.startRun("s1", JsonPrimitive("hello"), "workspace-$index", instructions = instructions, workspace = folder).run_id)
            val request = server.requests.last { it.path == "/v1/runs" }
            assertNull(request.getHeader("X-Hermes-Workspace"))
            assertEquals(instructions, HermesJson.parseToJsonElement(request.body.clone().readUtf8()).jsonObject["instructions"]!!.jsonPrimitive.content)
        }
    }

    @Test fun asciiWorkspaceHeaderIsUnchanged() = runBlocking {
        val path = "/srv/example project/a%20b"
        HermesClient(server.baseUrl, server.key).startRun("s1", JsonPrimitive("hello"), "workspace-ascii", workspace = path)
        assertEquals(path, server.requests.last { it.path == "/v1/runs" }.getHeader("X-Hermes-Workspace"))
    }

    @Test fun existingDesktopSessionInChineseProjectCanContinue() = runBlocking {
        val folder = "/srv/示例项目"
        val repo = connect(folder)
        repo.sendMessage("s1", "继续对话", emptyList())
        awaitReply(repo, "s1", "继续对话")
        val run = server.requests.last { it.path == "/v1/runs" }
        assertTrue(HermesJson.parseToJsonElement(run.body.clone().readUtf8()).jsonObject["instructions"]!!.jsonPrimitive.content.contains(folder))
        assertEquals(folder, repo.sessionGroups.value.folders["s1"])
    }

    @Test fun newSessionInChineseProjectCanSendItsFirstMessage() = runBlocking {
        val repo = connect()
        val draft = assertNotNull(repo.createSession())
        val folder = "/srv/另一个项目"
        repo.setDraftProject(draft, ProjectInfo("fixture-project", "另一个项目", folder))
        repo.sendMessage(draft, "第一条消息", emptyList())
        awaitReply(repo, draft, "第一条消息")
        val run = server.requests.last { it.path == "/v1/runs" }
        val body = HermesJson.parseToJsonElement(run.body.clone().readUtf8()).jsonObject
        assertEquals("new1", body["session_id"]!!.jsonPrimitive.content)
        assertTrue(body["instructions"]!!.jsonPrimitive.content.contains(folder))
        assertNull(run.getHeader("X-Hermes-Workspace"))
    }

    @Test fun preparationArgumentFailureStopsTypingAndDoesNotBlockTheNextMessage() = runBlocking {
        // 用现有本机文件导出入口模拟请求准备阶段同类异常，不访问真实文件或服务。
        val repo = connect(exporter = { _, _, _ -> throw IllegalArgumentException("fixture-private-folder") })
        val errors = CopyOnWriteArrayList<String>()
        val reported = CompletableDeferred<String>()
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            repo.errors.collect { errors += it; if (it.startsWith("发送失败")) reported.complete(it) }
        }
        repo.sendMessage("s1", "带附件", listOf(Attachment.BinaryFile("fixture", "fixture.zip", "application/zip", "/fixture/file", 1)))
        withTimeout(4000) {
            repo.chatItems("s1").first { list -> list.any { it is ChatItem.Message && it.text == "带附件" && it.status == DeliveryStatus.Failed } }
            repo.runPhase("s1").first { it == RunPhase.Idle }
        }
        assertTrue(withTimeout(4000) { reported.await() }.contains("IllegalArgumentException"))
        assertFalse(errors.any { "fixture-private-folder" in it })
        repo.sendMessage("s1", "随后再发", emptyList())
        awaitReply(repo, "s1", "随后再发")
        job.cancel()
    }
}

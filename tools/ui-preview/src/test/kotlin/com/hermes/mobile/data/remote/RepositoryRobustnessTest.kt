package com.hermes.mobile.data.remote

import com.hermes.mobile.data.model.ConnectionProfile
import com.hermes.mobile.data.model.ConnectionState
import com.hermes.mobile.data.model.ConnectionType
import com.hermes.mobile.platform.FakeSecretBox
import com.hermes.mobile.platform.MemoryLocalStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** 连接隔离、任务响应顺序和诊断隐私的回归测试，只使用本机假服务器。 */
class RepositoryRobustnessTest {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val servers = mutableListOf<FakeHermesServer>()

    @AfterTest fun cleanup() {
        scope.cancel()
        servers.forEach { it.shutdown() }
        dispatcher.close()
    }

    private fun server(name: String) = FakeHermesServer(FakeHermesServer.Version.Modern).start().also {
        servers += it
        it.jobs["shared-job"] = mutableMapOf(
            "id" to "shared-job", "name" to name, "prompt" to "review fixture",
            "schedule" to "0 9 * * *", "deliver" to "local", "enabled" to true,
            "state" to "scheduled", "last_run_at" to null, "last_status" to null,
        )
    }

    private fun profile(id: String, s: FakeHermesServer) = ConnectionProfile(
        id = id, name = id, type = ConnectionType.Direct,
        host = s.server.hostName, port = s.server.port,
    )

    private suspend fun connected(repo: RemoteHermesRepository, s: FakeHermesServer) {
        repo.saveConnection(profile("a", s), s.key)
        withTimeout(5000) { repo.connectionState.first { it is ConnectionState.Connected } }
        withTimeout(5000) { repo.jobs.first { it.singleOrNull()?.name == "server-a" } }
    }

    @Test fun switchingConnectionsMustNotModifyThePreviousServer() = runBlocking(dispatcher) {
        val a = server("server-a")
        val b = server("server-b")
        val original = b.server.dispatcher
        b.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = original.dispatch(request).also {
                if (request.path == "/health") it.setBodyDelay(1500, TimeUnit.MILLISECONDS)
            }
        }
        val repo = RemoteHermesRepository(scope, MemoryLocalStore(), FakeSecretBox())
        connected(repo, a)
        repo.saveConnection(profile("b", b), b.key)
        repo.switchConnection("b")
        assertEquals(ConnectionState.Connecting, repo.connectionState.value)
        repo.setJobEnabled("shared-job", false)
        delay(500)
        assertFalse(a.requests.any { it.path == "/api/jobs/shared-job/pause" },
            "切换到 B 的连接过程中，暂停操作仍被发送到了 A")
    }

    @Test fun oldConnectionResponseMustNotReplaceTheNewConnectionJobs() = runBlocking(dispatcher) {
        val a = server("server-a")
        val b = server("server-b")
        val repo = RemoteHermesRepository(scope, MemoryLocalStore(), FakeSecretBox())
        connected(repo, a)
        val delayNext = AtomicBoolean(true)
        val arrived = AtomicBoolean(false)
        val original = a.server.dispatcher
        a.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = original.dispatch(request).also {
                if (request.path?.startsWith("/api/jobs?") == true && delayNext.compareAndSet(true, false)) {
                    arrived.set(true)
                    it.setBodyDelay(1200, TimeUnit.MILLISECONDS)
                }
            }
        }
        val refresh = scope.launch { repo.refreshJobs() }
        withTimeout(5000) { while (!arrived.get()) delay(10) }
        repo.saveConnection(profile("b", b), b.key)
        repo.switchConnection("b")
        withTimeout(5000) { repo.jobs.first { it.singleOrNull()?.name == "server-b" } }
        refresh.join()
        assertEquals("server-b", repo.jobs.value.single().name,
            "A 的延迟响应不应覆盖已经显示的 B 的任务")
    }

    @Test fun earlierPauseResponseMustNotUndoLaterRename() = runBlocking(dispatcher) {
        val a = server("server-a")
        val repo = RemoteHermesRepository(scope, MemoryLocalStore(), FakeSecretBox())
        connected(repo, a)
        val arrived = AtomicBoolean(false)
        val original = a.server.dispatcher
        a.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = original.dispatch(request).also {
                if (request.path == "/api/jobs/shared-job/pause") {
                    arrived.set(true)
                    it.setBodyDelay(1000, TimeUnit.MILLISECONDS)
                }
            }
        }
        repo.setJobEnabled("shared-job", false)
        withTimeout(5000) { while (!arrived.get()) delay(10) }
        repo.saveJob(repo.jobs.value.single().copy(name = "new-name"))
        withTimeout(5000) { repo.jobs.first { it.singleOrNull()?.name == "new-name" } }
        delay(1300)
        assertEquals("new-name", repo.jobs.value.single().name,
            "较早暂停请求的旧任务名覆盖了后续已保存的名字")
    }

    @Test fun exportedDiagnosticsMustNotContainPrivateHostnames() = runBlocking(dispatcher) {
        val repo = RemoteHermesRepository(scope, MemoryLocalStore(), FakeSecretBox())
        val fictionalHost = "private-demo.example.invalid"
        repo.reportUnexpected(IllegalStateException("Unable to connect to $fictionalHost"))
        assertFalse(repo.diagnosticsReport().contains(fictionalHost),
            "诊断导出包含异常原文中的主机名")
    }

    @Test fun staleListResponseMustNotUndoAnEdit() = runBlocking(dispatcher) {
        val a = server("server-a")
        val repo = RemoteHermesRepository(scope, MemoryLocalStore(), FakeSecretBox())
        connected(repo, a)
        val arrived = AtomicBoolean(false)
        val original = a.server.dispatcher
        a.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = original.dispatch(request).also {
                if (request.path?.startsWith("/api/jobs?") == true) {
                    arrived.set(true)
                    it.setBodyDelay(1000, TimeUnit.MILLISECONDS)
                }
            }
        }
        val refresh = scope.launch { repo.refreshJobs() }
        withTimeout(5000) { while (!arrived.get()) delay(10) }
        repo.saveJob(repo.jobs.value.single().copy(name = "new-name"))
        withTimeout(5000) { repo.jobs.first { it.singleOrNull()?.name == "new-name" } }
        refresh.join()
        assertEquals("new-name", repo.jobs.value.single().name)
    }

    @Test fun failedToggleMustNotRollBackAnotherJobsEdit() = runBlocking(dispatcher) {
        val a = server("server-a")
        val repo = RemoteHermesRepository(scope, MemoryLocalStore(), FakeSecretBox())
        connected(repo, a)
        a.jobs["second-job"] = a.jobs.getValue("shared-job").toMutableMap().apply {
            put("id", "second-job")
            put("name", "second-original")
        }
        repo.refreshJobs()
        val arrived = AtomicBoolean(false)
        val original = a.server.dispatcher
        a.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/jobs/shared-job/pause") {
                    arrived.set(true)
                    return MockResponse().setResponseCode(500)
                        .setBody("""{"error":"fixture failure"}""")
                        .setBodyDelay(1000, TimeUnit.MILLISECONDS)
                }
                return original.dispatch(request)
            }
        }
        repo.setJobEnabled("shared-job", false)
        withTimeout(5000) { while (!arrived.get()) delay(10) }
        repo.saveJob(repo.jobs.value.first { it.id == "second-job" }.copy(name = "second-updated"))
        withTimeout(5000) { repo.jobs.first { list -> list.any { it.name == "second-updated" } } }
        withTimeout(5000) { repo.jobs.first { list -> list.first { it.id == "shared-job" }.enabled } }
        assertEquals("second-updated", repo.jobs.value.first { it.id == "second-job" }.name)
    }

    @Test fun refreshingDuringASaveMustNotRestoreThePreviousName() = runBlocking(dispatcher) {
        val a = server("server-a")
        val repo = RemoteHermesRepository(scope, MemoryLocalStore(), FakeSecretBox())
        connected(repo, a)
        val arrived = AtomicBoolean(false)
        val release = java.util.concurrent.CountDownLatch(1)
        val original = a.server.dispatcher
        a.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "PATCH" && request.path == "/api/jobs/shared-job") {
                    arrived.set(true)
                    release.await(5, TimeUnit.SECONDS)
                }
                return original.dispatch(request).also {
                    if (request.path?.startsWith("/api/jobs?") == true) it.setBodyDelay(1000, TimeUnit.MILLISECONDS)
                }
            }
        }
        try {
            repo.saveJob(repo.jobs.value.single().copy(name = "new-name"))
            withTimeout(5000) { while (!arrived.get()) delay(10) }
            val refresh = scope.launch { repo.refreshJobs() }
            delay(100)
            release.countDown()
            withTimeout(5000) { repo.jobs.first { it.singleOrNull()?.name == "new-name" } }
            refresh.join()
            assertEquals("new-name", repo.jobs.value.single().name)
        } finally { release.countDown() }
    }
}

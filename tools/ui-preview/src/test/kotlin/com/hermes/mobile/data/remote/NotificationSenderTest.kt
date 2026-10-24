package com.hermes.mobile.data.remote

import com.hermes.mobile.data.model.ConnectionProfile
import com.hermes.mobile.data.model.ConnectionType
import com.hermes.mobile.data.model.SshAuthMethod
import com.hermes.mobile.data.ssh.SshAuth
import com.hermes.mobile.data.ssh.SshTunnel
import com.hermes.mobile.data.ssh.TestSshServer
import com.hermes.mobile.data.notifications.*
import com.hermes.mobile.data.store.StoredState
import com.hermes.mobile.platform.FakeSecretBox
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class NotificationSenderTest {
    @Test fun scheduledSummaryUsesIndependentPinnedSshTunnel() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"run_id":"ssh-run","status":"started"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"completed","output":"摘要已生成"}"""))
        server.start()
        val ssh = TestSshServer().start()
        try {
            var pin: String? = null
            val auth = SshAuth.Password(ssh.password)
            SshTunnel("127.0.0.1", ssh.port, ssh.user, auth, server.port, null, { pin = it }).use { it.connect() }
            val secrets = FakeSecretBox()
            val state = StoredState(
                connections = listOf(ConnectionProfile("ssh-bound", "测试", ConnectionType.Ssh, "127.0.0.1", server.port,
                    sshUser = ssh.user, sshPort = ssh.port, sshAuth = SshAuthMethod.Password, sshHostKey = assertNotNull(pin))),
                keys = mapOf("ssh:ssh-bound" to secrets.encrypt(ssh.password), "ssh-bound" to secrets.encrypt("test-api-key")),
            )
            val sender = NotificationSender({ state }, secrets, pollMillis = 1)
            val batch = NotificationBatch("ssh-batch", NotificationTarget("ssh-bound", "default", "fixed", "测试"), emptyList())
            withTimeout(20_000) { assertEquals("摘要已生成", sender.send(batch) {}) }
            assertEquals("/v1/runs", server.takeRequest().path)
            assertEquals("/v1/runs/ssh-run", server.takeRequest().path)
            assertEquals(2, ssh.logins.get())
        } finally { ssh.stop(); server.shutdown() }
    }

    @Test fun sendsToBoundBotWithItsOwnKeyAndResumesRunWithoutResubmitting() = runBlocking {
        val server = MockWebServer()
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val body = when {
                    request.path == "/p/helper/api/sessions" -> """{"session":{"id":"fixed"}}"""
                    request.path == "/p/helper/v1/runs" -> """{"run_id":"r-one","status":"started"}"""
                    request.path == "/p/helper/v1/runs/r-one" -> """{"status":"completed","output":"需要处理：取件"}"""
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body).setHeader("Content-Type", "application/json")
            }
        }
        server.start()
        try {
            val secrets = FakeSecretBox()
            var state = StoredState(
                connections = listOf(ConnectionProfile("bound", "测试", ConnectionType.Local, "127.0.0.1", server.port)),
                activeConnectionId = "other", activeBots = mapOf("bound" to "another"),
                keys = mapOf("bound" to secrets.encrypt("base-key"), "bot:bound:helper" to secrets.encrypt("helper-key")),
            )
            val sender = NotificationSender({ state }, secrets, pollMillis = 1)
            val target = NotificationTarget("bound", "helper", "fixed", "测试")
            assertEquals("fixed", sender.createSession(target))
            val batch = NotificationBatch("batch-one", target, listOf(NotificationRecord("one", "hashed", "example.delivery", "快递", "包裹", "已到达", 1)))
            var run: String? = null
            assertEquals("需要处理：取件", sender.send(batch) { run = it })
            assertEquals("r-one", run)
            state = state.copy(activeBots = mapOf("bound" to "different"))
            sender.send(batch.copy(runId = run)) { fail("恢复时不应重新提交") }
            assertEquals(1, requests.count { it.method == "POST" && it.path?.endsWith("/v1/runs") == true })
            assertTrue(requests.all { it.getHeader("Authorization") == "Bearer helper-key" })
            val submitted = requests.first { it.path == "/p/helper/v1/runs" }
            assertEquals("notifications-batch-one", submitted.getHeader("Idempotency-Key"))
            val body = submitted.body.readUtf8()
            assertTrue(body.contains("fixed"))
            assertTrue(body.contains("禁止调用工具"))
        } finally { server.shutdown() }
    }

    @Test fun missingBotKeyFailsBeforeNetworkAccess() = runBlocking {
        val state = StoredState(connections = listOf(ConnectionProfile("bound", "测试", ConnectionType.Local, "127.0.0.1")))
        val sender = NotificationSender({ state }, FakeSecretBox())
        assertFailsWith<IllegalArgumentException> {
            sender.createSession(NotificationTarget("bound", "unconfigured", "", "测试"))
        }
        Unit
    }
}

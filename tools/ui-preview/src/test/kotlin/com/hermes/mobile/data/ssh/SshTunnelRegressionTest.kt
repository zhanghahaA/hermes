package com.hermes.mobile.data.ssh

import com.hermes.mobile.data.model.ConnectionProfile
import com.hermes.mobile.data.model.ConnectionType
import com.hermes.mobile.data.model.SshAuthMethod
import com.hermes.mobile.data.model.TestStepStatus
import com.hermes.mobile.data.ConnectionDiagnostics
import com.hermes.mobile.data.remote.FakeHermesServer
import com.hermes.mobile.data.remote.HermesClient
import com.hermes.mobile.data.remote.RemoteHermesRepository
import com.hermes.mobile.platform.FakeSecretBox
import com.hermes.mobile.platform.MemoryLocalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.SocketPolicy
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.forward.TcpForwardingFilter
import org.apache.sshd.server.forward.DirectTcpipFactory
import org.apache.sshd.server.forward.TcpipServerChannel
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.net.Inet4Address
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 真 TCP、真 SSH 通道；服务器、账号和模型全部是本机测试数据。 */
class SshTunnelRegressionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cleanup = mutableListOf<() -> Unit>()

    @AfterTest fun close() {
        scope.cancel()
        cleanup.asReversed().forEach { runCatching(it) }
    }

    private fun ssh(server: TestSshServer = TestSshServer()) = server.start().also { cleanup += { it.stop() } }

    private fun tunnel(ssh: TestSshServer, target: Int) = SshTunnel(
        "127.0.0.1", ssh.port, ssh.user, SshAuth.Password(ssh.password), target, null,
    ).also { cleanup += { it.close() }; it.connect() }

    private fun profile(ssh: TestSshServer, target: Int) = ConnectionProfile(
        id = "ssh-regression", name = "SSH test", type = ConnectionType.Ssh,
        host = "127.0.0.1", port = target, sshUser = ssh.user, sshPort = ssh.port,
        sshAuth = SshAuthMethod.Password,
    )

    @Test fun refusingOneChannelDoesNotResetAnAuthenticatedSession() {
        val reject = AtomicBoolean(false)
        val ssh = ssh(TestSshServer().apply {
            sshd.forwardingFilter = object : AcceptAllForwardingFilter() {
                override fun canConnect(type: TcpForwardingFilter.Type, address: SshdSocketAddress, session: Session): Boolean = !reject.get()
            }
        })
        val tunnel = tunnel(ssh, echoPeer().localPort)
        Socket("127.0.0.1", tunnel.localPort).use { first ->
            first.soTimeout = 4000
            val input = DataInputStream(first.getInputStream())
            val output = DataOutputStream(first.getOutputStream())
            output.writeInt(21); output.flush()
            assertEquals(42, input.readInt())
            reject.set(true)
            Socket("127.0.0.1", tunnel.localPort).use { refused ->
                refused.soTimeout = 4000
                assertEquals(-1, refused.getInputStream().read())
            }
            assertEquals(1, ssh.logins.get(), "一个被拒绝的通道不能导致整条 SSH 重连")
            reject.set(false)
            output.writeInt(22); output.flush()
            assertEquals(44, input.readInt(), "原有长连接应继续可用")
        }
    }

    @Test fun closingTunnelUnblocksAlreadyAcceptedIdleConnections() {
        val tunnel = tunnel(ssh(), echoPeer().localPort)
        Socket("127.0.0.1", tunnel.localPort).use { socket ->
            socket.soTimeout = 700
            DataOutputStream(socket.getOutputStream()).apply { writeInt(1); flush() }
            assertEquals(2, DataInputStream(socket.getInputStream()).readInt())
            tunnel.close()
            assertEquals(-1, socket.getInputStream().read(), "关闭隧道应立即关闭本地连接，不能再等固定两秒")
        }
    }

    @Test fun ipv4ProbeAndHealthHaveSeparateByteLevelEvidence() = runBlocking {
        val hermes = FakeHermesServer(FakeHermesServer.Version.Modern).start().also { cleanup += { it.shutdown() } }
        val ssh = ssh()
        val trace = ConnectionDiagnostics().begin(ConnectionDiagnostics.Use.Test)
        val tunnel = SshTunnel("127.0.0.1", ssh.port, ssh.user, SshAuth.Password(ssh.password), hermes.server.port, null, diagnostics = trace)
            .also { cleanup += { it.close() }; it.connect() }
        assertTrue(tunnel.localBindAddress is Inet4Address)
        assertEquals("127.0.0.1", tunnel.localBindAddress.hostAddress)
        assertEquals(null, tunnel.probeForward())
        assertEquals(0, hermes.requests.size, "TCP 探测不应伪装成 HTTP 健康检查")
        val client = HermesClient(tunnel.localBaseUrl, hermes.key).throughSshTunnel(trace)
        assertEquals("hermes-agent", client.health().platform)
        assertTrue(hermes.requests.any { it.path == "/health" })
        tunnel.close()
        val report = trace.report()
        for (stage in listOf("ListenerBound", "IPv4", "TCP-only", "LocalAccepted", "UpFirstBytes", "DownFirstBytes", "HTTP=200", "HealthParsed", "proxy=DIRECT")) {
            assertTrue(report.contains(stage), "$stage 缺失：$report")
        }
        assertTrue(!report.contains(hermes.key) && !report.contains(ssh.password))
    }

    @Test fun remoteHalfCloseDoesNotCutOffAnUploadAfterTwoSeconds() {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        cleanup += { server.close() }
        val received = CompletableFuture<ByteArray>()
        val ready = CompletableFuture<Unit>()
        val payload = ByteArray(300_000) { (it % 239).toByte() }
        thread(isDaemon = true, name = "ssh-test-half-close") {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 7000
                    assertEquals(1, socket.getInputStream().read())
                    ready.complete(Unit)
                    received.complete(socket.getInputStream().readNBytes(payload.size))
                }
            } catch (e: Exception) { received.completeExceptionally(e) }
        }
        // MINA 的 TCP 转发器把 TCP EOF 当整条关闭；直接发送真实 SSH EOF，
        // 保持反向通道可写，用来验证手机转发器的半关闭语义。
        class HalfCloseChannel : TcpipServerChannel(TcpForwardingFilter.Type.Direct, null) {
            fun finishSending() { sendEof().verify(5000) }
        }
        val channel = CompletableFuture<HalfCloseChannel>()
        val ssh = ssh(TestSshServer().apply {
            sshd.channelFactories = sshd.channelFactories.filter { it.name != "direct-tcpip" } + object : DirectTcpipFactory() {
                override fun createChannel(session: Session) = HalfCloseChannel().also { channel.complete(it) }
            }
        })
        val tunnel = tunnel(ssh, server.localPort)
        Socket("127.0.0.1", tunnel.localPort).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write(1)
            ready.get(5, TimeUnit.SECONDS)
            channel.get(5, TimeUnit.SECONDS).finishSending()
            assertEquals(-1, socket.getInputStream().read())
            Thread.sleep(2300) // 超过原有固定 join(2000)，对端仍可接收上传。
            socket.getOutputStream().write(payload)
            socket.shutdownOutput()
            val actual = received.get(5, TimeUnit.SECONDS)
            assertTrue(actual.contentEquals(payload), "上传不完整：${actual.size}/${payload.size}")
        }
    }

    @Test fun sseLastsOverAMinuteAlongsideConcurrentHealthAndLargePost() = runBlocking {
        val requests = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
        val frame = "id: 1\ndata: {\"event\":\"message.delta\",\"delta\":\"x\"}\n\n"
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return when {
                        request.path.orEmpty().endsWith("/events") -> MockResponse().setHeader("Content-Type", "text/event-stream")
                            .setBody(frame.repeat(90)).throttleBody(frame.length.toLong(), 1, TimeUnit.SECONDS)
                        request.path == "/v1/runs" -> MockResponse().setBody("{\"run_id\":\"test-run\",\"status\":\"running\"}")
                        else -> MockResponse().setBody("{\"status\":\"ok\",\"platform\":\"hermes-agent\",\"version\":\"test\"}")
                    }
                }
            }
            start()
        }.also { cleanup += { it.shutdown() } }
        val ssh = ssh()
        val tunnel = tunnel(ssh, server.port)
        val trace = ConnectionDiagnostics().begin(ConnectionDiagnostics.Use.Test)
        val http = OkHttpClient.Builder().build()
        val client = HermesClient(tunnel.localBaseUrl, "test-only-key", http = http).throughSshTunnel(trace)
        val first = CompletableDeferred<Unit>()
        val frames = AtomicInteger()
        val stream = launch(Dispatchers.IO) {
            client.runEvents("test-run", -1).collect { frames.incrementAndGet(); first.complete(Unit) }
        }
        try {
            withTimeout(5000) { first.await() }
            val started = System.nanoTime()
            withTimeout(15_000) {
                (1..3).map { async(Dispatchers.IO) { repeat(4) { assertEquals("hermes-agent", client.health().platform) } } }.awaitAll()
                val accepted = client.startRun("test-session", kotlinx.serialization.json.JsonPrimitive("x".repeat(2 * 1024 * 1024)), "test-idempotency")
                assertEquals("test-run", accepted.run_id)
            }
            withTimeout(70_000) {
                while ((System.nanoTime() - started) / 1_000_000 < 61_000) delay(250)
            }
            assertTrue(frames.get() >= 40, "一分钟内应持续接收事件，而非只打开 TCP 通道")
            assertTrue(requests.any { it.path == "/v1/runs" && it.bodySize > 2_000_000 })
            assertEquals(1, ssh.logins.get(), "并发 HTTP 和 SSE 共用会话，不应重置")
            withTimeout(5000) { stream.cancelAndJoin() }
            http.connectionPool.evictAll()
            withTimeout(5000) { while (tunnel.activeConnectionCount > 0) delay(20) }
        } finally {
            stream.cancelAndJoin()
            tunnel.close()
        }
    }

    private fun echoPeer(): ServerSocket {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val sockets = ConcurrentHashMap.newKeySet<Socket>()
        cleanup += { server.close(); sockets.forEach { runCatching { it.close() } } }
        thread(isDaemon = true, name = "ssh-test-echo") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                sockets += socket
                thread(isDaemon = true, name = "ssh-test-echo-peer") {
                    socket.use {
                        try {
                            val input = DataInputStream(it.getInputStream())
                            val output = DataOutputStream(it.getOutputStream())
                            while (true) { output.writeInt(input.readInt() * 2); output.flush() }
                        } catch (_: IOException) { /* 测试结束或对端关闭。 */ }
                    }
                    sockets -= socket
                }
            }
        }
        return server
    }
}

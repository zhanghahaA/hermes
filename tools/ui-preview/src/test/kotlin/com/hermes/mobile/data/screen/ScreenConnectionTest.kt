package com.hermes.mobile.data.screen

import com.hermes.mobile.data.ssh.RemoteStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScreenConnectionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    /** 假的 SSH 通道：[server] 是“Hermes 那头”往 App 写的，[written] 是 App 写给 Hermes 的。 */
    private class FakeStream(var error: String = "", var code: Int? = null) : RemoteStream {
        val server = PipedOutputStream()
        override val output = PipedInputStream(server, 1 shl 20)
        val written = ByteArrayOutputStream()
        override val input = written
        override fun errorText() = error
        override val exitCode get() = code
        var closed = false
        override fun close() {
            closed = true
            runCatching { server.close() }
        }
    }

    private fun host(calls: MutableList<String>) = ScreenPageHost().apply { evaluate = { calls += it } }

    private fun waitFor(what: String, check: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!check()) kotlinx.coroutines.delay(10) }
    }.also { assertTrue(check(), what) }

    @Test
    fun bytesGoBothWaysAsBase64() {
        val calls = CopyOnWriteArrayList<String>()
        val stream = FakeStream()
        val host = host(calls)
        val c = ScreenConnection(stream, host, scope)
        c.start(fit = true, viewOnly = true)
        assertEquals("hm.start({fit:true,viewOnly:true})", calls.first())

        // Hermes → 页面
        stream.server.write("RFB 003.008\n".toByteArray())
        stream.server.flush()
        waitFor("页面收到握手") { calls.any { it.startsWith("hm.recv(") } }
        val b64 = calls.first { it.startsWith("hm.recv(") }.removePrefix("hm.recv('").removeSuffix("')")
        assertEquals("RFB 003.008\n", String(Base64.getDecoder().decode(b64)))

        // 页面 → Hermes
        host.onSend(Base64.getEncoder().encodeToString("RFB 003.008\n".toByteArray()))
        assertEquals("RFB 003.008\n", stream.written.toString())

        // 页面的事件改变状态
        host.onEvent("connect", "")
        assertEquals(ScreenConnection.State.Live, c.state.value)

        c.close()
        assertTrue(stream.closed)
        assertTrue("hm.stop()" in calls)
        assertTrue(c.state.value is ScreenConnection.State.Closed)
    }

    @Test
    fun screenDownIsExplained() {
        val calls = CopyOnWriteArrayList<String>()
        val stream = FakeStream(error = "SCREEN_DOWN no sandbox\n", code = 3)
        val c = ScreenConnection(stream, host(calls), scope)
        c.start(fit = true, viewOnly = true)
        stream.server.close()
        val state = runBlocking { withTimeout(5_000) { c.state.first { it is ScreenConnection.State.Closed } } }
        assertEquals(ScreenConnection.State.Closed("Bot 屏幕没开"), state)
        waitFor("页面也知道断了") { calls.any { it.startsWith("hm.closed(") } }
    }

    @Test
    fun jsStringsAreSafe() {
        assertEquals("\"a\\\"b\\\\c\\n\"", ScreenConnection.jsString("a\"b\\c\n"))
        assertEquals("\"\\u003c/script>\"", ScreenConnection.jsString("</script>"))
        assertEquals("\"你好\"", ScreenConnection.jsString("你好"))
    }
}

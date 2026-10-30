package com.hermes.mobile.data.remote

import com.hermes.mobile.data.ssh.RemoteStream
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SSH 底层断了（息屏、换网络）时服务器发不回退出码；RoomClient 要自己发现通道死了并换一条。 */
class RoomClientReconnectTest {
    /** 假的群聊桥：先说 ready，之后每行请求回一个空的群列表。[kill] 模拟底层连接断掉。 */
    private class FakeBridge : RemoteStream {
        private val toClient = PipedOutputStream()
        override val output = PipedInputStream(toClient, 64 * 1024)
        private val fromClient = PipedInputStream(64 * 1024)
        private val pipe = PipedOutputStream(fromClient)
        @Volatile var dead = false
        var calls = 0
        override val input: OutputStream = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()))
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (dead) throw IOException("Broken transport")
                pipe.write(b, off, len)
            }
            override fun flush() {
                if (dead) throw IOException("Broken transport")
                pipe.flush()
            }
        }
        // 和断网一样：永远等不到退出码
        override val exitCode: Int? get() = null
        override fun errorText() = ""

        init {
            Thread {
                runCatching {
                    toClient.write("{\"ready\":true}\n".toByteArray()); toClient.flush()
                    fromClient.bufferedReader().forEachLine { line ->
                        val id = Regex("\"id\":(\\d+)").find(line)!!.groupValues[1]
                        calls++
                        toClient.write("{\"id\":$id,\"result\":{\"rooms\":[]}}\n".toByteArray()); toClient.flush()
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        fun kill() {
            dead = true
            runCatching { toClient.close() }
        }

        override fun close() {
            dead = true
            runCatching { toClient.close() }
            runCatching { fromClient.close() }
        }
    }

    @Test fun deadChannelWithoutExitCodeIsReplacedOnNextCall() = runBlocking {
        val bridges = mutableListOf<FakeBridge>()
        val client = RoomClient { FakeBridge().also { bridges += it } }
        assertEquals(emptyList(), client.rooms())
        bridges.single().kill()
        Thread.sleep(200) // 读线程读到 EOF
        // 不能一直报“连接断了”：直接换一条新的通道
        assertEquals(emptyList(), client.rooms())
        assertEquals(2, bridges.size)
        assertEquals(emptyList(), client.rooms())
        assertEquals(2, bridges.size, "新通道好好的就继续用")
    }

    @Test fun failedWriteDropsTheChannelSoTheNextCallRecovers() = runBlocking {
        val bridges = mutableListOf<FakeBridge>()
        val client = RoomClient { FakeBridge().also { bridges += it } }
        client.rooms()
        // 只有写不进去（读线程还没发现）：这一次报错，下一次换新的
        bridges.single().dead = true
        val first = runCatching { client.rooms() }
        assertTrue(first.isFailure)
        assertEquals(emptyList(), client.rooms())
        assertEquals(2, bridges.size)
    }
}

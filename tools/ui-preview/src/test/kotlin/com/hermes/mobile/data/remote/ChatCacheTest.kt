package com.hermes.mobile.data.remote

import com.hermes.mobile.data.model.ChatItem
import com.hermes.mobile.data.model.ConnectionProfile
import com.hermes.mobile.data.model.ConnectionState
import com.hermes.mobile.data.model.ConnectionType
import com.hermes.mobile.data.model.ImageRef
import com.hermes.mobile.data.model.Role
import com.hermes.mobile.data.model.Session
import com.hermes.mobile.data.store.ChatCache
import com.hermes.mobile.platform.FakeSecretBox
import com.hermes.mobile.platform.MemoryLocalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 本机缓存：打开就有列表和记录，没网也能看。 */
class ChatCacheTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val servers = mutableListOf<FakeHermesServer>()
    private val cacheDir = kotlin.io.path.createTempDirectory("cache").toFile()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        servers.forEach { runCatching { it.shutdown() } }
    }

    private fun repo(store: MemoryLocalStore) =
        RemoteHermesRepository(scope, store, FakeSecretBox(), cacheDir = cacheDir, backoff = { 60_000L })

    @Test
    fun listAndHistoryAreShownFromCacheWhenHermesIsUnreachable() {
        val s = FakeHermesServer(FakeHermesServer.Version.Modern).start().also { servers += it }
        val store = MemoryLocalStore()
        val first = repo(store)
        first.saveConnection(ConnectionProfile(id = "c1", name = "测试", type = ConnectionType.Direct, host = s.server.hostName, port = s.server.port), s.key)
        runBlocking {
            withTimeout(10_000) { first.sessions.first { list -> list.any { it.id == "s1" } } }
            withTimeout(10_000) { first.chatItems("s1").first { list -> list.any { it is ChatItem.Message && it.text == "DNS 改好了。" } } }
            delay(1_500) // 列表攒一会儿才写
        }
        s.shutdown()

        val second = repo(store)
        val cachedList = second.sessions.value
        assertTrue(cachedList.any { it.id == "s1" && it.title == "家庭网络" }, "还没连上就有列表：$cachedList")
        val state = runBlocking { withTimeout(15_000) { second.connectionState.first { it is ConnectionState.Failed } } }
        println("连不上：$state")
        assertTrue(second.sessions.value.any { it.id == "s1" }, "连不上也保留列表")
        val history = runBlocking { withTimeout(5_000) { second.chatItems("s1").first { it.isNotEmpty() } } }
        assertTrue(history.any { it is ChatItem.Message && it.text == "DNS 改好了。" }, "没网也能看记录：$history")
    }

    @Test
    fun cacheFollowsTheServer() {
        val cache = ChatCache(cacheDir)
        val now = 1_760_000_000_000L
        cache.saveSessions("c", listOf(Session("a", "A", "", now), Session("b", "B", "", now, running = true)))
        val message = ChatItem.Message("1", now, Role.Assistant, "你好", images = listOf(ImageRef("i", 10f, 1.5f, "hermes-media:/x.png")))
        cache.saveMessages("c", "a", listOf(message))
        cache.saveMessages("c", "b", listOf(message))
        assertEquals(listOf(message), cache.messages("c", "a"))
        assertEquals(false, cache.sessions("c").first { it.id == "b" }.running, "“正在回复”不留到下次")

        // 服务器上 b 被删了：列表更新时把它的记录一起清掉
        cache.saveSessions("c", listOf(Session("a", "A", "", now)))
        assertNull(cache.messages("c", "b"))
        assertEquals(listOf(message), cache.messages("c", "a"))

        cache.saveMessages("c", "a", (1..400).map { message.copy(id = "m$it") })
        assertEquals(ChatCache.MaxMessages, cache.messages("c", "a")!!.size, "只留最近的")
        assertEquals("m400", cache.messages("c", "a")!!.last().id)

        cache.removeConnection("c")
        assertTrue(cache.sessions("c").isEmpty())
    }
}

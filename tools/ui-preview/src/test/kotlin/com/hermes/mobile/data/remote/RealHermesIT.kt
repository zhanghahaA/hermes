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
}

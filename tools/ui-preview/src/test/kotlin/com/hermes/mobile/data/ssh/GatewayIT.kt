package com.hermes.mobile.data.ssh

import com.hermes.mobile.data.model.ActivityStatus
import com.hermes.mobile.data.model.ChatItem
import com.hermes.mobile.data.model.ConnectionProfile
import com.hermes.mobile.data.model.ConnectionState
import com.hermes.mobile.data.model.ConnectionType
import com.hermes.mobile.data.model.Role
import com.hermes.mobile.data.model.RunPhase
import com.hermes.mobile.data.model.SshAuthMethod
import com.hermes.mobile.data.model.WorkStep
import com.hermes.mobile.data.remote.RemoteHermesRepository
import com.hermes.mobile.platform.FakeSecretBox
import com.hermes.mobile.platform.MemoryLocalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SSH 连接全走 Hermes gateway：经真正的 OpenSSH 连到测试环境里真在跑的 Hermes gateway（假模型 tools/fake-llm），
 * 把 gateway 才有的说法挨个跑一遍：审批、追问、停止、插话、工作过程、改会话、模型和推理强度、接着聊电脑上的会话。
 * 环境变量同 [OpenSshIT]；没设置就跳过。
 */
class GatewayIT {
    private val dir = System.getenv("HERMES_IT_SSH_DIR")?.let(::File)
    private val sshPort = System.getenv("HERMES_IT_SSH_PORT")?.toIntOrNull() ?: 22
    private val user = System.getenv("HERMES_IT_SSH_USER") ?: "root"
    private val enabled = dir != null && System.getenv("HERMES_IT_KEY") != null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest fun tearDown() = scope.cancel()

    private fun connected(): RemoteHermesRepository {
        val repo = RemoteHermesRepository(scope, MemoryLocalStore(), FakeSecretBox())
        val profile = ConnectionProfile(
            id = "gw", name = "Gateway", type = ConnectionType.Ssh, host = "127.0.0.1", port = 8642,
            sshUser = user, sshPort = sshPort, sshAuth = SshAuthMethod.Key,
        )
        // SSH 连接不需要 API Key
        repo.saveConnection(profile, null, File(dir, "user_ed25519").readText())
        scope.launch { repo.errors.collect { println("APP-ERROR $it") } }
        val state = runBlocking { withTimeout(30_000) { repo.connectionState.first { it is ConnectionState.Connected || it is ConnectionState.Failed } } }
        assertTrue(state is ConnectionState.Connected, state.toString())
        return repo
    }

    private fun <T> Flow<T>.await(what: String, timeoutMillis: Long = 60_000, predicate: (T) -> Boolean): T = runBlocking {
        var last: T? = null
        try {
            withTimeout(timeoutMillis) { first { last = it; predicate(it) } }
        } catch (e: Exception) {
            throw AssertionError("等不到：$what；最后看到的是 ${last.toString().take(1500)}", e)
        }
    }

    /** 服务器上改完了没有：每秒刷新一次列表再看（改名、置顶是先在本地改、再发给服务器的）。 */
    private fun RemoteHermesRepository.pollSessions(what: String, predicate: (List<com.hermes.mobile.data.model.Session>) -> Boolean) {
        repeat(30) {
            runBlocking { refreshSessions() }
            if (predicate(sessions.value)) return
            Thread.sleep(1000)
        }
        throw AssertionError("等不到：$what；列表是 ${sessions.value.map { it.id.take(14) + ":" + it.title + ":" + it.pinned }}")
    }

    private fun List<ChatItem>.replies() = filterIsInstance<ChatItem.Message>().filter { it.role == Role.Assistant }.map { it.text }

    @Test fun approvalAndClarifyCardsAreAnsweredThroughTheGateway() {
        if (!enabled) return println("跳过：没有设置 HERMES_IT_SSH_DIR / HERMES_IT_KEY")
        val repo = connected()
        val chat = repo.createSession()!!
        repo.sendMessage(chat, "运行命令", emptyList())
        val card = repo.chatItems(chat).await("审批卡片") { list -> list.any { it is ChatItem.Approval } }
            .filterIsInstance<ChatItem.Approval>().single()
        assertTrue(card.command.contains("rm -rf"), card.toString())
        assertEquals(RunPhase.AwaitingInput, runBlocking { repo.runPhase(chat).first() })
        repo.resolveApproval(chat, card.id, "once")
        repo.chatItems(chat).await("批准后的回复") { list -> list.replies().any { it.contains("命令执行完了") } }

        repo.sendMessage(chat, "追问我", emptyList())
        val question = repo.chatItems(chat).await("追问卡片") { list -> list.any { it is ChatItem.Clarify } }
            .filterIsInstance<ChatItem.Clarify>().single()
        assertEquals(listOf("红色", "蓝色"), question.options)
        repo.answerClarify(chat, question.id, "蓝色")
        repo.chatItems(chat).await("回答后的回复") { list -> list.replies().any { it.contains("就按你选的来") } }
        repo.deleteSession(chat)
    }

    @Test fun stopSteerAndWorkProcessFollowTheGatewayEvents() {
        if (!enabled) return println("跳过：没有设置 HERMES_IT_SSH_DIR / HERMES_IT_KEY")
        val repo = connected()
        repo.updateSettings { it.copy(showWorkProcess = true) }
        val chat = repo.createSession()!!

        // 工作过程：工具开始 / 结束都收到，回复正文不含思考
        repo.sendMessage(chat, "运行命令", emptyList())
        val card = repo.chatItems(chat).await("审批卡片") { list -> list.any { it is ChatItem.Approval } }.filterIsInstance<ChatItem.Approval>().single()
        repo.resolveApproval(chat, card.id, "once")
        val done = repo.chatItems(chat).await("回复和工作过程") { list ->
            list.replies().any { it.contains("命令执行完了") } &&
                list.filterIsInstance<ChatItem.Activity>().any { a -> a.status == ActivityStatus.Completed && a.steps.any { it.kind == WorkStep.Kind.Tool && it.finished } }
        }
        val step = done.filterIsInstance<ChatItem.Activity>().last().steps.first { it.kind == WorkStep.Kind.Tool }
        assertEquals("terminal", step.title)
        assertTrue(step.text.contains("rm -rf"), step.toString())
        assertTrue(done.replies().none { "SECRET-THINKING" in it }, "思考不进回复")

        // 插话：慢工具执行中再发一句，这一轮的模型看到它
        repo.sendMessage(chat, "慢慢查", emptyList())
        repo.runPhase(chat).await("开始工作") { it == RunPhase.Working }
        Thread.sleep(1500)
        repo.sendMessage(chat, "顺便看看磁盘", emptyList())
        repo.chatItems(chat).await("插话被看到") { list -> list.replies().any { it.contains("收到插话") && it.contains("顺便看看磁盘") } }
        repo.runPhase(chat).await("空闲") { it == RunPhase.Idle }

        // 停止：逐段回复到一半停下
        repo.sendMessage(chat, "慢慢说", emptyList())
        repo.streamingText(chat).await("开始出字") { !it.isNullOrBlank() }
        repo.stop(chat)
        repo.runPhase(chat).await("停下", timeoutMillis = 20_000) { it == RunPhase.Idle }
        val stopped = runBlocking { repo.chatItems(chat).first() }
        assertTrue(stopped.filterIsInstance<ChatItem.Activity>().last().status == ActivityStatus.Stopped, stopped.toString())
        repo.deleteSession(chat)
    }

    @Test fun sessionEditsGoThroughSessionMutate() {
        if (!enabled) return println("跳过：没有设置 HERMES_IT_SSH_DIR / HERMES_IT_KEY")
        val repo = connected()
        val draft = repo.createSession()!!
        repo.sendMessage(draft, "起个头", emptyList())
        repo.chatItems(draft).await("回复") { list -> list.replies().any { it.contains("起个头") } }
        val id = repo.session(draft).await("正式会话") { it != null && !it.id.startsWith("draft") }!!.id

        // Hermes 要求标题不重复，用一个这次专用的
        val title = "手机改的名字 " + System.currentTimeMillis()
        repo.sessions.await("新会话进了列表") { list -> list.any { it.id == id } }
        repo.renameSession(id, title)
        repo.togglePin(id)
        repo.pollSessions("改名和置顶在服务器上生效") { list -> list.any { it.id == id && it.title == title && it.pinned } }

        val fork = assertNotNull(repo.forkSession(id, null))
        // 分叉先返回一个占位 id，服务器建好后对上真正的会话
        val forkId = repo.session(fork).await("分叉建好") { it != null && !it.id.startsWith("draft") }!!.id
        repo.pollSessions("分叉出现在列表里") { list -> list.any { it.id == forkId } }

        repo.setArchived(id, true)
        repo.pollSessions("归档后不在列表里") { list -> list.none { it.id == id } }
        repo.setArchived(id, false)
        repo.pollSessions("取消归档回来了") { list -> list.any { it.id == id } }

        repo.deleteSession(id)
        repo.deleteSession(forkId)
        repo.pollSessions("删掉了") { list -> list.none { it.id == id } }
    }

    @Test fun modelAndReasoningEffortAreApplied() {
        if (!enabled) return println("跳过：没有设置 HERMES_IT_SSH_DIR / HERMES_IT_KEY")
        val repo = connected()
        val model = repo.models.await("模型清单") { list -> list.any { it.model == "fake-model" } }.first { it.model == "fake-model" }
        val chat = repo.createSession()!!
        repo.setSessionModel(chat, model.id)
        repo.setReasoningEffort(chat, "high")
        repo.sendMessage(chat, "用指定的模型", emptyList())
        repo.chatItems(chat).await("回复") { list -> list.replies().any { it.contains("用指定的模型") } }
        val id = runBlocking { repo.session(chat).first() }!!.id
        // 服务器上这个会话的推理强度改成了 high（gateway 的 session.mutate reasoning；存在会话的运行策略里，问 gateway）
        val effort = gatewayPython("""
s = await c.rpc("session.resume", session_id="$id", omit_messages=True)
print("EFFORT=" + str((s.get("info") or {}).get("reasoning_effort")))
""")
        println(effort)
        assertTrue(effort.contains("EFFORT=high"), effort)
        repo.deleteSession(id)
    }

    /** 在测试环境的电脑上用 Hermes 自己的客户端连 gateway 跑一段（[body] 里能用 `c`）。 */
    private fun gatewayPython(body: String): String {
        val script = """
import asyncio, os, uuid, json
os.environ["HERMES_HOME"] = "/home/user/ref/hermes-home"
async def main():
    from hermes_constants import get_hermes_home
    from hermes_cli.gateway_runtime import discover_gateway_endpoint, gateway_profile_home
    from hermes_cli.gateway_client import GatewayClient, _session_ticket, gateway_ws_target
    from websockets.asyncio.client import connect
    home = gateway_profile_home(get_hermes_home())
    found = await asyncio.to_thread(discover_gateway_endpoint, home)
    ticket = await asyncio.to_thread(_session_ticket, home, found.endpoint, scope="host")
    url, protocols = gateway_ws_target(found.endpoint, ticket)
    async with connect(url, subprotocols=protocols, max_size=16*1024*1024, proxy=None) as ws:
        async with GatewayClient(ws) as c:
""" + body.trimIndent().lines().joinToString("\n") { "            $it" } + "\nasyncio.run(main())\n"
        // 脚本里有中文：写成 UTF-8 文件再跑（测试 JVM 的默认编码不是 UTF-8，直接当命令行参数会变成问号）
        val file = File.createTempFile("gateway-it", ".py").apply { writeText(script, Charsets.UTF_8); deleteOnExit() }
        return ProcessBuilder("/home/user/ref/hvenv/bin/python", file.path).redirectErrorStream(true).start()
            .inputStream.bufferedReader(Charsets.UTF_8).readText()
    }

    @Test fun aSessionStartedOnTheDesktopCanBeContinued() {
        if (!enabled) return println("跳过：没有设置 HERMES_IT_SSH_DIR / HERMES_IT_KEY")
        // 像电脑版那样经 gateway 建一个会话、聊一句（来源不是 api_server，API Server 会拒绝继续）
        val out = gatewayPython("""
s = await c.rpc("session.create", source="tui")
await c.rpc("prompt.submit", session_id=s["session_id"], text="电脑上说的第一句", submission_id=uuid.uuid4().hex)
while True:
    ev = await asyncio.wait_for(c.events.get(), 30)
    if ev.get("params", {}).get("type") == "message.complete":
        break
print("SID=" + s["session_id"])
""")
        val desktop = Regex("SID=(\\S+)").find(out)?.groupValues?.get(1) ?: throw AssertionError("电脑端会话没建成：$out")
        val repo = connected()
        runBlocking { repo.refreshSessions() }
        val listed = repo.sessions.await("电脑上的会话出现在列表里") { list -> list.any { it.id == desktop } }.first { it.id == desktop }
        assertFalse(listed.readOnly)
        val history = repo.chatItems(desktop).await("读到电脑上的记录") { list -> list.filterIsInstance<ChatItem.Message>().any { it.text == "电脑上说的第一句" } }
        assertTrue(history.replies().isNotEmpty())
        repo.sendMessage(desktop, "手机接着说", emptyList())
        repo.chatItems(desktop).await("手机上接着聊") { list -> list.replies().any { it.contains("手机接着说") } }
        assertFalse(runBlocking { repo.session(desktop).first() }!!.readOnly, "不能被标成只读")
        repo.deleteSession(desktop)
    }
}

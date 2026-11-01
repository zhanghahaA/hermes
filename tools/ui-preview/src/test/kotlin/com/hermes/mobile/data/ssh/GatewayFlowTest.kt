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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SSH 连接全走 Hermes gateway 时的聊天流程（真 SSH 服务器 + [FakeGateway]）。真实 Hermes 上的同类测试在 [GatewayIT]；
 * 这里专门测那边不好造的情况：手机断线时这一轮结束了、别处改过会话（revision 旧了）、重名、断线重连后补发。
 */
class GatewayFlowTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cleanup = mutableListOf<() -> Unit>()
    private val keys = SshKeys.generate()
    private val gateway = FakeGateway()

    @AfterTest fun tearDown() {
        scope.cancel()
        cleanup.asReversed().forEach { runCatching(it) }
    }

    private fun sshd(server: TestSshServer = TestSshServer(authorizedKey = keys.publicKey)) =
        gateway.install(server).start().also { s -> cleanup += { s.stop() } }

    private fun connected(ssh: TestSshServer, clock: () -> Long = System::currentTimeMillis): RemoteHermesRepository {
        val repo = RemoteHermesRepository(scope, MemoryLocalStore(), FakeSecretBox(), backoff = { 100L }, clock = clock)
        repo.saveConnection(ConnectionProfile(
            id = "gw", name = "家里电脑", type = ConnectionType.Ssh, host = "127.0.0.1", port = 8642,
            sshUser = ssh.user, sshPort = ssh.port, sshAuth = SshAuthMethod.Key,
        ), null, keys.privateKey)
        val state = runBlocking { withTimeout(20_000) { repo.connectionState.first { it is ConnectionState.Connected || it is ConnectionState.Failed } } }
        assertTrue(state is ConnectionState.Connected, state.toString())
        runBlocking { withTimeout(5_000) { repo.sessions.first { it.isNotEmpty() } } }
        return repo
    }

    private fun <T> Flow<T>.await(what: String, timeoutMillis: Long = 15_000, predicate: (T) -> Boolean): T = runBlocking {
        var last: T? = null
        try {
            withTimeout(timeoutMillis) { first { last = it; predicate(it) } }
        } catch (e: Exception) {
            throw AssertionError("等不到：$what；最后看到的是 ${last.toString().take(1200)}", e)
        }
    }

    private fun List<ChatItem>.replies() = filterIsInstance<ChatItem.Message>().filter { it.role == Role.Assistant }.map { it.text }
    private fun methods() = gateway.requests.mapNotNull { it["method"]?.toString()?.trim('"') }

    @Test fun refreshLatencyMeasuresEachGatewayPing() {
        val roundTrips = listOf(170L, 380L, 95L)
        // 模拟时间只在服务器收到 ping 时推进；本地缓存读取不耗时。
        val repo = connected(sshd(), clock = { 1_000L + roundTrips.take(methods().count { it == "ping" }).sum() })
        for (expected in roundTrips) {
            runBlocking { repo.refreshSessions() }
            assertEquals(expected.toInt(), (repo.connectionState.value as ConnectionState.Connected).latencyMs,
                "每次刷新都显示这次 ping 的往返耗时，而不是缓存读取的 1 ms")
        }
        assertEquals(3, methods().count { it == "ping" }, "沿用每次刷新的原有 ping，不额外发送请求")
    }

    @Test fun approvalDenyAndWorkProcessComeFromGatewayEvents() {
        val repo = connected(sshd())
        repo.updateSettings { it.copy(showWorkProcess = true) }
        repo.sendMessage("s1", "看看目录", emptyList())
        val done = repo.chatItems("s1").await("回复和工作过程") { list ->
            list.replies().any { it == "最终回复：看看目录" } && list.filterIsInstance<ChatItem.Activity>().any { it.status == ActivityStatus.Completed }
        }
        val tool = done.filterIsInstance<ChatItem.Activity>().last().steps.single { it.kind == WorkStep.Kind.Tool }
        assertEquals("terminal", tool.title)
        assertEquals("ls", tool.text)
        assertEquals("a.txt", tool.output, "工具结果")
        assertTrue(tool.finished)
        assertTrue(done.replies().none { "SECRET" in it }, "思考不进回复")

        repo.sendMessage("s1", "需要审批", emptyList())
        val card = repo.chatItems("s1").await("审批卡片") { list -> list.any { it is ChatItem.Approval && it.resolvedChoice == null } }
            .filterIsInstance<ChatItem.Approval>().last()
        assertEquals("rm -rf /tmp/x", card.command)
        assertEquals(RunPhase.AwaitingInput, runBlocking { repo.runPhase("s1").first() })
        repo.resolveApproval("s1", card.id, "deny")
        repo.chatItems("s1").await("拒绝后的回复") { list -> list.replies().any { it == "好的，不执行了。" } }
        assertTrue("approval.respond" in methods())
    }

    /** 回复到一半手机断线（SSH 服务器重启），这一轮在断线期间结束：重连后补发 / 查结果，回复照样出来。 */
    @Test fun aTurnThatFinishesWhileDisconnectedIsReplayedAfterReconnecting() {
        var ssh = sshd()
        val repo = connected(ssh)
        repo.sendMessage("s1", "慢慢来", emptyList())
        repo.runPhase("s1").await("开始工作") { it == RunPhase.Working }
        val s1 = gateway.sessions.getValue("s1")
        runBlocking { withTimeout(10_000) { while (!s1.busy) kotlinx.coroutines.delay(20) } }

        val port = ssh.port
        ssh.stop()
        s1.released = true // 断线期间这一轮结束了
        runBlocking { withTimeout(5_000) { while (s1.busy) kotlinx.coroutines.delay(20) } }
        ssh = sshd(TestSshServer(authorizedKey = keys.publicKey, hostKeyFile = ssh.hostKeyFile, port = port))
        repo.onNetworkAvailable()

        repo.chatItems("s1").await("重连后补到回复", timeoutMillis = 30_000) { list -> list.replies().any { it == "最终回复：慢慢来" } }
        repo.runPhase("s1").await("回到空闲") { it == RunPhase.Idle }
        assertTrue(ssh.logins.get() >= 1, "重新登录了")
        // 两条路都对：按 seq 补发事件；或者先查这一轮的结果，已经结束就直接重读记录（断线那一刻补发请求还没送到时走这条）
        assertTrue(methods().any { it == "session.events.since" || it == "prompt.receipt" }, "补发或查结果：${methods()}")
    }

    @Test fun steerAndStopUseTheCurrentGeneration() {
        val repo = connected(sshd())
        repo.updateSettings { it.copy(showWorkProcess = true) }
        repo.sendMessage("s1", "慢慢查", emptyList())
        repo.runPhase("s1").await("开始工作") { it == RunPhase.Working }
        runBlocking { withTimeout(10_000) { while (!gateway.sessions.getValue("s1").busy) kotlinx.coroutines.delay(20) } }
        repo.sendMessage("s1", "顺便看看磁盘", emptyList())
        repo.chatItems("s1").await("插话被看到") { list -> list.replies().any { it == "收到插话：顺便看看磁盘" } }
        repo.runPhase("s1").await("空闲") { it == RunPhase.Idle }

        repo.sendMessage("s1", "慢慢说", emptyList())
        repo.streamingText("s1").await("开始出字") { it == "正在" }
        repo.stop("s1")
        repo.runPhase("s1").await("停下") { it == RunPhase.Idle }
        val activity = runBlocking { repo.chatItems("s1").first() }.filterIsInstance<ChatItem.Activity>().last()
        assertEquals(ActivityStatus.Stopped, activity.status)
        assertTrue("session.steer" in methods() && "session.interrupt" in methods())
    }

    /** 电脑上刚改过这个会话（revision 变了）：gateway 拒绝并带回新值，App 照着重试一次就成功。重名时说清楚原因。 */
    @Test fun editsRetryOnAStaleRevisionAndDuplicateTitlesAreExplained() {
        val repo = connected(sshd())
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        scope.launch { repo.errors.collect { errors += it } }
        // 先订阅一次（读记录），再让“电脑”改一下
        repo.chatItems("s1").await("记录") { list -> list.isNotEmpty() }
        gateway.sessions.getValue("s1").revision += 3
        repo.renameSession("s1", "手机改的名字")
        runBlocking { withTimeout(10_000) { while (gateway.sessions.getValue("s1").title != "手机改的名字") kotlinx.coroutines.delay(20) } }
        val conflicts = gateway.requests.count { it["method"]?.toString() == "\"session.mutate\"" }
        assertTrue(conflicts >= 2, "第一次被拒，带新 revision 重试：$conflicts 次")

        repo.togglePin("s1")
        runBlocking { withTimeout(10_000) { while (!gateway.sessions.getValue("s1").pinned) kotlinx.coroutines.delay(20) } }

        val other = gateway.Session("other", "已经有的名字", "local")
        gateway.sessions["other"] = other
        repo.renameSession("s1", "已经有的名字")
        runBlocking { withTimeout(10_000) { while (errors.none { "标题不重复" in it }) kotlinx.coroutines.delay(20) } }
        repo.sessions.await("改名失败后改回原来的") { list -> list.first { it.id == "s1" }.title == "手机改的名字" }
    }

    /**
     * 真实 Hermes 上出现过：改名紧接着置顶（两个同时发），这时 Hermes 又刚好在自己起标题、revision 连着变，
     * 改名重试一次还是冲突就丢了。现在同一个会话的修改排队发、多试几次。
     */
    @Test fun renameAndPinTogetherSurviveRevisionsChangingUnderneath() {
        val repo = connected(sshd())
        repo.chatItems("s1").await("记录") { list -> list.isNotEmpty() }
        val bumps = java.util.concurrent.atomic.AtomicInteger(2)
        gateway.beforeMutate = { s -> if (bumps.getAndDecrement() > 0) s.revision++ }
        repo.renameSession("s1", "一起改的名字")
        repo.togglePin("s1")
        val s1 = gateway.sessions.getValue("s1")
        runBlocking { withTimeout(10_000) { while (s1.title != "一起改的名字" || !s1.pinned) kotlinx.coroutines.delay(20) } }
    }

    @Test fun modelAndReasoningEffortReachTheGateway() {
        val repo = connected(sshd())
        val other = repo.models.await("模型清单") { list -> list.any { it.model == "other-model" } }.first { it.model == "other-model" }
        assertTrue(!other.reasoning, "这个模型 Hermes 说不支持推理")
        repo.setSessionModel("s1", other.id)
        repo.setReasoningEffort("s1", "high")
        repo.sendMessage("s1", "换个模型", emptyList())
        repo.chatItems("s1").await("回复") { list -> list.replies().any { it == "最终回复：换个模型" } }
        val s1 = gateway.sessions.getValue("s1")
        assertEquals("high", s1.effort, "推理强度改到会话上")
        val submit = gateway.requests.last { it["method"]?.toString() == "\"prompt.submit\"" }["params"].toString()
        assertTrue(submit.contains("\"model_override\":{\"model\":\"other-model\",\"provider\":\"custom\"}"), submit)
        assertTrue(submit.contains("\"api_text\""), "App 的补充说明给模型、不进聊天记录：$submit")
        assertTrue(!submit.contains("SECRET"))
        // 再发一次：推理强度没变就不再改
        val before = gateway.requests.count { it["method"]?.toString() == "\"session.mutate\"" }
        repo.sendMessage("s1", "再来一次", emptyList())
        repo.chatItems("s1").await("第二次回复") { list -> list.replies().any { it == "最终回复：再来一次" } }
        assertEquals(before, gateway.requests.count { it["method"]?.toString() == "\"session.mutate\"" })
    }

    /**
     * 用户手机上（新版 Hermes）出现过：打开一个旧对话发消息，弹“发送失败：Hermes gateway：session_read_only”。
     * 那是 Hermes 存档的旧记录（以前经 API 建的等），只能看；App 要说清楚原因、标成只读，并且不再提供必然失败的分叉。
     */
    @Test fun storedHistoryIsExplainedAndMarkedReadOnly() {
        val old = gateway.Session("old", "以前的对话", "api_server").apply {
            storedHistory = true
            messages += gateway.message("user", "要不要装上")
            messages += gateway.message("assistant", "说一声就动手。", finish = "stop")
        }
        gateway.sessions[old.id] = old
        val repo = connected(sshd())
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        scope.launch { repo.errors.collect { errors += it } }
        repo.refreshSessionsBlocking()
        repo.chatItems("old").await("能看记录") { list -> list.replies().any { it == "说一声就动手。" } }

        repo.sendMessage("old", "?", emptyList())
        repo.chatItems("old").await("这条标成发送失败") { list ->
            list.filterIsInstance<ChatItem.Message>().any { it.role == Role.User && it.text == "?" && it.status == com.hermes.mobile.data.model.DeliveryStatus.Failed }
        }
        runBlocking { withTimeout(10_000) { while (errors.none { "存档的旧对话" in it }) kotlinx.coroutines.delay(20) } }
        assertTrue(errors.none { "session_read_only" in it }, "不把 Hermes 的原始代码直接给用户看：$errors")
        val marked = repo.session("old").await("标成存档的只读对话") { it?.storedHistory == true }!!
        assertTrue(marked.readOnly)

        // 刷新列表后还记得
        repo.refreshSessionsBlocking()
        assertTrue(runBlocking { repo.session("old").first() }!!.storedHistory, "刷新后不能忘了")
        // 其它会话不受影响
        assertTrue(!runBlocking { repo.session("s1").first() }!!.readOnly)
    }

    /**
     * 用户手机上真正出的问题：电脑上能接着聊的长对话（压缩过），手机上发消息回 `session_read_only`。
     * 列表里是压缩后那一段的 id，gateway 打开时换成会话本身的 id；App 以前还拿列表里的 id 去发。
     * 现在发消息、改会话、停止都用换过的 id，对话照常接着聊，也不会被标成只读。
     */
    @Test fun aCompressedConversationIsContinuedUnderTheIdTheGatewayResumesItAs() {
        val bot = gateway.Session("local-botchat", "Bot Chat", "local").apply {
            listedAs = "20261031_233700_tip"
            messages += gateway.message("user", "要不要装上")
            messages += gateway.message("assistant", "说一声就动手。", finish = "stop")
        }
        gateway.sessions[bot.id] = bot
        val repo = connected(sshd())
        repo.refreshSessionsBlocking()
        val listed = "20261031_233700_tip"
        assertTrue(repo.sessions.value.any { it.id == listed }, "列表里是压缩后那一段的 id")
        repo.chatItems(listed).await("能看记录") { list -> list.replies().any { it == "说一声就动手。" } }

        repo.sendMessage(listed, "装上吧", emptyList())
        repo.chatItems(listed).await("接着聊，收到回复") { list -> list.replies().any { it == "最终回复：装上吧" } }
        assertTrue(!runBlocking { repo.session(listed).first() }!!.readOnly, "不能被标成只读")
        val submit = gateway.requests.last { it["method"]?.toString() == "\"prompt.submit\"" }["params"]!!.jsonObject
        assertEquals("local-botchat", submit["session_id"]!!.jsonPrimitive.content, "发到 gateway 换出来的那个 id")

        repo.togglePin(listed)
        runBlocking { withTimeout(10_000) { while (!bot.pinned) kotlinx.coroutines.delay(20) } }

        repo.updateSettings { it.copy(showWorkProcess = true) }
        repo.sendMessage(listed, "慢慢说", emptyList())
        repo.streamingText(listed).await("开始出字") { it == "正在" }
        repo.stop(listed)
        repo.runPhase(listed).await("停下") { it == RunPhase.Idle }
        runBlocking { withTimeout(10_000) { while (methods().none { it == "session.interrupt" }) kotlinx.coroutines.delay(20) } }
        val interrupt = gateway.requests.last { it["method"]?.toString() == "\"session.interrupt\"" }["params"]!!.jsonObject
        assertEquals("local-botchat", interrupt["session_id"]!!.jsonPrimitive.content, "停止也发到换过的 id")
        assertTrue(runBlocking { repo.chatItems(listed).first() }.filterIsInstance<ChatItem.Message>().none { it.status == com.hermes.mobile.data.model.DeliveryStatus.Failed }, "没有发送失败的消息")
    }

    private fun RemoteHermesRepository.refreshSessionsBlocking() = runBlocking { refreshSessions() }

    @Test fun newChatIsCreatedThroughTheGatewayAndGetsItsTitle() {
        val repo = connected(sshd())
        val draft = repo.createSession()!!
        repo.sendMessage(draft, "你好", emptyList())
        repo.chatItems(draft).await("回复") { list -> list.replies().any { it == "最终回复：你好" } }
        val real = repo.session(draft).await("正式会话") { it != null && it.id.startsWith("local-") }!!
        assertTrue("session.create" in methods())
        repo.sessions.await("Hermes 起的标题") { list -> list.any { it.id == real.id && it.title == "关于你好" } }
    }

    @Test fun gatewayNotRunningFailsTheConnectionWithAReason() {
        gateway.running = false
        val ssh = sshd()
        val repo = RemoteHermesRepository(scope, MemoryLocalStore(), FakeSecretBox(), backoff = { 100L })
        repo.saveConnection(ConnectionProfile(
            id = "gw", name = "家里电脑", type = ConnectionType.Ssh, host = "127.0.0.1", port = 8642,
            sshUser = ssh.user, sshPort = ssh.port, sshAuth = SshAuthMethod.Key,
        ), null, keys.privateKey)
        val state = repo.connectionState.await("连接失败") { it is ConnectionState.Failed }
        assertTrue((state as ConnectionState.Failed).reason.contains("gateway 没在运行"), state.reason)
    }
}

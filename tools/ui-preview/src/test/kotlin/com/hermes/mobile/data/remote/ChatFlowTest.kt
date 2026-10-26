package com.hermes.mobile.data.remote

import com.hermes.mobile.data.model.Attachment
import com.hermes.mobile.data.model.ChatItem
import com.hermes.mobile.data.model.Draft
import com.hermes.mobile.data.model.ConnectionProfile
import com.hermes.mobile.data.model.ConnectionState
import com.hermes.mobile.data.model.ConnectionType
import com.hermes.mobile.data.model.DeliveryStatus
import com.hermes.mobile.data.model.Quote
import com.hermes.mobile.data.model.Role
import com.hermes.mobile.data.model.RunPhase
import com.hermes.mobile.data.store.StoredRun
import com.hermes.mobile.data.store.StoredState
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
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 阶段 2：发消息、收回复、审批、断线重连、只读会话、App 重启后接着等回复。 */
class ChatFlowTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val servers = mutableListOf<FakeHermesServer>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        servers.forEach { it.shutdown() }
    }

    private fun server() = FakeHermesServer(FakeHermesServer.Version.Modern).start().also { servers += it }

    private fun FakeHermesServer.profile() =
        ConnectionProfile(id = "c1", name = "测试", type = ConnectionType.Direct, host = server.hostName, port = server.port)

    private val mediaDir = kotlin.io.path.createTempDirectory("media").toFile()

    private fun connected(s: FakeHermesServer, store: MemoryLocalStore = MemoryLocalStore()): RemoteHermesRepository {
        val repo = RemoteHermesRepository(scope, store, FakeSecretBox(), mediaDir = mediaDir, backoff = { 50L })
        if (store.state?.connections.isNullOrEmpty()) repo.saveConnection(s.profile(), s.key)
        runBlocking { withTimeout(10_000) { repo.connectionState.first { it is ConnectionState.Connected } } }
        runBlocking { withTimeout(5_000) { repo.sessions.first { it.isNotEmpty() } } }
        return repo
    }

    private fun <T> Flow<T>.await(what: String, predicate: (T) -> Boolean): T = runBlocking {
        try {
            withTimeout(10_000) { first(predicate) }
        } catch (e: Exception) {
            throw AssertionError("等不到：$what", e)
        }
    }

    /** 收集一个 Flow 发出的所有值，检查过程中从没出现过思考内容。 */
    private fun <T> record(flow: Flow<T>): MutableList<T> {
        val seen = Collections.synchronizedList(mutableListOf<T>())
        scope.launch { flow.collect { seen += it } }
        return seen
    }

    private fun List<ChatItem>.texts() = filterIsInstance<ChatItem.Message>().map { it.role to it.text }

    @Test
    fun newChatSendsAndShowsOnlyTheFinalReply() {
        val s = server()
        val repo = connected(s)
        val draft = assertNotNull(repo.createSession())
        val history = record(repo.chatItems(draft))
        val streamed = record(repo.streamingText(draft))

        repo.sendMessage(draft, "你好", emptyList())
        val items = repo.chatItems(draft).await("最终回复") { list -> list.texts().any { it.second == "最终回复：你好" } }
        repo.runPhase(draft).await("回到空闲") { it == RunPhase.Idle }

        val final = repo.chatItems(draft).await("和服务器记录对齐") { list -> list.filterIsInstance<ChatItem.Message>().none { it.id.startsWith("local-") || it.id.startsWith("reply-") } }
        assertEquals(listOf(Role.User to "你好", Role.Assistant to "最终回复：你好"), final.texts(), "过程中的话、工具步骤都不显示：$items")

        val create = s.requests.first { it.method == "POST" && it.path == "/api/sessions" }
        assertNotNull(create, "草稿在第一次发消息时才在服务器上创建")
        val run = s.requests.first { it.path == "/v1/runs" }
        assertTrue(run.body.clone().readUtf8().contains("\"session_id\":\"new1\""))
        assertTrue(run.getHeader("Idempotency-Key")!!.startsWith("local-"), "带幂等键")

        assertTrue(repo.sessions.value.any { it.id == "new1" }, "新会话出现在列表里")
        repo.sessions.await("显示 Hermes 自动起的标题") { list -> list.firstOrNull { it.id == "new1" }?.title == "关于你好" }
        assertEquals("new1", runBlocking { repo.session(draft).first() }?.id, "界面拿着草稿 id 也能找到会话")
        Thread.sleep(200)
        val everything = history.flatten().filterIsInstance<ChatItem.Message>().map { it.text } + streamed.mapNotNull { it }
        assertTrue(everything.none { "SECRET" in it }, "思考内容在任何时候都不能出现：$everything")
        assertTrue(streamed.all { it == null }, "默认不逐字显示")
    }

    @Test
    fun streamingKeepsEarlierSentencesThroughToolsReconnectAndCompletion() {
        val s = server()
        val repo = connected(s)
        repo.updateSettings { it.copy(streamReplies = true) }
        repo.sendMessage("s1", "逐句回复", emptyList())
        repo.streamingText("s1").await("第一句") { it == "第一句。" }
        val run = s.runs.values.single()
        run.sentence("第二句。")
        val next = repo.streamingText("s1").await("第二句") { it?.contains("第二句。") == true }
        assertEquals("第一句。\n\n第二句。", next)
        run.complete()
        val done = repo.chatItems("s1").await("最终回复和过程收尾") { list ->
            list.texts().any { it.second == "最终回复：逐句回复" } &&
                list.filterIsInstance<ChatItem.Activity>().any { it.status == com.hermes.mobile.data.model.ActivityStatus.Completed }
        }
        assertEquals("第一句。\n\n第二句。", done.filterIsInstance<ChatItem.Activity>().single().earlierReply)
        val aligned = repo.chatItems("s1").await("历史对齐后仍保留先前正文") { list ->
            list.filterIsInstance<ChatItem.Message>().none { it.id.startsWith("local-") || it.id.startsWith("reply-") }
        }
        assertEquals("第一句。\n\n第二句。", aligned.filterIsInstance<ChatItem.Activity>().single().earlierReply)
        assertTrue(aligned.filterIsInstance<ChatItem.Activity>().all { it.steps.isEmpty() }, "默认不收集思考")
        assertTrue(s.requests.count { it.path!!.endsWith("/events") } >= 2, "真实走过断流和续传")
    }

    @Test fun processIsOptInStreamsWithoutTypewriterAndCanBeClearedImmediately() {
        val s = server()
        val store = MemoryLocalStore()
        val repo = connected(s, store)
        assertFalse(repo.settings.value.showWorkProcess)
        repo.updateSettings { it.copy(showWorkProcess = true) }
        assertTrue(store.state!!.settings.showWorkProcess)
        assertFalse(repo.settings.value.streamReplies)
        repo.sendMessage("s1", "逐句研究", emptyList())
        repo.streamingText("s1").await("过程模式自动实时显示正文") { it == "第一句。" }
        val activities = repo.chatItems("s1").await("真实思考和工具完成状态") { list ->
            list.filterIsInstance<ChatItem.Activity>().any { a -> a.steps.any { it.finished && it.title == "terminal" } }
        }.filterIsInstance<ChatItem.Activity>()
        assertTrue(activities.single().steps.any { "SECRET-THINKING" in it.text })
        assertTrue(s.requests.filter { it.method == "POST" }.none { "SECRET" in it.body.clone().readUtf8() }, "过程不会发回模型")
        repo.updateSettings { it.copy(showWorkProcess = false) }
        assertTrue(runBlocking { repo.chatItems("s1").first() }.none { it is ChatItem.Activity })
        assertNull(runBlocking { repo.streamingText("s1").first() })
        repo.updateSettings { it.copy(showWorkProcess = true) }
        assertTrue(runBlocking { repo.chatItems("s1").first() }.filterIsInstance<ChatItem.Activity>().single().steps.isEmpty(), "重新打开不能恢复刚清掉的思考")
    }

    @Test fun stoppingPreservesAlreadyVisibleText() {
        val s = server()
        val repo = connected(s)
        repo.updateSettings { it.copy(streamReplies = true) }
        repo.sendMessage("s1", "逐句停止", emptyList())
        repo.streamingText("s1").await("停止前正文") { it == "第一句。" }
        repo.stop("s1")
        val items = repo.chatItems("s1").await("停止后正文还在") { list ->
            list.filterIsInstance<ChatItem.Activity>().any { it.status == com.hermes.mobile.data.model.ActivityStatus.Stopped }
        }
        assertEquals("第一句。", items.filterIsInstance<ChatItem.Activity>().single().earlierReply)
    }

    @Test
    fun approvalCardRoundTrip() {
        val s = server()
        val repo = connected(s)
        repo.sendMessage("s1", "帮我审批一下", emptyList())
        val card = repo.chatItems("s1").await("审批卡片") { list -> list.any { it is ChatItem.Approval } }
            .filterIsInstance<ChatItem.Approval>().single()
        assertEquals("删除临时目录", card.summary)
        assertEquals("rm -rf /tmp/x", card.command)
        assertEquals(listOf("once", "session", "always", "deny"), card.choices)
        repo.runPhase("s1").await("等待用户") { it == RunPhase.AwaitingInput }

        repo.resolveApproval("s1", card.id, "once")
        val items = repo.chatItems("s1").await("批准后的回复") { list -> list.texts().any { it.second == "最终回复：帮我审批一下" } }
        val resolved = items.filterIsInstance<ChatItem.Approval>().single()
        assertEquals("once", resolved.resolvedChoice)
        val body = s.requests.first { it.path!!.endsWith("/approval") }.body.clone().readUtf8()
        assertTrue(body.contains("\"request_id\":\"p1\"") && body.contains("\"execution_generation\":3") && body.contains("\"choice\":\"once\""), body)

        // 和服务器记录对齐后，卡片还在用户消息和回复之间
        val final = repo.chatItems("s1").await("对齐") { list -> list.filterIsInstance<ChatItem.Message>().none { it.id.startsWith("local-") || it.id.startsWith("reply-") } }
        val cardIndex = final.indexOfFirst { it is ChatItem.Approval }
        assertEquals(Role.User, (final[cardIndex - 1] as ChatItem.Message).role)
        assertEquals("最终回复：帮我审批一下", (final[cardIndex + 1] as ChatItem.Message).text)
    }

    @Test
    fun droppedStreamResumesFromLastEvent() {
        val s = server()
        val repo = connected(s)
        repo.sendMessage("s1", "断线测试", emptyList())
        repo.chatItems("s1").await("断线后仍拿到回复") { list -> list.texts().any { it.second == "最终回复：断线测试" } }
        val eventRequests = s.requests.filter { it.path!!.endsWith("/events") }
        assertTrue(eventRequests.size >= 2, "断了要重连")
        assertEquals(null, eventRequests[0].getHeader("Last-Event-ID"))
        assertEquals("2", eventRequests[1].getHeader("Last-Event-ID"), "重连时带上最后处理的序号")
    }

    @Test
    fun conflictMarksSessionReadOnly() {
        val s = server()
        val repo = connected(s)
        repo.sendMessage("s2", "从手机继续 Telegram 会话", emptyList())
        val items = repo.chatItems("s2").await("发送失败") { list -> list.filterIsInstance<ChatItem.Message>().any { it.status == DeliveryStatus.Failed } }
        assertTrue(items.isNotEmpty())
        repo.session("s2").await("只读") { it?.readOnly == true }
        assertEquals(RunPhase.Idle, runBlocking { repo.runPhase("s2").first() })
    }

    @Test
    fun busyServerIsRetriedWithSameKey() {
        val s = server()
        s.failRunsTimes = 2
        val repo = connected(s)
        repo.sendMessage("s1", "忙也要发", emptyList())
        repo.chatItems("s1").await("重试后成功") { list -> list.texts().any { it.second == "最终回复：忙也要发" } }
        val keys = s.requests.filter { it.path == "/v1/runs" }.map { it.getHeader("Idempotency-Key") }
        assertEquals(3, keys.size)
        assertEquals(1, keys.toSet().size, "重试用同一个幂等键，Hermes 不会重复执行")
    }

    @Test
    fun stopCancelsTheRun() {
        val s = server()
        val repo = connected(s)
        repo.sendMessage("s1", "慢慢来", emptyList())
        repo.runPhase("s1").await("开始") { it == RunPhase.Working }
        runBlocking { withTimeout(5_000) { repo.activeRuns.first { "s1" in it } } }
        repo.stop("s1")
        repo.runPhase("s1").await("停止后空闲") { it == RunPhase.Idle }
        assertTrue(s.requests.any { it.path!!.endsWith("/stop") })
        assertTrue(repo.activeRuns.value.isEmpty())
    }

    @Test
    fun quoteIsSentAsBlockquoteAndShownAsQuote() {
        val s = server()
        val repo = connected(s)
        val quote = Quote("5", "DNS 改好了。", partial = false)
        repo.sendMessage("s1", "具体改了什么", emptyList(), quote)
        val final = repo.chatItems("s1").await("对齐") { list ->
            list.texts().any { it.second.startsWith("最终回复") } &&
                list.filterIsInstance<ChatItem.Message>().none { it.id.startsWith("local-") || it.id.startsWith("reply-") }
        }
        val input = s.requests.first { it.path == "/v1/runs" }.body.clone().readUtf8()
        assertTrue(input.contains("> DNS 改好了。\\n\\n具体改了什么"), input)
        val mine = final.filterIsInstance<ChatItem.Message>().last { it.role == Role.User }
        assertEquals("具体改了什么", mine.text)
        assertEquals("DNS 改好了。", mine.quote?.text)
        assertEquals("5", mine.quote?.messageId, "引用能定位回原消息")
    }

    @Test
    fun runSurvivesAppRestart() {
        val s = server()
        // 上次 App 被杀前提交了一轮，还没等到结果
        val run = s.FakeRun("run_old", "s1", "重启前发的")
        s.runs[run.id] = run
        val store = MemoryLocalStore()
        store.save(StoredState(
            connections = listOf(s.profile()),
            activeConnectionId = "c1",
            keys = mapOf("c1" to FakeSecretBox().encrypt(s.key)),
            runs = listOf(StoredRun("c1", "s1", "run_old")),
        ))
        val repo = connected(s, store)
        repo.chatItems("s1").await("重启后拿到回复") { list -> list.texts().any { it.second == "最终回复：重启前发的" } }
        repo.runPhase("s1").await("空闲") { it == RunPhase.Idle }
        Thread.sleep(200)
        assertTrue(store.state!!.runs.isEmpty(), "结束后从待办里删掉")
    }

    @Test
    fun pendingApprovalIsRestoredAfterRestart() {
        val s = server()
        val run = s.FakeRun("run_wait", "s1", "重启前要审批")
        s.runs[run.id] = run
        val store = MemoryLocalStore()
        store.save(StoredState(
            connections = listOf(s.profile()),
            activeConnectionId = "c1",
            keys = mapOf("c1" to FakeSecretBox().encrypt(s.key)),
            runs = listOf(StoredRun("c1", "s1", "run_wait")),
        ))
        val repo = connected(s, store)
        val card = repo.chatItems("s1").await("审批卡片恢复") { list -> list.any { it is ChatItem.Approval } }
            .filterIsInstance<ChatItem.Approval>()
        assertEquals(1, card.size, "事件重放和状态查询不能产生两张卡片")
        repo.resolveApproval("s1", card.single().id, "deny")
        repo.chatItems("s1").await("拒绝后的回复") { list -> list.texts().any { it.second == "好的，不执行了。" } }
    }

    /** 和手机上一样：图片按内容的 SHA-256 存进缓存目录。 */
    private fun cachedImage(color: Int): Attachment.Image {
        val image = java.awt.image.BufferedImage(40, 20, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 40) for (y in 0 until 20) image.setRGB(x, y, color)
        val bytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "jpg", it) }.toByteArray()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val file = java.io.File(mediaDir, "$sha.jpg").apply { writeBytes(bytes) }
        return Attachment.Image("img-$color", file.path, 2f)
    }

    @Test
    fun imagesAndFilesAreSentAndComeBackFromHistory() {
        val s = server()
        val repo = connected(s)
        val red = cachedImage(0xff0000)
        val blue = cachedImage(0x0000ff)
        val notes = Attachment.TextFile("t1", "notes.md", "# 标题\n```\ncode\n```", 30L)
        repo.sendMessage("s1", "看看这些", listOf(red, blue, notes))

        // 发送中：自己的气泡立刻显示图片和文件卡片
        val sending = repo.chatItems("s1").await("本地气泡") { list -> list.any { it is ChatItem.Message && it.text == "看看这些" } }
            .filterIsInstance<ChatItem.Message>().last { it.role == Role.User }
        assertEquals(listOf(red.path, blue.path), sending.images.map { it.source })
        assertEquals(listOf("notes.md"), sending.files.map { it.name })

        val final = repo.chatItems("s1").await("对齐后的记录") { list ->
            list.texts().any { it.second == "收到 2 张图片" } &&
                list.filterIsInstance<ChatItem.Message>().none { it.id.startsWith("local-") || it.id.startsWith("reply-") }
        }
        val request = s.requests.first { it.path == "/v1/runs" }.body.clone().readUtf8()
        assertTrue(request.contains("\"type\":\"image_url\"") && request.contains("data:image/jpeg;base64,"), request.take(300))
        assertTrue(request.contains("📎 附件：notes.md"), "文本文件的内容放进消息")

        // 从服务器记录里拆回来：正文干净，图片指回本机缓存，文件变成卡片
        val mine = final.filterIsInstance<ChatItem.Message>().last { it.role == Role.User }
        assertEquals("看看这些", mine.text)
        assertEquals(listOf(red.path, blue.path), mine.images.map { it.source }, "按 SHA-256 找回本机缓存的原图")
        assertEquals(listOf("notes.md"), mine.files.map { it.name })
    }

    @Test
    fun otherFilesNeedSshOrLocal() {
        val s = server()
        val repo = connected(s)
        assertFalse(repo.canSendAnyFile(), "直连不能发任意文件")
        val tmp = java.io.File.createTempFile("upload", ".zip").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        repo.sendMessage("s1", "压缩包", listOf(Attachment.BinaryFile("z", "a.zip", "application/zip", tmp.path, 3L)))
        repo.chatItems("s1").await("发送失败") { list -> list.filterIsInstance<ChatItem.Message>().any { it.status == DeliveryStatus.Failed } }
        assertTrue(s.requests.none { it.path == "/v1/runs" }, "不能发的文件不提交")
    }

    @Test
    fun sentImagesComeBackEvenIfHermesOnlyKeepsScreenshotMarkers() {
        val s = server()
        s.screenshotOnly = true
        val repo = connected(s)
        val red = cachedImage(0xff0000)
        repo.sendMessage("s1", "只记了截图标记", listOf(red))
        val final = repo.chatItems("s1").await("对齐后的记录") { list ->
            list.texts().any { it.second == "收到 1 张图片" } &&
                list.filterIsInstance<ChatItem.Message>().none { it.id.startsWith("local-") || it.id.startsWith("reply-") }
        }
        val mine = final.filterIsInstance<ChatItem.Message>().last { it.role == Role.User }
        assertEquals(listOf(red.path), mine.images.map { it.source }, "服务器没记路径，也要用本机记住的图")

        // 重新打开 App（新的仓库实例，同一个缓存目录）也还在
        val again = connected(s)
        val reloaded = again.chatItems("s1").await("重新加载") { list -> list.any { it is ChatItem.Message && it.text == "只记了截图标记" } }
        assertEquals(listOf(red.path), reloaded.filterIsInstance<ChatItem.Message>().last { it.role == Role.User }.images.map { it.source })
    }

    @Test
    fun repliesWithMediaShowImages() {
        val s = server()
        val repo = connected(s)
        repo.sendMessage("s1", "帮我画图", emptyList())
        val final = repo.chatItems("s1").await("带图的回复") { list ->
            list.filterIsInstance<ChatItem.Message>().any { it.role == Role.Assistant && it.text == "画好了，送你一只猫。" } &&
                list.filterIsInstance<ChatItem.Message>().none { it.id.startsWith("local-") || it.id.startsWith("reply-") }
        }
        val reply = final.filterIsInstance<ChatItem.Message>().last { it.role == Role.Assistant }
        assertEquals("hermes-media:/gen/cat.png", reply.images.single().source, "MEDIA 标签变成图片，不显示路径原文")
        assertNull(runBlocking { repo.fetchRemoteMedia("/gen/cat.png") }, "直连没有 SSH，拿不到服务器上的文件")
    }

    @Test
    fun draftsSurviveLeavingAndRestart() {
        val s = server()
        val store = MemoryLocalStore()
        val repo = connected(s, store)
        val image = cachedImage(0x00ff00)
        repo.saveDraft("s1", Draft("还没写完的话", listOf(image)))
        assertEquals("还没写完的话", repo.draft("s1").text)
        assertEquals(listOf(image), repo.draft("s1").attachments, "附件在 App 运行期间保留")
        assertEquals("还没写完的话", repo.drafts.value["s1"])
        Thread.sleep(800)
        val again = connected(s, store)
        assertEquals("还没写完的话", again.draft("s1").text, "重启后文字还在")
        again.saveDraft("s1", Draft(""))
        assertTrue(again.drafts.value.isEmpty())
    }

    @Test
    fun runsCarryClientInstructionsAndFilesOpen() {
        val s = server()
        val repo = connected(s)
        repo.sendMessage("s1", "你好", emptyList())
        repo.chatItems("s1").await("回复") { list -> list.texts().any { it.second == "最终回复：你好" } }
        val body = s.requests.first { it.path == "/v1/runs" }.body.clone().readUtf8()
        assertTrue(body.contains("\"instructions\"") && body.contains("MEDIA:/absolute/path"), "告诉模型这个 App 能收文件、能渲染 Markdown")

        val text = com.hermes.mobile.data.model.FileRef("a.md", inlineText = "# 糖")
        val path = assertNotNull(runBlocking { repo.fileForOpening(text) })
        assertEquals("# 糖", java.io.File(path).readText())
        assertTrue(path.endsWith(".md"))
        assertNull(runBlocking { repo.fileForOpening(com.hermes.mobile.data.model.FileRef("x.md", remotePath = "/srv/x.md")) }, "直连没有 SSH 拿不到服务器上的文件")
    }

    @Test
    fun imagesSurviveSystemNotesAppendedByHermes() {
        val s = server()
        s.screenshotOnly = true
        s.appendSystemNote = true
        val repo = connected(s)
        val img = cachedImage(0x123456)
        repo.sendMessage("s1", "我连接不上啊", listOf(img))
        val final = repo.chatItems("s1").await("对齐") { list ->
            list.texts().any { it.second == "收到 1 张图片" } &&
                list.filterIsInstance<ChatItem.Message>().none { it.id.startsWith("local-") || it.id.startsWith("reply-") }
        }
        val mine = final.filterIsInstance<ChatItem.Message>().last { it.role == Role.User }
        assertEquals("我连接不上啊", mine.text, "系统提示不显示")
        assertEquals(listOf(img.path), mine.images.map { it.source })
    }

    @Test
    fun imagesFromBeforeTheMemoryAreFoundByTime() {
        val s = server()
        val sentAt = 1_792_300_000L // 秒
        // 旧版本发的：服务器只记了 [screenshot]，本机没有“哪条消息配哪张图”的记录，但缓存里有这张图
        s.messages["s1"] = mutableListOf("""{"id":900,"role":"user","content":"旧消息里的图\n[screenshot]","timestamp":$sentAt}""")
        val old = cachedImage(0x654321)
        java.io.File(old.path).setLastModified(sentAt * 1000 - 2 * 60_000)
        val far = cachedImage(0x111111)
        java.io.File(far.path).setLastModified(sentAt * 1000 - 5 * 3600_000) // 太早，不该配上
        val repo = connected(s)
        val items = repo.chatItems("s1").await("记录") { list -> list.any { it is ChatItem.Message && it.text == "旧消息里的图" } }
        val mine = items.filterIsInstance<ChatItem.Message>().first { it.text == "旧消息里的图" }
        assertEquals(listOf(old.path), mine.images.map { it.source })
    }
}

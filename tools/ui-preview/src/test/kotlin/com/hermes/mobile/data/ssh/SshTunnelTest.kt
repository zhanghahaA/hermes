package com.hermes.mobile.data.ssh

import com.hermes.mobile.data.model.ChatItem
import com.hermes.mobile.data.model.ConnectionProfile
import com.hermes.mobile.data.model.ConnectionState
import com.hermes.mobile.data.model.ConnectionTestStep
import com.hermes.mobile.data.model.ConnectionType
import com.hermes.mobile.data.model.Role
import com.hermes.mobile.data.model.SshAuthMethod
import com.hermes.mobile.data.model.TestStepStatus
import com.hermes.mobile.data.remote.FakeHermesServer
import com.hermes.mobile.data.remote.RemoteHermesRepository
import com.hermes.mobile.platform.FakeSecretBox
import com.hermes.mobile.platform.MemoryLocalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 阶段 3：SSH 隧道。用真的 SSH 服务器把请求转发到模拟的 Hermes。 */
class SshTunnelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cleanup = mutableListOf<() -> Unit>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        cleanup.asReversed().forEach { runCatching(it) }
    }

    private val keys = SshKeys.generate()

    private fun hermes() = FakeHermesServer(FakeHermesServer.Version.Modern).start().also { s -> cleanup += { s.shutdown() } }
    private fun sshd(server: TestSshServer = TestSshServer(authorizedKey = keys.publicKey)) = server.start().also { s -> cleanup += { s.stop() } }

    private fun profile(ssh: TestSshServer, hermes: FakeHermesServer, auth: SshAuthMethod = SshAuthMethod.Key, pin: String? = null) = ConnectionProfile(
        id = "ssh1", name = "家里 NAS", type = ConnectionType.Ssh,
        host = "127.0.0.1", port = hermes.server.port,
        sshUser = ssh.user, sshPort = ssh.port, sshAuth = auth, sshHostKey = pin,
    )

    private fun repo(store: MemoryLocalStore = MemoryLocalStore()) = RemoteHermesRepository(scope, store, FakeSecretBox(), backoff = { 50L })

    private fun steps(flow: Flow<List<ConnectionTestStep>>) = runBlocking { withTimeout(20_000) { flow.toList().last() } }

    private fun RemoteHermesRepository.awaitState(): ConnectionState = runBlocking {
        withTimeout(20_000) { connectionState.first { it is ConnectionState.Connected || it is ConnectionState.Failed } }
    }

    @Test
    fun generatedKeyIsValidOpenSsh() {
        assertTrue(keys.privateKey.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        assertTrue(keys.publicKey.startsWith("ssh-ed25519 AAAA"))
        assertEquals(keys.publicKey.substringBeforeLast(' '), SshKeys.publicKeyLine(keys.privateKey).substringBeforeLast(' '), "能从私钥还原出同一个公钥")
    }

    @Test
    fun keyLoginThroughTunnelWorksEndToEnd() {
        val hermes = hermes()
        val ssh = sshd()
        val repo = repo()
        val profile = profile(ssh, hermes)

        val result = steps(repo.testConnection(profile, hermes.key, keys.privateKey))
        assertEquals(listOf("SSH 连接", "SSH 登录", "网络可达", "识别 Hermes", "API Key 鉴权", "能力探测"), result.map { it.title })
        assertTrue(result.all { it.status == TestStepStatus.Passed }, result.toString())
        assertTrue(result[0].detail!!.contains("首次连接") && result[0].detail!!.contains("SHA256:"), result[0].detail)

        repo.saveConnection(profile, hermes.key, keys.privateKey)
        assertTrue(repo.awaitState() is ConnectionState.Connected, repo.connectionState.value.toString())
        runBlocking { withTimeout(5_000) { repo.sessions.first { it.isNotEmpty() } } }
        val pinned = repo.connections.value.single().sshHostKey
        assertNotNull(pinned, "第一次连上就记住主机指纹")
        assertTrue(pinned.startsWith("SHA256:"))
        assertEquals(keys.publicKey.substringBeforeLast(' '), repo.sshPublicKey("ssh1")?.substringBeforeLast(' '), "可以再次查看公钥")

        // 隧道里聊天
        repo.sendMessage("s1", "隧道里你好", emptyList())
        runBlocking {
            withTimeout(10_000) {
                repo.chatItems("s1").first { list -> list.any { it is ChatItem.Message && it.role == Role.Assistant && it.text == "最终回复：隧道里你好" } }
            }
        }
    }

    @Test
    fun secretsAreStoredEncrypted() {
        val hermes = hermes()
        val ssh = sshd()
        val store = MemoryLocalStore()
        repo(store).saveConnection(profile(ssh, hermes), hermes.key, keys.privateKey)
        val saved = assertNotNull(store.state)
        assertTrue(saved.keys.values.none { "OPENSSH PRIVATE KEY" in it }, "私钥必须加密保存")
        assertTrue(saved.keys.containsKey("ssh:ssh1"))
    }

    @Test
    fun passwordLogin() {
        val hermes = hermes()
        val ssh = sshd()
        val repo = repo()
        val ok = steps(repo.testConnection(profile(ssh, hermes, SshAuthMethod.Password), hermes.key, ssh.password))
        assertTrue(ok.all { it.status == TestStepStatus.Passed }, ok.toString())

        val bad = steps(repo.testConnection(profile(ssh, hermes, SshAuthMethod.Password), hermes.key, "wrong"))
        assertEquals(TestStepStatus.Passed, bad[0].status)
        assertEquals(TestStepStatus.Failed, bad[1].status)
        assertTrue(bad[1].detail!!.contains("密码不对"), bad[1].detail)
    }

    @Test
    fun unknownKeyIsRejectedWithHint() {
        val hermes = hermes()
        val ssh = sshd()
        val other = SshKeys.generate()
        val result = steps(repo().testConnection(profile(ssh, hermes), hermes.key, other.privateKey))
        assertEquals(TestStepStatus.Failed, result[1].status)
        assertTrue(result[1].detail!!.contains("authorized_keys"), result[1].detail)
    }

    @Test
    fun changedHostKeyIsRefused() {
        val hermes = hermes()
        val ssh = sshd()
        val fakePin = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val result = steps(repo().testConnection(profile(ssh, hermes, pin = fakePin), hermes.key, keys.privateKey))
        assertEquals(TestStepStatus.Failed, result[0].status)
        assertTrue(result[0].detail!!.contains("指纹变了"), result[0].detail)

        val repo = repo()
        repo.saveConnection(profile(ssh, hermes, pin = fakePin), hermes.key, keys.privateKey)
        val state = repo.awaitState()
        assertTrue(state is ConnectionState.Failed && state.reason.contains("指纹变了"), state.toString())
        assertEquals(0, ssh.logins.get(), "指纹不对时绝不能把密钥/密码发过去")
    }

    @Test
    fun wrongHermesPortIsExplained() {
        val hermes = hermes()
        val ssh = sshd()
        val profile = profile(ssh, hermes).copy(port = 1) // 远端没有服务
        val result = steps(repo().testConnection(profile, hermes.key, keys.privateKey))
        assertEquals(TestStepStatus.Passed, result[1].status)
        assertEquals(TestStepStatus.Failed, result[2].status)
        assertTrue(result[2].detail!!.contains("SSH 已登录"), result[2].detail)
    }

    @Test
    fun tunnelReconnectsAfterSshServerRestart() {
        val hermes = hermes()
        val ssh = sshd()
        val repo = repo()
        repo.saveConnection(profile(ssh, hermes), hermes.key, keys.privateKey)
        assertTrue(repo.awaitState() is ConnectionState.Connected)
        runBlocking { withTimeout(5_000) { repo.sessions.first { it.isNotEmpty() } } }

        // SSH 服务器重启（同一台机器，指纹不变）
        val port = ssh.port
        ssh.stop()
        val restarted = sshd(TestSshServer(authorizedKey = keys.publicKey, hostKeyFile = ssh.hostKeyFile, port = port))
        repo.onNetworkAvailable()

        repo.sendMessage("s1", "重启之后", emptyList())
        runBlocking {
            withTimeout(15_000) {
                repo.chatItems("s1").first { list -> list.any { it is ChatItem.Message && it.text == "最终回复：重启之后" } }
            }
        }
        assertTrue(restarted.logins.get() >= 1, "隧道自动重新登录")
        assertTrue(repo.connectionState.value is ConnectionState.Connected)
    }

    @Test
    fun pullRefreshReopensSshWithoutSwitchingConnection() = runBlocking {
        val hermes = hermes()
        val ssh = sshd()
        val repo = repo()
        repo.saveConnection(profile(ssh, hermes), hermes.key, keys.privateKey)
        assertTrue(repo.awaitState() is ConnectionState.Connected)
        withTimeout(5000) { repo.sessions.first { it.isNotEmpty() } }
        val before = ssh.logins.get()
        withTimeout(10_000) { repo.refreshSessions() }
        assertTrue(ssh.logins.get() > before, "刷新主动重建 SSH，而不是继续使用旧连接")
        assertTrue(repo.connectionState.value is ConnectionState.Connected)
        assertTrue(repo.sessions.value.isNotEmpty())
        assertEquals("ssh1", repo.activeConnectionId.value)
    }

    @Test
    fun interactiveChoicesTravelThroughTheSshTunnel() = runBlocking {
        val hermes = hermes()
        val ssh = sshd()
        val repo = repo()
        repo.saveConnection(profile(ssh, hermes), hermes.key, keys.privateKey)
        assertTrue(repo.awaitState() is ConnectionState.Connected)
        repo.sendMessage("s1", "给我选项", emptyList())
        val items = withTimeout(10_000) { repo.chatItems("s1").first { list -> list.any { it is ChatItem.Clarify } } }
        val card = items.filterIsInstance<ChatItem.Clarify>().single()
        assertEquals(listOf("蓝色", "绿色"), card.options)
        repo.answerClarify("s1", card.id, "蓝色")
        withTimeout(10_000) { repo.chatItems("s1").first { list -> list.any { it is ChatItem.Message && it.text == "最终回复：给我选项" } } }
        assertTrue(hermes.requests.any { it.path?.endsWith("/clarify") == true })
    }

    @Test
    fun directChatWithSshReceivesSharedCardsAfterDraftBecomesRealSession() = runBlocking {
        val hermes = hermes()
        val requests = java.util.concurrent.CopyOnWriteArrayList<kotlinx.serialization.json.JsonObject>()
        val ssh = sshd(TestSshServer(authorizedKey = keys.publicKey).also {
            it.sshd.commandFactory = org.apache.sshd.server.command.CommandFactory { _, command -> InteractiveGatewayCommand(command, requests) }
        })
        val repo = repo()
        repo.saveConnection(profile(ssh, hermes).copy(type = ConnectionType.Direct), hermes.key, keys.privateKey)
        assertTrue(repo.awaitState() is ConnectionState.Connected)
        assertNotNull(repo.roomApi(), "直连附加 SSH 也应提供 Gateway 通道")
        val draft = assertNotNull(repo.createSession())
        val received = kotlinx.coroutines.CompletableDeferred<ChatItem.Clarify>()
        val collecting = launch {
            repo.chatItems(draft).collect { items ->
                items.filterIsInstance<ChatItem.Clarify>().firstOrNull()?.let { received.complete(it) }
            }
        }
        try {
            kotlinx.coroutines.delay(400)
            repo.sendMessage(draft, "普通测试消息", emptyList())
            val card = try { withTimeout(10_000) { received.await() } }
            catch (e: Exception) { throw AssertionError("未收到卡片，RPC=" + requests.map { it["method"] } + "，连接=" + repo.connectionState.value, e) }
            assertEquals(listOf("甲", "乙"), card.options)
            repo.answerClarify(draft, card.id, "甲")
            withTimeout(5000) {
                while (requests.none { it["method"].toString() == "\"clarify.respond\"" }) kotlinx.coroutines.delay(20)
            }
            assertTrue(requests.any { it["method"].toString() == "\"session.resume\"" })
        } finally { collecting.cancel(); collecting.join() }
    }

    @Test
    fun otherFilesAreUploadedOverSftp() {
        val hermes = hermes()
        val ssh = sshd()
        val repo = repo()
        repo.saveConnection(profile(ssh, hermes), hermes.key, keys.privateKey)
        assertTrue(repo.awaitState() is ConnectionState.Connected)
        runBlocking { withTimeout(5_000) { repo.sessions.first { it.isNotEmpty() } } }
        assertTrue(repo.canSendAnyFile(), "SSH 连接可以发任意文件")

        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        scope.launch { repo.errors.collect { errors += it } }
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        val local = java.io.File.createTempFile("upload", ".zip").apply { writeBytes(bytes) }
        repo.sendMessage("s1", "解压看看", listOf(com.hermes.mobile.data.model.Attachment.BinaryFile("b1", "data pack.zip", "application/zip", local.path, bytes.size.toLong())))
        val items = runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(15_000) {
                repo.chatItems("s1").first { list ->
                    list.any { it is ChatItem.Message && it.role == Role.Assistant && it.text.startsWith("最终回复：解压看看") } &&
                        list.filterIsInstance<ChatItem.Message>().none { it.id.startsWith("local-") || it.id.startsWith("reply-") }
                }
            }
        } ?: throw AssertionError("没等到回复。错误：$errors；现在：${runBlocking { repo.chatItems("s1").first() }.takeLast(3)}")
        // 测试用的 JVM 默认编码不是 UTF-8，文件名用 ASCII（中文名走同一条路径，真实 OpenSSH 的集成测试里有）
        val uploaded = ssh.home.resolve("hermes-uploads/data pack.zip")
        assertTrue(java.nio.file.Files.isRegularFile(uploaded), "保留原文件名：" + java.nio.file.Files.list(ssh.home.resolve("hermes-uploads")).use { it.toList() })
        assertTrue(java.nio.file.Files.readAllBytes(uploaded).contentEquals(bytes), "上传的内容完整")
        val input = hermes.requests.first { it.path == "/v1/runs" }.body.clone().readUtf8()
        assertTrue(input.contains("📎 附件：data pack.zip（已保存到 /hermes-uploads/data pack.zip，"), input)
        val mine = items.filterIsInstance<ChatItem.Message>().last { it.role == Role.User }
        assertEquals("解压看看", mine.text)
        assertEquals("data pack.zip", mine.files.single().name, "气泡里显示原文件名")
        assertEquals("已上传：/hermes-uploads/data pack.zip", mine.files.single().note)
    }

    /** 文本、YAML 也原样传文件（不再把内容写进消息）；重名加序号；连接里设的位置生效。 */
    @Test
    fun textFilesAreUploadedAsFilesWithOriginalNames() {
        val hermes = hermes()
        val ssh = sshd()
        val repo = repo()
        repo.saveConnection(profile(ssh, hermes).copy(uploadDir = "~/inbox/hermes"), hermes.key, keys.privateKey)
        assertTrue(repo.awaitState() is ConnectionState.Connected)
        runBlocking { withTimeout(5_000) { repo.sessions.first { it.isNotEmpty() } } }

        val yaml = "server:\n  port: 8080\n  secret_line: DO-NOT-INLINE\n"
        val local = java.io.File.createTempFile("cfg", ".yaml").apply { writeText(yaml) }
        fun send(text: String) {
            repo.sendMessage("s1", text, listOf(com.hermes.mobile.data.model.Attachment.BinaryFile("y-$text", "config.yaml", "application/x-yaml", local.path, local.length())))
            runBlocking {
                withTimeout(15_000) {
                    repo.chatItems("s1").first { list ->
                        list.any { it is ChatItem.Message && it.role == Role.Assistant && it.text.startsWith("最终回复：$text") } &&
                            list.filterIsInstance<ChatItem.Message>().none { it.id.startsWith("local-") || it.id.startsWith("reply-") }
                    }
                }
            }
        }
        send("看看配置")
        send("再看一次")
        val dir = ssh.home.resolve("inbox/hermes")
        assertEquals(yaml, java.nio.file.Files.readString(dir.resolve("config.yaml")))
        assertEquals(yaml, java.nio.file.Files.readString(dir.resolve("config (1).yaml")), "重名的加序号，不覆盖")
        val inputs = hermes.requests.filter { it.path == "/v1/runs" }.map { it.body.clone().readUtf8() }
        assertTrue(inputs.none { "DO-NOT-INLINE" in it }, "文件内容不写进消息：$inputs")
        assertTrue(inputs[0].contains("📎 附件：config.yaml（已保存到 /inbox/hermes/config.yaml，"), inputs[0])
        assertTrue(inputs[1].contains("/inbox/hermes/config (1).yaml"), inputs[1])
    }

    /** 发图片时原文件也传一份到服务器：模型拿到真实路径，可以拿去上传、处理（有的 Hermes 版本只给模型文字描述）。 */
    @Test
    fun photosAreAlsoSavedOnTheServer() {
        val hermes = hermes()
        val ssh = sshd()
        val repo = repo()
        repo.saveConnection(profile(ssh, hermes), hermes.key, keys.privateKey)
        assertTrue(repo.awaitState() is ConnectionState.Connected)
        runBlocking { withTimeout(5_000) { repo.sessions.first { it.isNotEmpty() } } }

        val bytes = ByteArray(40_000) { (it % 233).toByte() }
        val photo = java.io.File.createTempFile("photo", ".jpg").apply { writeBytes(bytes) }
        repo.sendMessage("s1", "用这张图填表", listOf(com.hermes.mobile.data.model.Attachment.Image("p1", photo.path, 1.3f)))
        val items = runBlocking {
            withTimeout(15_000) {
                repo.chatItems("s1").first { list ->
                    list.any { it is ChatItem.Message && it.role == Role.Assistant && it.text.startsWith("收到 1 张图片") } &&
                        list.filterIsInstance<ChatItem.Message>().none { it.id.startsWith("local-") || it.id.startsWith("reply-") }
                }
            }
        }
        val uploaded = ssh.home.resolve("hermes-uploads/image.jpg")
        assertTrue(java.nio.file.Files.readAllBytes(uploaded).contentEquals(bytes), "原文件完整")
        val input = hermes.requests.first { it.path == "/v1/runs" }.body.clone().readUtf8()
        assertTrue(input.contains("📎 图片文件：/hermes-uploads/") && input.contains("image_url"), "模型既能看图，也知道文件在哪：$input")
        val mine = items.filterIsInstance<ChatItem.Message>().last { it.role == Role.User }
        assertEquals("用这张图填表", mine.text, "气泡里不显示路径那一行")
        assertEquals(1, mine.images.size)
        assertTrue(mine.files.isEmpty(), "图片不变成文件卡片")
    }

    @Test
    fun generatedImagesAreDownloadedOverSftp() {
        val hermes = hermes()
        val ssh = sshd()
        java.nio.file.Files.createDirectories(ssh.home.resolve("gen"))
        val png = ByteArray(5000) { (it % 200).toByte() }
        java.nio.file.Files.write(ssh.home.resolve("gen/cat.png"), png)
        val repo = RemoteHermesRepository(scope, MemoryLocalStore(), FakeSecretBox(), mediaDir = kotlin.io.path.createTempDirectory("m").toFile(), backoff = { 50L })
        repo.saveConnection(profile(ssh, hermes), hermes.key, keys.privateKey)
        assertTrue(repo.awaitState() is ConnectionState.Connected)
        val local = runBlocking { repo.fetchRemoteMedia("/gen/cat.png") }
        assertNotNull(local, "经 SFTP 下载到本机")
        assertTrue(java.io.File(local).readBytes().contentEquals(png))
        assertEquals(local, runBlocking { repo.fetchRemoteMedia("/gen/cat.png") }, "第二次直接用缓存")
    }

    @Test
    fun localTermuxCanUseSshForFiles() {
        val hermes = hermes()
        val ssh = sshd()
        // 本机 Termux：聊天直接连 127.0.0.1，SSH 只用来传文件
        val profile = ConnectionProfile(
            id = "local1", name = "本机", type = ConnectionType.Local, host = "127.0.0.1", port = hermes.server.port,
            sshUser = ssh.user, sshPort = ssh.port, sshAuth = SshAuthMethod.Key,
        )
        val repo = repo()
        val steps = steps(repo.testConnection(profile, hermes.key, keys.privateKey))
        assertEquals("文件互通（SSH）", steps.last().title)
        assertTrue(steps.all { it.status == TestStepStatus.Passed }, steps.toString())
        repo.saveConnection(profile, hermes.key, keys.privateKey)
        assertTrue(repo.awaitState() is ConnectionState.Connected)
        assertTrue(repo.canSendAnyFile())
        assertNotNull(repo.connections.value.single().sshHostKey, "文件互通的 SSH 也记住指纹")
        assertTrue(hermes.requests.none { it.path == "/v1/runs" })
    }

    @Test
    fun refusedForwardingIsExplained() {
        val hermes = hermes()
        val ssh = TestSshServer(authorizedKey = keys.publicKey).apply {
            sshd.forwardingFilter = org.apache.sshd.server.forward.RejectAllForwardingFilter.INSTANCE
        }
        sshd(ssh)
        val result = steps(repo().testConnection(profile(ssh, hermes), hermes.key, keys.privateKey))
        assertEquals(TestStepStatus.Passed, result[1].status)
        assertEquals(TestStepStatus.Failed, result[2].status)
        assertTrue(result[2].detail!!.contains("不允许端口转发"), result[2].detail)
    }

    @Test
    fun generatedFilesOpenOverSftp() {
        val hermes = hermes()
        val ssh = sshd()
        java.nio.file.Files.createDirectories(ssh.home.resolve("scratch"))
        java.nio.file.Files.writeString(ssh.home.resolve("scratch/one-character.md"), "糖")
        val repo = RemoteHermesRepository(scope, MemoryLocalStore(), FakeSecretBox(), mediaDir = kotlin.io.path.createTempDirectory("m").toFile(), backoff = { 50L })
        repo.saveConnection(profile(ssh, hermes), hermes.key, keys.privateKey)
        assertTrue(repo.awaitState() is ConnectionState.Connected)
        val file = com.hermes.mobile.data.model.FileRef("one-character.md", remotePath = "/scratch/one-character.md")
        val local = assertNotNull(runBlocking { repo.fileForOpening(file) })
        assertEquals("糖", java.io.File(local).readText())
    }
}

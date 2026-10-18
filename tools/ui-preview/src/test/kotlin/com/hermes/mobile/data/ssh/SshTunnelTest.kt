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
        repo.sendMessage("s1", "解压看看", listOf(com.hermes.mobile.data.model.Attachment.BinaryFile("b1", "数据 包.zip", "application/zip", local.path, bytes.size.toLong())))
        val items = runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(15_000) {
                repo.chatItems("s1").first { list ->
                    list.any { it is ChatItem.Message && it.role == Role.Assistant && it.text.startsWith("最终回复：解压看看") } &&
                        list.filterIsInstance<ChatItem.Message>().none { it.id.startsWith("local-") || it.id.startsWith("reply-") }
                }
            }
        } ?: throw AssertionError("没等到回复。错误：$errors；现在：${runBlocking { repo.chatItems("s1").first() }.takeLast(3)}")
        val uploaded = java.nio.file.Files.list(ssh.home.resolve("hermes-uploads")).use { it.toList() }.single()
        assertTrue(uploaded.fileName.toString().endsWith(".zip"), uploaded.toString())
        assertTrue(java.nio.file.Files.readAllBytes(uploaded).contentEquals(bytes), "上传的内容完整")
        val input = hermes.requests.first { it.path == "/v1/runs" }.body.clone().readUtf8()
        assertTrue(input.contains("📎 附件：数据 包.zip（已保存到 /hermes-uploads/"), input)
        val mine = items.filterIsInstance<ChatItem.Message>().last { it.role == Role.User }
        assertEquals("解压看看", mine.text)
        assertEquals("数据 包.zip", mine.files.single().name, "气泡里显示原文件名")
        assertTrue(mine.files.single().note!!.startsWith("已上传：/hermes-uploads/"), mine.files.single().note)
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

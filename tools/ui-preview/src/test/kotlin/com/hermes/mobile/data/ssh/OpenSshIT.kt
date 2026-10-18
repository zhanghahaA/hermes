package com.hermes.mobile.data.ssh

import com.hermes.mobile.data.model.ChatItem
import com.hermes.mobile.data.model.ConnectionProfile
import com.hermes.mobile.data.model.ConnectionState
import com.hermes.mobile.data.model.ConnectionType
import com.hermes.mobile.data.model.Role
import com.hermes.mobile.data.model.SshAuthMethod
import com.hermes.mobile.data.model.TestStepStatus
import com.hermes.mobile.data.remote.RemoteHermesRepository
import com.hermes.mobile.platform.FakeSecretBox
import com.hermes.mobile.platform.MemoryLocalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 和真正的 OpenSSH 服务器互通（密钥由 ssh-keygen 生成），再经隧道连到真实的 Hermes。只有设置了环境变量才跑：
 *   HERMES_IT_SSH_DIR=<放 user_ed25519 等私钥和 host_ed25519.pub 的目录> HERMES_IT_SSH_PORT=2222 HERMES_IT_SSH_USER=root
 *   HERMES_IT_KEY=<API_SERVER_KEY>
 */
class OpenSshIT {
    private val dir = System.getenv("HERMES_IT_SSH_DIR")?.let(::File)
    private val sshPort = System.getenv("HERMES_IT_SSH_PORT")?.toIntOrNull() ?: 22
    private val user = System.getenv("HERMES_IT_SSH_USER") ?: "root"
    private val key = System.getenv("HERMES_IT_KEY")

    @Test
    fun openSshKeysAndTunnelToRealHermes() {
        if (dir == null || key == null) {
            println("跳过：没有设置 HERMES_IT_SSH_DIR / HERMES_IT_KEY")
            return
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // ssh-keygen -lf host_ed25519.pub 的输出：256 SHA256:xxx comment (ED25519)
            val expectedFingerprint = ProcessBuilder("ssh-keygen", "-lf", File(dir, "host_ed25519.pub").path).start()
                .inputStream.bufferedReader().readText().split(" ")[1]

            for (type in listOf("ed25519", "rsa", "ecdsa")) {
                val privateKey = File(dir, "user_$type").readText()
                val profile = ConnectionProfile(
                    id = "os-$type", name = "OpenSSH $type", type = ConnectionType.Ssh, host = "127.0.0.1", port = 8642,
                    sshUser = user, sshPort = sshPort, sshAuth = SshAuthMethod.Key,
                )
                val repo = RemoteHermesRepository(scope, MemoryLocalStore(), FakeSecretBox())
                val steps = runBlocking { withTimeout(30_000) { repo.testConnection(profile, key, privateKey).toList().last() } }
                println("$type 密钥：" + steps.joinToString { "${it.title}=${it.status}" })
                assertTrue(steps.all { it.status == TestStepStatus.Passed }, steps.toString())
                assertTrue(steps[0].detail!!.contains(expectedFingerprint), "指纹和 ssh-keygen 显示的一致：${steps[0].detail}")
            }

            // 用 Ed25519 密钥真正连上并聊一句
            val repo = RemoteHermesRepository(scope, MemoryLocalStore(), FakeSecretBox())
            val profile = ConnectionProfile(
                id = "os", name = "OpenSSH", type = ConnectionType.Ssh, host = "127.0.0.1", port = 8642,
                sshUser = user, sshPort = sshPort, sshAuth = SshAuthMethod.Key,
            )
            repo.saveConnection(profile, key, File(dir, "user_ed25519").readText())
            val state = runBlocking { withTimeout(30_000) { repo.connectionState.first { it is ConnectionState.Connected || it is ConnectionState.Failed } } }
            assertTrue(state is ConnectionState.Connected, state.toString())
            assertEquals(expectedFingerprint, repo.connections.value.single().sshHostKey)
            val chat = repo.createSession()!!
            repo.sendMessage(chat, "隧道你好", emptyList())
            val items = runBlocking {
                withTimeout(60_000) { repo.chatItems(chat).first { list -> list.any { it is ChatItem.Message && it.role == Role.Assistant } } }
            }
            println("经 OpenSSH 隧道的回复：${items.filterIsInstance<ChatItem.Message>().filter { it.role == Role.Assistant }.map { it.text }}")
            assertTrue(items.any { it is ChatItem.Message && it.text == "收到：隧道你好" })
            runBlocking { repo.session(chat).first() }?.id?.let(repo::deleteSession)
            Thread.sleep(1000)

            // App 生成的密钥：OpenSSH 能读，加进 authorized_keys 后能登录
            val generated = SshKeys.generate()
            val tmp = File.createTempFile("gen", "").apply { writeText(generated.privateKey); setReadable(false, false); setReadable(true, true) }
            val derived = ProcessBuilder("ssh-keygen", "-y", "-f", tmp.path).start().inputStream.bufferedReader().readText().trim()
            tmp.delete()
            fun keyPart(line: String) = line.split(' ').take(2).joinToString(" ")
            assertEquals(keyPart(generated.publicKey), keyPart(derived), "ssh-keygen 能读 App 生成的私钥")
            val authorized = File(dir, "authorized_keys")
            val original = authorized.readText()
            try {
                authorized.appendText(generated.publicKey + "\n")
                val genSteps = runBlocking { withTimeout(30_000) { repo.testConnection(profile.copy(id = "os-gen"), key, generated.privateKey).toList().last() } }
                println("App 生成的密钥：" + genSteps.joinToString { "${it.title}=${it.status}" })
                assertTrue(genSteps.all { it.status == TestStepStatus.Passed }, genSteps.toString())
            } finally {
                authorized.writeText(original)
            }

            // 带口令的私钥：给出能看懂的提示
            val protectedSteps = runBlocking {
                withTimeout(30_000) {
                    repo.testConnection(profile.copy(id = "os-p"), key, File(dir, "user_protected").readText()).toList().last()
                }
            }
            println("带口令的私钥：${protectedSteps[1].detail}")
            assertEquals(TestStepStatus.Failed, protectedSteps[1].status)
            assertTrue(protectedSteps[1].detail!!.contains("口令"), protectedSteps[1].detail)
        } finally {
            scope.cancel()
        }
    }
}

package com.hermes.mobile.data.setup

import com.hermes.mobile.data.model.*
import com.hermes.mobile.data.remote.RemoteHermesRepository
import com.hermes.mobile.data.ssh.*
import com.hermes.mobile.platform.FakeSecretBox
import com.hermes.mobile.platform.MemoryLocalStore
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.apache.sshd.server.command.AbstractCommandSupport
import org.apache.sshd.server.command.CommandFactory
import java.util.Base64
import kotlin.test.*

/** 真实本机 SSH 握手与认证，远端配置回包使用假数据，不连接用户机器。 */
class ConnectionSetupSshTest {
    private fun runCase(wrongNonce: Boolean) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pair = SshKeys.generate()
        val ssh = TestSshServer(authorizedKey = pair.publicKey)
        val key = ConnectionSetup.generateApiKey()
        try {
            ssh.start()
            val profile = ConnectionProfile("setup-test", "test", ConnectionType.Ssh, "127.0.0.1", sshUser = ssh.user, sshPort = ssh.port)
            val parameters = ConnectionSetup.Parameters(if (wrongNonce) "wrong-nonce" else "test-nonce", "Ssh", profile.host,
                profile.port, "", ssh.user, ssh.port, key, "", 8765)
            val receipt = ConnectionSetup.ReceiptPrefix + Base64.getUrlEncoder().encodeToString(
                Json.encodeToString(ConnectionSetup.Parameters.serializer(), parameters).toByteArray())
            ssh.sshd.commandFactory = CommandFactory { _, command ->
                object : AbstractCommandSupport("配置回包", null) {
                    override fun run() {
                        if (command.contains(".mobile-setup-results/") && !command.contains("test-nonce") && command.contains("head -c 32769")) {
                            outputStream.write((receipt + "\n").toByteArray())
                            outputStream.flush()
                            onExit(0)
                        } else onExit(2)
                    }
                }
            }
            val repo = RemoteHermesRepository(scope, MemoryLocalStore(), FakeSecretBox())
            if (wrongNonce) {
                val error = assertFailsWith<IllegalStateException> { repo.readConnectionSetup(profile, pair.privateKey, "test-nonce") }
                assertFalse(error.message.orEmpty().contains(key))
            } else {
                val result = assertNotNull(repo.readConnectionSetup(profile, pair.privateKey, "test-nonce"))
                assertEquals("", result.apiKey, "SSH 走 gateway：旧助手写进回执的密钥也不再取用")
                assertEquals(8765, result.port)
                assertTrue(assertNotNull(result.hostKey).startsWith("SHA256:"))
                assertTrue(ssh.logins.get() >= 1)
                assertTrue(repo.connections.value.isEmpty(), "取回配置不能提前保存或切换连接")
            }
        } finally {
            scope.cancel()
            ssh.stop()
            ssh.home.toFile().deleteRecursively()
            ssh.hostKeyFile.toFile().delete()
        }
    }

    @Test fun authenticatedSshPinsTheObservedHost() = runCase(false)
    @Test fun unrelatedReceiptsAreRejectedWithoutExposingTheKey() = runCase(true)
}

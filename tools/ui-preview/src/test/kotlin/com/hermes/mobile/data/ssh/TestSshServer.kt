package com.hermes.mobile.data.ssh

import org.apache.sshd.common.config.keys.AuthorizedKeyEntry
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import java.nio.file.Files
import java.nio.file.Path
import java.security.PublicKey

/**
 * 测试用的真 SSH 服务器（Apache MINA SSHD），允许端口转发。
 * 主机密钥存在 [hostKeyFile] 里：用同一个文件重启，指纹不变；换个文件就是“换了一台服务器”。
 */
class TestSshServer(
    val user: String = "hermes",
    val password: String = "correct-horse",
    authorizedKey: String? = null,
    val hostKeyFile: Path = Files.createTempFile("hostkey", ".ser").also { Files.delete(it) },
    port: Int = 0,
) {
    private val allowed: PublicKey? = authorizedKey?.let {
        AuthorizedKeyEntry.parseAuthorizedKeyEntry(it).resolvePublicKey(null, PublicKeyEntryResolver.IGNORING)
    }
    val logins = java.util.concurrent.atomic.AtomicInteger()

    val sshd: SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        this.port = port
        keyPairProvider = SimpleGeneratorHostKeyProvider(hostKeyFile)
        publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { u, key, _ ->
            (u == user && allowed != null && KeyUtils.compareKeys(allowed, key)).also { if (it) logins.incrementAndGet() }
        }
        passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { u, p, _ ->
            (u == user && p == password).also { if (it) logins.incrementAndGet() }
        }
        forwardingFilter = AcceptAllForwardingFilter.INSTANCE
    }

    val port: Int get() = sshd.port

    fun start(): TestSshServer = apply { sshd.start() }
    fun stop() = sshd.stop(true)
}

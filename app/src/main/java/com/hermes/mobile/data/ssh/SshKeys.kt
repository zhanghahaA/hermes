package com.hermes.mobile.data.ssh

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.keyprovider.KeyFormat
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.keyprovider.PuTTYKeyFile
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import java.io.StringReader
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Base64

/** 一对 SSH 密钥：私钥是 OpenSSH 格式的文本，公钥是一行 `ssh-ed25519 AAAA... 注释`，放进服务器的 authorized_keys。 */
data class SshKeyPair(val privateKey: String, val publicKey: String) {
    override fun toString() = "SshKeyPair(privateKey=[已隐藏], publicKey=$publicKey)"
}

object SshKeys {
    /** 在手机上生成 Ed25519 密钥。私钥不离开手机（加密保存），只需要把公钥加到服务器上。 */
    fun generate(comment: String = "hermes-mobile"): SshKeyPair {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val pair = generator.generateKeyPair()
        val privateBlob = OpenSSHPrivateKeyUtil.encodePrivateKey(pair.private)
        val pem = buildString {
            append("-----BEGIN OPENSSH PRIVATE KEY-----\n")
            Base64.getEncoder().encodeToString(privateBlob).chunked(70).forEach { append(it).append('\n') }
            append("-----END OPENSSH PRIVATE KEY-----\n")
        }
        val publicBlob = OpenSSHPublicKeyUtil.encodePublicKey(pair.public)
        return SshKeyPair(pem, "ssh-ed25519 ${Base64.getEncoder().encodeToString(publicBlob)} $comment")
    }

    /**
     * 读私钥（OpenSSH 新格式、PEM、PKCS#8、PuTTY 都认）。格式不对、需要口令却没给时抛 [IllegalArgumentException]，
     * 消息可以直接给用户看。
     */
    fun load(privateKey: String, passphrase: String? = null): KeyProvider {
        val text = privateKey.trim().replace("\r\n", "\n") + "\n"
        val format = runCatching { KeyProviderUtil.detectKeyFileFormat(text, false) }.getOrDefault(KeyFormat.Unknown)
        val file = when (format) {
            KeyFormat.OpenSSHv1 -> OpenSSHKeyV1KeyFile()
            KeyFormat.OpenSSH -> OpenSSHKeyFile()
            KeyFormat.PKCS8 -> PKCS8KeyFile()
            KeyFormat.PuTTY -> PuTTYKeyFile()
            else -> throw IllegalArgumentException("看不懂这个私钥：请粘贴完整的私钥文件内容（以 -----BEGIN 开头）")
        }
        val finder = passphrase?.takeIf { it.isNotEmpty() }?.let { pass ->
            object : PasswordFinder {
                override fun reqPassword(resource: Resource<*>?): CharArray = pass.toCharArray()
                override fun shouldRetry(resource: Resource<*>?): Boolean = false
            }
        }
        if (finder != null) file.init(StringReader(text), finder) else file.init(StringReader(text))
        try {
            file.public
        } catch (e: Exception) {
            throw IllegalArgumentException(
                if (passphrase.isNullOrEmpty()) "私钥读取失败，可能设置了口令（暂不支持带口令的私钥，请用 ssh-keygen -p 去掉口令，或在 App 里生成新密钥）"
                else "私钥读取失败：${e.message}",
            )
        }
        return file
    }

    /** 由私钥得到公钥那一行（方便用户再次复制到服务器）。 */
    fun publicKeyLine(privateKey: String, comment: String = "hermes-mobile"): String {
        val key = load(privateKey).public
        return "${KeyType.fromKey(key)} ${Base64.getEncoder().encodeToString(blob(key))} $comment"
    }

    /** OpenSSH 风格的指纹：`SHA256:` + Base64（无填充），和 `ssh-keygen -lf` 显示的一样。 */
    fun fingerprint(key: PublicKey): String =
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob(key)))

    private fun blob(key: PublicKey): ByteArray = Buffer.PlainBuffer().putPublicKey(key).compactData
}

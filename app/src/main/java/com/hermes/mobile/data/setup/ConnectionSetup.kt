package com.hermes.mobile.data.setup

import com.hermes.mobile.data.model.ConnectionProfile
import com.hermes.mobile.data.model.ConnectionType
import com.hermes.mobile.data.model.SshAuthMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64

/** 配置命令只在用户点复制时生成；凭据不进入通用状态、诊断或默认字符串。 */
object ConnectionSetup {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    const val ReceiptPrefix = "HERMES-MOBILE-SETUP:"

    @Serializable
    data class Parameters(val nonce: String, val type: String, val host: String, val port: Int,
        val profile: String, val sshUser: String, val sshPort: Int, val apiKey: String, val publicKey: String, val effectivePort: Int = 0) {
        override fun toString() = "SetupParameters(contents=[已隐藏])"
    }

    class Result(val apiKey: String, val port: Int, val hostKey: String? = null) {
        override fun toString() = "SetupResult(credentials=[已隐藏], port=$port)"
    }

    fun generateApiKey(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun validate(profile: ConnectionProfile) {
        require(profile.port in 1..65535 && profile.sshPort in 1..65535) { "端口需要在 1–65535 之间" }
        require(profile.host.isNotBlank() && profile.host.none { it.isWhitespace() || it.isISOControl() }) { "请先填写电脑或服务器地址" }
        require(profile.profile.isNullOrBlank() || Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}").matches(profile.profile)) { "Profile 只使用字母、数字、短横线或下划线" }
        if (profile.type == ConnectionType.Ssh) require(!profile.sshUser.isNullOrBlank()) { "请先填写 SSH 用户名" }
    }

    fun command(program: String, profile: ConnectionProfile, apiKey: String, publicKey: String, nonce: String): String {
        validate(profile)
        require(profile.type != ConnectionType.Local || profile.id != com.hermes.mobile.data.local.LocalRuntimeManager.ConnectionId) {
            "此连接由本机运行中心管理，请在那里配置"
        }
        require(apiKey.length in 16..4096 && apiKey.none { it.isISOControl() }) { "连接密钥至少 16 位，也可以留空让 App 自动生成" }
        if (profile.type == ConnectionType.Ssh && profile.sshAuth == SshAuthMethod.Key) require(publicKey.isNotBlank()) { "请先生成或导入 SSH 密钥" }
        val parameters = Parameters(nonce, profile.type.name, profile.host, profile.port,
            profile.profile.orEmpty(), profile.sshUser.orEmpty(), profile.sshPort,
            if (profile.type == ConnectionType.Ssh) "" else apiKey, publicKey)
        val encoded = Base64.getEncoder().encodeToString(json.encodeToString(Parameters.serializer(), parameters).toByteArray(Charsets.UTF_8))
        val source = Base64.getEncoder().encodeToString(program.toByteArray(Charsets.UTF_8))
        val loader = "import base64;exec(compile(base64.b64decode('$source'),'hermes-mobile-setup','exec'))"
        val suffix = profile.profile?.takeIf { it != "default" && it.isNotBlank() }?.let { "/profiles/$it" }.orEmpty()
        return """
            (
            set -eu
            umask 077
            export HERMES_HOME="§{HERMES_HOME:-§HOME/.hermes}"@@SUFFIX@@
            hm_python=
            if command -v python3 >/dev/null 2>&1; then hm_python=python3
            elif command -v python >/dev/null 2>&1; then hm_python=python
            else
              hm_python=§(hermes --print-runtime-command 2>/dev/null | tail -n 1 | sed -n 's/^\["\([^"]*\)".*/\1/p')
            fi
            if [ -z "§hm_python" ] || ! command -v "§hm_python" >/dev/null 2>&1; then
              if command -v pkg >/dev/null 2>&1; then pkg install -y python; hm_python=python3
              else echo '未找到可用的 Python 3；请先完成 Hermes 安装，再重新运行此命令'; exit 2; fi
            fi
            "§hm_python" -c @@LOADER@@ @@PAYLOAD@@
            mkdir -p "§HERMES_HOME/logs"
            if command -v setsid >/dev/null 2>&1; then
              setsid nohup hermes gateway restart > "§HERMES_HOME/logs/mobile-setup.log" 2>&1 < /dev/null &
            else
              nohup hermes gateway restart > "§HERMES_HOME/logs/mobile-setup.log" 2>&1 < /dev/null &
            fi
            echo '已请求重启 Hermes。回到手机点「检查并连接」；如提示已有密钥，请先粘贴上面的连接码。'
            )
        """.trimIndent().replace('§', '$').replace("@@SUFFIX@@", quote(suffix))
            .replace("@@LOADER@@", quote(loader)).replace("@@PAYLOAD@@", quote(encoded))
    }

    fun readResult(text: String, expected: ConnectionProfile, nonce: String): Result {
        try {
            require(text.length <= 32_768)
            val lines = text.lineSequence().map(String::trim).filter { it.startsWith(ReceiptPrefix) }.toList()
            require(lines.size == 1)
            val bytes = Base64.getUrlDecoder().decode(lines.single().removePrefix(ReceiptPrefix))
            require(bytes.size <= 16_384)
            val result = json.decodeFromString(Parameters.serializer(), bytes.toString(Charsets.UTF_8))
            require(result.nonce == nonce && result.type == expected.type.name && result.host == expected.host &&
                result.port == expected.port && result.profile == expected.profile.orEmpty() &&
                result.sshUser == expected.sshUser.orEmpty() && result.sshPort == expected.sshPort)
            require(result.apiKey.length in 16..4096 && result.apiKey.none { it.isISOControl() })
            val actualPort = result.effectivePort.takeIf { it != 0 } ?: result.port
            require(actualPort in 1..65535)
            return Result(result.apiKey, actualPort)
        } catch (_: Exception) { error("连接码不完整或不属于本页设置。请复制当前命令执行后输出的完整一行连接码。") }
    }

    fun publicKeyCommand(publicKey: String): String {
        require(publicKey.isNotBlank() && '\n' !in publicKey && '\r' !in publicKey)
        val key = quote(publicKey)
        return "umask 077; test ! -L ~/.ssh && test ! -L ~/.ssh/authorized_keys && mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && (grep -qxF -- $key ~/.ssh/authorized_keys || printf '%s\\n' $key >> ~/.ssh/authorized_keys)"
    }

    fun resultReadCommand(profile: ConnectionProfile, nonce: String): String {
        validate(profile)
        require(Regex("[A-Za-z0-9-]{1,80}").matches(nonce)) { "配置标识无效，请重新打开引导" }
        // SSH 首次连接只发送摘要，远端必须证明自己收到过用户实际粘贴的随机标识。
        val resultId = java.security.MessageDigest.getInstance("SHA-256").digest(nonce.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val suffix = profile.profile?.takeIf { it != "default" && it.isNotBlank() }?.let { "/profiles/$it" }.orEmpty()
        return "hm_root=\"\${HERMES_HOME:-\$HOME/.hermes}\"" + quote(suffix) + "; " +
            "test ! -L \"\$hm_root/.mobile-setup-results\" && test ! -L \"\$hm_root/.mobile-setup-results/$resultId\" && test -f \"\$hm_root/.mobile-setup-results/$resultId\" && head -c 32769 \"\$hm_root/.mobile-setup-results/$resultId\""
    }

    private fun quote(text: String) = "'" + text.replace("'", "'\"'\"'") + "'"
}

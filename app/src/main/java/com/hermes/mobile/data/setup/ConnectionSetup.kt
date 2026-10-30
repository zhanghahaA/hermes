package com.hermes.mobile.data.setup

import com.hermes.mobile.data.model.ConnectionProfile
import com.hermes.mobile.data.model.ConnectionType
import com.hermes.mobile.data.model.SshAuthMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream

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
        require(profile.host.isNotBlank() && Regex("[A-Za-z0-9.:_-]+").matches(profile.host)) { "请先填写电脑或服务器地址" }
        require(profile.profile.isNullOrBlank() || Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}").matches(profile.profile)) { "Profile 只使用字母、数字、短横线或下划线" }
        if (profile.type == ConnectionType.Ssh) require(profile.sshUser != null && Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*[$]?").matches(profile.sshUser)) { "请先填写 SSH 用户名" }
    }

    fun command(program: String, profile: ConnectionProfile, apiKey: String, publicKey: String, nonce: String, reuseHelper: Boolean = false): String {
        validate(profile)
        require(profile.type != ConnectionType.Local || profile.id != com.hermes.mobile.data.local.LocalRuntimeManager.ConnectionId) {
            "此连接由本机运行中心管理，请在那里配置"
        }
        // SSH 走 Hermes gateway，不用 API Server 的连接密钥
        if (profile.type != ConnectionType.Ssh) require(apiKey.length in 16..4096 && apiKey.none { it.isISOControl() }) { "连接密钥至少 16 位，也可以留空让 App 自动生成" }
        if (profile.type == ConnectionType.Ssh && profile.sshAuth == SshAuthMethod.Key) require(publicKey.isNotBlank()) { "请先生成或导入 SSH 密钥" }
        val parameters = Parameters(nonce, profile.type.name, profile.host, profile.port,
            profile.profile.orEmpty(), profile.sshUser.orEmpty(), profile.sshPort,
            if (profile.type == ConnectionType.Ssh) "" else apiKey, publicKey)
        val encoded = Base64.getEncoder().encodeToString(json.encodeToString(Parameters.serializer(), parameters).toByteArray(Charsets.UTF_8))
        val compressed = ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { it.write(program.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val source = Base64.getEncoder().encodeToString(compressed)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(program.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val loader = """
            import base64,zlib,pathlib,hashlib,os,tempfile
            p=pathlib.Path.home()/'.cache'/'hermes-mobile-pair'/'$digest.py'
            for q in (p,*p.parents):
                if q.is_symlink(): raise SystemExit('助手路径含符号链接，已停止')
            @@SOURCE@@
            if hashlib.sha256(source).hexdigest()!='$digest': raise SystemExit('助手版本不匹配，请重新执行完整配置命令')
            exec(compile(source,str(p),'exec'))
        """.trimIndent().replace("@@SOURCE@@", if (reuseHelper) "source=p.read_bytes() if p.is_file() else b''" else """
            source=zlib.decompress(base64.b64decode('$source'))
            p.parent.mkdir(mode=0o700,parents=True,exist_ok=True)
            fd,tmp=tempfile.mkstemp(dir=p.parent)
            try:
                with os.fdopen(fd,'wb') as f:
                    f.write(source);f.flush();os.fsync(f.fileno())
                os.replace(tmp,p)
            finally:
                if os.path.exists(tmp): os.unlink(tmp)
        """.trimIndent())

        val suffix = profile.profile?.takeIf { it != "default" && it.isNotBlank() }?.let { "/profiles/$it" }.orEmpty()
        return """
            (
            set -eu
            umask 077
            hm_root="§{HERMES_HOME:-§HOME/.hermes}"
            case "§hm_root" in */profiles/*) hm_root="§{hm_root%/profiles/*}";; esac
            export HERMES_HOME="§hm_root"@@SUFFIX@@
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
            // SSH 的回执只用来确认主机（gateway 不用 API Key）；旧版助手写进去的密钥也不再用
            if (expected.type != ConnectionType.Ssh) require(result.apiKey.length in 16..4096 && result.apiKey.none { it.isISOControl() })
            val actualPort = result.effectivePort.takeIf { it != 0 } ?: result.port
            require(actualPort in 1..65535)
            return Result(if (expected.type == ConnectionType.Ssh) "" else result.apiKey, actualPort)
        } catch (_: Exception) { error("连接码不完整或不属于本页设置。请复制当前命令执行后输出的完整一行连接码。") }
    }

    fun publicKeyCommand(publicKey: String): String = authorizationCommand(publicKey, false)

    fun revokeKeyCommand(publicKey: String): String = authorizationCommand(publicKey, true)

    private fun authorizationCommand(publicKey: String, revoke: Boolean): String {
        require(Regex("(?:ssh-ed25519|ssh-rsa|ecdsa-sha2-[A-Za-z0-9-]+) [A-Za-z0-9+/=]+(?: [^\\r\\n]*)?").matches(publicKey))
        val identity = publicKey.split(" ").take(2).joinToString(" ")
        val script = """
            import os,pathlib,shlex,tempfile,fcntl
            p=pathlib.Path.home()/'.ssh'
            for q in (p,*p.parents,p/'authorized_keys',p/'.hermes-mobile.lock'):
                if q.is_symlink(): raise SystemExit('路径含符号链接，已停止')
            os.umask(0o077)
            p.mkdir(mode=0o700,exist_ok=True)
            with open(p/'.hermes-mobile.lock','a') as lock:
                fcntl.flock(lock,fcntl.LOCK_EX)
                f=p/'authorized_keys'
                old=f.read_text() if f.exists() else ''
                identity=shlex.split(@@KEY@@)
                def matches(line):
                    try: fields=shlex.split(line,comments=True)
                    except ValueError: return False
                    i=0 if fields and fields[0].startswith(('ssh-','ecdsa-')) else 1
                    return fields[i:i+2]==identity
                lines=old.splitlines(keepends=True)
                if @@REVOKE@@:
                    new=''.join(line for line in lines if not matches(line))
                else:
                    new=old if any(matches(line) for line in lines) else old+('\n' if old and not old.endswith('\n') else '')+'no-agent-forwarding,no-X11-forwarding,no-pty '+@@LINE@@+'\n'
                if new!=old:
                    fd,tmp=tempfile.mkstemp(dir=p,prefix='.mobile-key-')
                    try:
                        with os.fdopen(fd,'w') as out:
                            out.write(new);out.flush();os.fsync(out.fileno())
                        os.replace(tmp,f)
                    finally:
                        if os.path.exists(tmp): os.unlink(tmp)
            print('本手机公钥授权已撤销，现有 SSH 会话需另外断开。' if @@REVOKE@@ else '本手机公钥授权已检查。')
        """.trimIndent().replace("@@KEY@@", json.encodeToString(String.serializer(), identity))
            .replace("@@LINE@@", json.encodeToString(String.serializer(), publicKey))
            .replace("@@REVOKE@@", if (revoke) "True" else "False")
        return "python3 -c " + quote(script)
    }

    fun resultReadCommand(profile: ConnectionProfile, nonce: String): String {
        validate(profile)
        require(Regex("[A-Za-z0-9-]{1,80}").matches(nonce)) { "配置标识无效，请重新打开引导" }
        // SSH 首次连接只发送摘要，远端必须证明自己收到过用户实际粘贴的随机标识。
        val resultId = java.security.MessageDigest.getInstance("SHA-256").digest(nonce.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val suffix = profile.profile?.takeIf { it != "default" && it.isNotBlank() }?.let { "/profiles/$it" }.orEmpty()
        return "hm_root=\"\${HERMES_HOME:-\$HOME/.hermes}\"; " +
            "case \"\$hm_root\" in */profiles/*) hm_root=\"\${hm_root%/profiles/*}\";; esac; " +
            "hm_root=\"\$hm_root\"" + quote(suffix) + "; " +
            "test ! -L \"\$hm_root/.mobile-setup-results\" && test ! -L \"\$hm_root/.mobile-setup-results/$resultId\" && test -f \"\$hm_root/.mobile-setup-results/$resultId\" && head -c 32769 \"\$hm_root/.mobile-setup-results/$resultId\""
    }

    private fun quote(text: String) = "'" + text.replace("'", "'\"'\"'") + "'"
}

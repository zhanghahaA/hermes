package com.hermes.mobile.data.ssh

import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.PublicKey
import kotlin.concurrent.thread

/** SSH 登录方式。 */
sealed interface SshAuth {
    data class Key(val privateKey: String) : SshAuth
    data class Password(val password: String) : SshAuth
}

/** SSH 这一层出的错，消息已经是给用户看的话。 */
class SshException(message: String, val stage: Stage, val hostKey: String? = null) : IOException(message) {
    enum class Stage { Connect, HostKey, Login, Forward }
}

/**
 * 把手机上的一个本地端口（只监听 127.0.0.1）转发到 SSH 服务器上的 `127.0.0.1:[remotePort]`（Hermes API Server）。
 *
 * 每来一个本地连接就开一条 SSH 通道。SSH 连接断了（换网络、服务器重启）不需要上层处理：
 * 下一个本地连接进来时自动重新登录，所以 HTTP 层的重试就等于隧道的重连。
 *
 * 主机指纹：[pinnedHostKey] 不为空时必须一致，否则拒绝连接（防中间人）；为空时第一次见到的指纹通过 [onHostKey] 交给调用方记住。
 */
class SshTunnel(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val auth: SshAuth,
    private val remotePort: Int,
    private val pinnedHostKey: String?,
    private val onHostKey: (String) -> Unit = {},
    private val connectTimeoutMs: Int = 10_000,
) : Closeable {

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val localPort: Int get() = server.localPort

    @Volatile private var client: SSHClient? = null
    @Volatile private var closed = false
    @Volatile private var acceptedHostKey: String? = pinnedHostKey

    /** 最近一次连上时服务器的指纹。 */
    val hostKey: String? get() = acceptedHostKey

    init {
        thread(name = "ssh-tunnel-accept", isDaemon = true) { acceptLoop() }
    }

    /** 立刻建立 SSH 连接并登录（不等第一个请求），出错时抛 [SshException]，用来尽早给出明确的错误。 */
    fun connect() {
        session()
    }

    /** 丢掉当前 SSH 连接（例如网络切换后它多半已经死了），下一个请求会重新连。 */
    fun reset() {
        val old = synchronized(this) { client.also { client = null } }
        runCatching { old?.disconnect() }
    }

    /**
     * 把手机上的文件传到服务器的 `~/hermes-uploads/` 下（文件名前加时间，避免覆盖），返回服务器上的绝对路径。
     * 连接断了会先重连一次。
     */
    fun upload(localPath: String, name: String): String {
        fun once(): String = session().newSFTPClient().use { sftp ->
            val dir = sftp.canonicalize(".").trimEnd('/') + "/hermes-uploads"
            sftp.mkdirs(dir)
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date())
            val source = net.schmizz.sshj.xfer.FileSystemFile(localPath)
            try {
                "$dir/$stamp-${safeName(name)}".also { sftp.put(source, it) }
            } catch (e: net.schmizz.sshj.sftp.SFTPException) {
                // 有的 SFTP 服务器不接受中文、空格等文件名，换成纯 ASCII 再试一次（保留扩展名）
                "$dir/$stamp-${asciiName(name)}".also { sftp.put(source, it) }
            }
        }
        return try {
            once()
        } catch (e: IOException) {
            reset()
            once()
        }
    }

    /** 把服务器上的文件下载到 [local]（Hermes 生成的图片等）。 */
    fun download(remotePath: String, local: java.io.File) {
        fun once() = session().newSFTPClient().use { sftp ->
            val tmp = java.io.File(local.path + ".part")
            sftp.get(remotePath, net.schmizz.sshj.xfer.FileSystemFile(tmp))
            if (!tmp.renameTo(local)) {
                tmp.copyTo(local, overwrite = true)
                tmp.delete()
            }
        }
        try {
            once()
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            throw e
        } catch (e: IOException) {
            reset()
            once()
        }
    }

    /**
     * 在服务器上执行一条命令（用登录 shell，这样 `hermes` 在 PATH 里），返回退出码和输出（stdout + stderr）。
     * 用来做 API 管不到的事，比如开关工具集。
     */
    fun exec(command: String, timeoutSeconds: Long = 60): Pair<Int, String> {
        fun once(): Pair<Int, String> = session().startSession().use { s ->
            val cmd = s.exec("bash -lc " + shellQuote(command) + " 2>&1")
            val out = cmd.inputStream.readBytes().toString(Charsets.UTF_8)
            cmd.join(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            (cmd.exitStatus ?: -1) to out
        }
        return try {
            once()
        } catch (e: IOException) {
            reset()
            once()
        }
    }

    private fun shellQuote(text: String) = "'" + text.replace("'", "'\\''") + "'"

    /**
     * 试着开一条到 Hermes 端口的转发通道，返回失败原因（给人看的话），成功返回 null。
     * 用来区分“服务器禁止端口转发”和“那个端口上没有服务”。
     */
    fun probeForward(): String? = try {
        session().newDirectConnection("127.0.0.1", remotePort).close()
        null
    } catch (e: net.schmizz.sshj.connection.channel.OpenFailException) {
        when (e.reason) {
            net.schmizz.sshj.connection.channel.OpenFailException.Reason.ADMINISTRATIVELY_PROHIBITED ->
                "SSH 服务器不允许端口转发：在它的 sshd_config 里加上 AllowTcpForwarding local（或 yes）并重载 sshd"
            net.schmizz.sshj.connection.channel.OpenFailException.Reason.CONNECT_FAILED ->
                "SSH 已登录，但服务器上 127.0.0.1:$remotePort 没有服务在监听：确认 Hermes gateway 在运行、API Server 已开启、端口正确"
            else -> "SSH 转发失败：${e.message}"
        }
    } catch (e: IOException) {
        "SSH 转发失败：${e.message}"
    }

    private fun asciiName(name: String): String {
        val ext = name.substringAfterLast('.', "").filter { it.isLetterOrDigit() && it.code < 128 }.take(10)
        val base = name.substringBeforeLast('.').map { if (it.code < 128 && (it.isLetterOrDigit() || it in "-_")) it else '_' }
            .joinToString("").trim('_').ifEmpty { "file" }.take(60)
        return if (ext.isEmpty()) base else "$base.$ext"
    }

    private fun safeName(name: String): String =
        name.replace(Regex("""[/\\\u0000-\u001f]"""), "_").trimStart('.', ' ').ifEmpty { "file" }.take(120)

    override fun close() {
        closed = true
        runCatching { server.close() }
        reset()
    }

    @Synchronized
    private fun session(): SSHClient {
        client?.let { if (it.isConnected && it.isAuthenticated) return it }
        runCatching { client?.disconnect() }
        client = null
        if (closed) throw SshException("隧道已关闭", SshException.Stage.Connect)

        val config = DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE }
        val ssh = SSHClient(config)
        ssh.connectTimeout = connectTimeoutMs
        ssh.timeout = 30_000
        var seenKey: String? = null
        ssh.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String?, port: Int, key: PublicKey): Boolean {
                val fp = SshKeys.fingerprint(key)
                seenKey = fp
                val expected = acceptedHostKey
                return expected == null || expected == fp
            }

            override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()
        })
        try {
            ssh.connect(host, port)
        } catch (e: TransportException) {
            runCatching { ssh.disconnect() }
            if (seenKey != null && acceptedHostKey != null && seenKey != acceptedHostKey) {
                throw SshException(
                    "服务器的主机指纹变了（记录的是 $acceptedHostKey，现在是 $seenKey）。" +
                        "如果你刚重装过服务器或换了机器，在连接设置里重置指纹；否则可能有人在冒充你的服务器。",
                    SshException.Stage.HostKey, seenKey,
                )
            }
            throw SshException("SSH 握手失败：${e.message}", SshException.Stage.Connect)
        } catch (e: UnknownHostException) {
            throw SshException("找不到 SSH 主机 $host，检查拼写或网络", SshException.Stage.Connect)
        } catch (e: ConnectException) {
            throw SshException("连不上 $host:$port，确认 SSH 服务在运行、端口正确", SshException.Stage.Connect)
        } catch (e: SocketTimeoutException) {
            throw SshException("连接 $host:$port 超时，确认地址和网络（例如 Tailscale 是否已连上）", SshException.Stage.Connect)
        } catch (e: IOException) {
            throw SshException("SSH 连接失败：${e.message}", SshException.Stage.Connect)
        }
        val fp = seenKey
        if (acceptedHostKey == null && fp != null) {
            acceptedHostKey = fp
            onHostKey(fp)
        }
        try {
            when (auth) {
                is SshAuth.Key -> {
                    val keys = try {
                        SshKeys.load(auth.privateKey)
                    } catch (e: IllegalArgumentException) {
                        throw SshException(e.message ?: "私钥有问题", SshException.Stage.Login)
                    }
                    ssh.authPublickey(user, keys)
                }
                is SshAuth.Password -> ssh.authPassword(user, auth.password)
            }
        } catch (e: UserAuthException) {
            runCatching { ssh.disconnect() }
            throw SshException(
                when (auth) {
                    is SshAuth.Key -> "SSH 登录被拒绝：服务器不认这把密钥。把公钥加到 $user 的 ~/.ssh/authorized_keys 里"
                    is SshAuth.Password -> "SSH 登录被拒绝：用户名或密码不对（也可能服务器关闭了密码登录）"
                },
                SshException.Stage.Login,
            )
        } catch (e: SshException) {
            runCatching { ssh.disconnect() }
            throw e
        } catch (e: IOException) {
            runCatching { ssh.disconnect() }
            throw SshException("SSH 登录失败：${e.message}", SshException.Stage.Login)
        }
        ssh.connection.keepAlive.keepAliveInterval = 15
        client = ssh
        return ssh
    }

    private fun acceptLoop() {
        while (!closed) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                if (closed) return else continue
            }
            thread(name = "ssh-tunnel-conn", isDaemon = true) { forward(socket) }
        }
    }

    /** 给一个本地连接开 SSH 通道并双向搬运数据。通道开不起来（连接刚断）就重连一次再试。 */
    private fun forward(socket: Socket) {
        val channel = try {
            open()
        } catch (e: Exception) {
            reset()
            try {
                open()
            } catch (e: Exception) {
                runCatching { socket.close() }
                return
            }
        }
        socket.tcpNoDelay = true
        val up = thread(name = "ssh-tunnel-up", isDaemon = true) {
            pump(socket.getInputStream(), channel.outputStream)
            runCatching { channel.outputStream.close() }
        }
        pump(channel.inputStream, socket.getOutputStream())
        runCatching { socket.shutdownOutput() }
        up.join(2_000)
        runCatching { channel.close() }
        runCatching { socket.close() }
    }

    private fun open() = session().newDirectConnection("127.0.0.1", remotePort)

    private fun pump(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                output.flush()
            }
        } catch (_: IOException) {
        }
    }
}

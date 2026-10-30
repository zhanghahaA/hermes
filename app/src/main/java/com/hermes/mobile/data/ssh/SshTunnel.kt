package com.hermes.mobile.data.ssh

import com.hermes.mobile.data.ConnectionTrace
import com.hermes.mobile.data.ConnectionTrace.Event
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
    private val diagnostics: ConnectionTrace? = null,
) : Closeable, RemoteShell {

    // HTTP 入口始终使用 IPv4 字面地址，监听地址必须与它完全一致。
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val localPort: Int get() = server.localPort
    val localBaseUrl: String get() = "http://127.0.0.1:$localPort"
    val localBindAddress: InetAddress get() = server.inetAddress
    internal val activeConnectionCount: Int get() = localSockets.size
    private val localSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val channels = ConcurrentHashMap.newKeySet<net.schmizz.sshj.connection.channel.direct.DirectConnection>()
    private val connectionSerial = AtomicLong()
    private val closeOnce = AtomicBoolean()

    @Volatile private var client: SSHClient? = null
    @Volatile private var closed = false
    @Volatile private var acceptedHostKey: String? = pinnedHostKey

    /** 最近一次连上时服务器的指纹。 */
    val hostKey: String? get() = acceptedHostKey

    init {
        diagnostics?.record(Event.ListenerBound, localPort = localPort, remotePort = remotePort)
        thread(name = "ssh-tunnel-accept", isDaemon = true) { acceptLoop() }
    }

    /** 立刻建立 SSH 连接并登录（不等第一个请求），出错时抛 [SshException]，用来尽早给出明确的错误。 */
    fun connect() {
        try { session() } catch (e: IOException) {
            diagnostics?.record(Event.SshFailed, error = e)
            throw e
        }
    }

    /** 丢掉当前 SSH 连接（例如网络切换后它多半已经死了），下一个请求会重新连。 */
    fun reset() {
        diagnostics?.record(Event.SessionReset)
        val old = synchronized(this) { client.also { client = null } }
        runCatching { old?.disconnect() }
    }

    private fun resetIfCurrent(expected: SSHClient) {
        val old = synchronized(this) {
            if (client === expected) client.also { client = null } else null
        }
        if (old != null) {
            diagnostics?.record(Event.SessionReset)
            runCatching { old.disconnect() }
        }
    }

    /**
     * 把手机上的文件传到服务器的 `~/hermes-uploads/` 下（文件名前加时间，避免覆盖），返回服务器上的绝对路径。
     * 连接断了会先重连一次。
     */
    /**
     * 把本机文件经 SFTP 传到服务器的 [dir]（`~/…` 是相对 SSH 登录用户的主目录；为空用 `~/hermes-uploads`），
     * 文件夹不存在就建。保留原文件名，重名时加序号：`config.yaml`、`config (1).yaml`……返回服务器上的绝对路径。
     */
    fun upload(localPath: String, name: String, dir: String? = null): String {
        fun once(): String = session().newSFTPClient().use { sftp ->
            val home = sftp.canonicalize(".").trimEnd('/')
            val wanted = dir?.trim()?.takeIf { it.isNotEmpty() } ?: "~/hermes-uploads"
            val target = when {
                wanted == "~" -> home
                wanted.startsWith("~/") -> home + "/" + wanted.removePrefix("~/").trimEnd('/')
                wanted.startsWith("/") -> wanted.trimEnd('/').ifEmpty { "/" }
                else -> home + "/" + wanted.trimEnd('/')
            }
            sftp.mkdirs(target)
            val source = net.schmizz.sshj.xfer.FileSystemFile(localPath)
            fun put(fileName: String): String {
                val path = freePath(sftp, target, fileName)
                sftp.put(source, path)
                return path
            }
            try {
                put(safeName(name))
            } catch (e: net.schmizz.sshj.sftp.SFTPException) {
                // 有的 SFTP 服务器不接受中文、空格等文件名，换成纯 ASCII 再试一次（保留扩展名）
                put(asciiName(name))
            }
        }
        return try {
            once()
        } catch (e: IOException) {
            reset()
            once()
        }
    }

    /** [dir] 里还没被占用的文件名：先用原名，有了就加 (1)、(2)……（加在扩展名前面）。 */
    private fun freePath(sftp: net.schmizz.sshj.sftp.SFTPClient, dir: String, fileName: String): String {
        val dot = fileName.lastIndexOf('.').takeIf { it > 0 } ?: fileName.length
        val base = fileName.substring(0, dot)
        val ext = fileName.substring(dot)
        var n = 0
        while (true) {
            val candidate = "$dir/" + (if (n == 0) fileName else "$base ($n)$ext")
            if (sftp.statExistence(candidate) == null) return candidate
            n++
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
    override fun exec(command: String, timeoutSeconds: Long): Pair<Int, String> {
        fun once(): Pair<Int, String> = session().startSession().use { s ->
            val cmd = s.exec("bash -lc " + shellQuote(command) + " 2>&1")
            val out = cmd.inputStream.readBytes().toString(Charsets.UTF_8)
            cmd.join(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            (cmd.exitStatus ?: -1) to out
        }
        return try {
            once()
        } catch (e: IOException) {
            // 服务器拒绝开命令通道（比如不允许执行命令）不代表连接坏了：别重连，免得打断同时在跑的传输
            if (connectionAlive()) throw e
            reset()
            once()
        }
    }

    override fun openStream(command: String): RemoteStream {
        fun once(): RemoteStream {
            val s = session().startSession()
            val cmd = try {
                s.exec("bash -lc " + shellQuote(command))
            } catch (e: IOException) {
                s.close()
                throw e
            }
            return object : RemoteStream {
                override val output = cmd.inputStream
                override val input = cmd.outputStream
                override fun errorText(): String = runCatching { cmd.errorStream.readBytes().toString(Charsets.UTF_8) }.getOrDefault("")
                override val exitCode: Int? get() = cmd.exitStatus
                override fun close() {
                    runCatching { cmd.close() }
                    runCatching { s.close() }
                }
            }
        }
        return try {
            once()
        } catch (e: IOException) {
            if (connectionAlive()) throw e
            reset()
            once()
        }
    }

    private fun connectionAlive(): Boolean = synchronized(this) { client?.let { it.isConnected && it.isAuthenticated } == true }

    override fun readFile(path: String): ByteArray? {
        fun once(): ByteArray? = session().newSFTPClient().use { sftp ->
            if (sftp.statExistence(path) == null) return@use null
            sftp.open(path).use { file -> file.RemoteFileInputStream().use { it.readBytes() } }
        }
        return try {
            once()
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            throw e
        } catch (e: IOException) {
            reset()
            once()
        }
    }

    override fun writeFile(path: String, bytes: ByteArray) {
        fun once() = session().newSFTPClient().use { sftp ->
            val modes = java.util.EnumSet.of(
                net.schmizz.sshj.sftp.OpenMode.WRITE,
                net.schmizz.sshj.sftp.OpenMode.CREAT,
                net.schmizz.sshj.sftp.OpenMode.TRUNC,
            )
            sftp.open(path, modes).use { file -> file.RemoteFileOutputStream().use { it.write(bytes) } }
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

    private fun shellQuote(text: String) = "'" + text.replace("'", "'\\''") + "'"

    /**
     * 试着开一条到 Hermes 端口的转发通道，返回失败原因（给人看的话），成功返回 null。
     * 用来区分“服务器禁止端口转发”和“那个端口上没有服务”。
     */
    fun probeForward(): String? {
        diagnostics?.record(Event.ProbeStart)
        return try {
            open(0).close()
            diagnostics?.record(Event.ProbePassed)
            null
        } catch (e: net.schmizz.sshj.connection.channel.OpenFailException) {
            diagnostics?.record(Event.ProbeFailed, error = e)
            when (e.reason) {
            net.schmizz.sshj.connection.channel.OpenFailException.Reason.ADMINISTRATIVELY_PROHIBITED ->
                "SSH 服务器不允许端口转发：在它的 sshd_config 里加上 AllowTcpForwarding local（或 yes）并重载 sshd"
            net.schmizz.sshj.connection.channel.OpenFailException.Reason.CONNECT_FAILED ->
                "SSH 已登录，但服务器上 127.0.0.1:$remotePort 没有服务在监听：确认 Hermes gateway 在运行、API Server 已开启、端口正确"
            else -> "SSH 转发失败（${e.reason}）"
            }
        } catch (e: IOException) {
            diagnostics?.record(Event.ProbeFailed, error = e)
            "SSH 转发失败（${e.javaClass.simpleName}）"
        }
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
        if (!closeOnce.compareAndSet(false, true)) return
        closed = true
        runCatching { server.close() }
        localSockets.forEach { runCatching { it.close() } }
        reset()
        channels.forEach { runCatching { it.close() } }
        diagnostics?.record(Event.TunnelClosed)
    }

    @Synchronized
    private fun session(): SSHClient {
        client?.let { if (it.isConnected && it.isAuthenticated) return it }
        runCatching { client?.disconnect() }
        client = null
        if (closed) throw SshException("隧道已关闭", SshException.Stage.Connect)
        diagnostics?.record(Event.SshConnectStart)

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
        if (closed) {
            runCatching { ssh.disconnect() }
            throw SshException("隧道已关闭", SshException.Stage.Connect)
        }
        client = ssh
        diagnostics?.record(Event.SshAuthenticated)
        return ssh
    }

    private fun acceptLoop() {
        while (!closed) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                if (closed) return else continue
            }
            localSockets += socket
            if (closed) {
                localSockets -= socket
                runCatching { socket.close() }
                return
            }
            val id = connectionSerial.incrementAndGet()
            diagnostics?.record(Event.LocalAccepted, connection = id)
            thread(name = "ssh-tunnel-conn", isDaemon = true) { forward(socket, id) }
        }
    }

    /** 单条通道失败不重置活着的 SSH 会话；两边分别半关闭，全部结束后再释放。 */
    private fun forward(socket: Socket, id: Long) {
        val channel = try {
            open(id)
        } catch (e: Exception) {
            diagnostics?.record(Event.ChannelFailed, connection = id, error = e)
            localSockets -= socket
            runCatching { socket.close() }
            diagnostics?.record(Event.LocalClosed, connection = id)
            return
        }
        channels += channel
        val finished = AtomicBoolean()
        fun closeConnection() {
            if (finished.compareAndSet(false, true)) {
                runCatching { socket.close() }
                runCatching { channel.close() }
            }
        }
        try {
            if (closed) return
            socket.tcpNoDelay = true
            val up = thread(name = "ssh-tunnel-up", isDaemon = true) {
                val end = pump({ socket.getInputStream() }, { channel.outputStream }, id, up = true)
                if (end == ConnectionTrace.End.Eof) {
                    try {
                        // sshj ChannelOutputStream.close() 发送 SSH EOF，不关闭反向通道。
                        channel.outputStream.close()
                        diagnostics?.record(Event.UpHalfClosed, connection = id)
                    } catch (e: IOException) {
                        diagnostics?.record(Event.HalfCloseFailed, connection = id, error = e)
                        closeConnection()
                    }
                } else closeConnection()
            }
            val end = pump({ channel.inputStream }, { socket.getOutputStream() }, id, up = false)
            if (end == ConnectionTrace.End.Eof) {
                try {
                    socket.shutdownOutput()
                    diagnostics?.record(Event.DownHalfClosed, connection = id)
                } catch (e: IOException) {
                    diagnostics?.record(Event.HalfCloseFailed, connection = id, error = e)
                    closeConnection()
                }
            } else closeConnection()
            // 不能固定等两秒就截断仍在上传的数据；close/reset 会解除两边阻塞。
            up.join()
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            diagnostics?.record(Event.LocalClosed, connection = id, error = e)
        } finally {
            closeConnection()
            channels -= channel
            localSockets -= socket
            diagnostics?.record(Event.LocalClosed, connection = id)
        }
    }

    private fun open(id: Long): net.schmizz.sshj.connection.channel.direct.DirectConnection {
        fun once(ssh: SSHClient): net.schmizz.sshj.connection.channel.direct.DirectConnection {
            diagnostics?.record(Event.ChannelOpening, connection = id)
            return try {
                ssh.newDirectConnection("127.0.0.1", remotePort).also {
                    diagnostics?.record(Event.ChannelOpened, connection = id)
                }
            } catch (e: IOException) {
                val failure = when ((e as? net.schmizz.sshj.connection.channel.OpenFailException)?.reason) {
                    net.schmizz.sshj.connection.channel.OpenFailException.Reason.ADMINISTRATIVELY_PROHIBITED -> ConnectionTrace.ForwardFailure.Denied
                    net.schmizz.sshj.connection.channel.OpenFailException.Reason.CONNECT_FAILED -> ConnectionTrace.ForwardFailure.RemoteConnect
                    else -> if (!ssh.isConnected || !ssh.isAuthenticated) ConnectionTrace.ForwardFailure.SessionLost else ConnectionTrace.ForwardFailure.Other
                }
                diagnostics?.record(Event.ChannelFailed, connection = id, error = e, failure = failure)
                throw e
            }
        }
        val ssh = session()
        return try { once(ssh) } catch (e: IOException) {
            if (closed || (ssh.isConnected && ssh.isAuthenticated)) throw e
            resetIfCurrent(ssh)
            once(session())
        }
    }

    private fun pump(input: () -> InputStream, output: () -> OutputStream, id: Long, up: Boolean): ConnectionTrace.End {
        val buffer = ByteArray(16 * 1024)
        var bytes = 0L
        var end = ConnectionTrace.End.Eof
        var failure: IOException? = null
        try {
            val source = input()
            val sink = output()
            while (true) {
                val n = source.read(buffer)
                if (n < 0) break
                sink.write(buffer, 0, n)
                sink.flush()
                if (bytes == 0L && n > 0) diagnostics?.record(if (up) Event.UpFirstBytes else Event.DownFirstBytes, connection = id, bytes = n.toLong())
                bytes += n
            }
        } catch (e: IOException) {
            end = if (closed) ConnectionTrace.End.Closed else ConnectionTrace.End.Error
            failure = e
        } finally {
            diagnostics?.record(if (up) Event.UpFinished else Event.DownFinished, connection = id, bytes = bytes, end = end, error = failure)
        }
        return end
    }
}

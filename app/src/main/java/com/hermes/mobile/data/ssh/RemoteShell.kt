package com.hermes.mobile.data.ssh

/** 在服务器上执行命令、读写文件（经 SSH）。管理功能都基于它，测试里可以换成假的。 */
interface RemoteShell {
    /** 执行一条命令，返回退出码和输出（stdout + stderr）。 */
    fun exec(command: String, timeoutSeconds: Long = 60): Pair<Int, String>

    /** 读一个文件（绝对路径）；不存在返回 null。 */
    fun readFile(path: String): ByteArray?

    /** 写一个文件（绝对路径），覆盖原有内容。 */
    fun writeFile(path: String, bytes: ByteArray)

    /**
     * 开一条一直开着的命令通道：往 [RemoteStream.input] 写的进命令的 stdin，命令的 stdout 从 [RemoteStream.output] 读。
     * stdout 只有数据（Bot 屏幕的画面），stderr 单独放。
     */
    fun openStream(command: String): RemoteStream = throw java.io.IOException("这个连接不支持")
}

/** 一条双向的字节流（经 SSH 的命令通道）。 */
interface RemoteStream : java.io.Closeable {
    /** 命令的 stdout。 */
    val output: java.io.InputStream
    /** 命令的 stdin。 */
    val input: java.io.OutputStream
    /** 命令的 stderr 全文（命令结束后读，出错时用来说明原因）。 */
    fun errorText(): String
    /** 命令结束的退出码；还没结束为 null。 */
    val exitCode: Int?
}

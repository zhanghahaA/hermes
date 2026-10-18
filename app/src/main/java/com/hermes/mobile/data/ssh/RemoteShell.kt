package com.hermes.mobile.data.ssh

/** 在服务器上执行命令、读写文件（经 SSH）。管理功能都基于它，测试里可以换成假的。 */
interface RemoteShell {
    /** 执行一条命令，返回退出码和输出（stdout + stderr）。 */
    fun exec(command: String, timeoutSeconds: Long = 60): Pair<Int, String>

    /** 读一个文件（绝对路径）；不存在返回 null。 */
    fun readFile(path: String): ByteArray?

    /** 写一个文件（绝对路径），覆盖原有内容。 */
    fun writeFile(path: String, bytes: ByteArray)
}

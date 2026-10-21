package com.hermes.mobile.data.ssh

import com.hermes.mobile.data.remote.FakeHermesServer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

/** adb 设备测试用本机服务。端口文件不含凭据；不启动真实 Hermes，也不访问模型。 */
object AndroidSshFixture {
    @JvmStatic fun main(args: Array<String>) {
        val directory = Path.of(args.single()).toAbsolutePath()
        Files.createDirectories(directory)
        val api = FakeHermesServer(FakeHermesServer.Version.Modern).start()
        val ssh = TestSshServer().start()
        Runtime.getRuntime().addShutdownHook(Thread {
            ssh.stop()
            api.shutdown()
            Files.deleteIfExists(directory.resolve("ports.properties"))
        })
        Files.writeString(directory.resolve("ports.properties"), "sshTestPort=${ssh.port}\nhermesTestPort=${api.server.port}\n")
        println("本机假 SSH/API 已就绪；端口文件：$directory/ports.properties")
        CountDownLatch(1).await()
    }
}

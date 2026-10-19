package com.hermes.mobile.data.ssh

import com.hermes.mobile.data.model.ChatItem
import com.hermes.mobile.data.model.ConnectionProfile
import com.hermes.mobile.data.model.ConnectionState
import com.hermes.mobile.data.model.ConnectionType
import com.hermes.mobile.data.model.Role
import com.hermes.mobile.data.model.SshAuthMethod
import com.hermes.mobile.data.model.TestStepStatus
import com.hermes.mobile.data.remote.RemoteHermesRepository
import com.hermes.mobile.platform.FakeSecretBox
import com.hermes.mobile.platform.MemoryLocalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 和真正的 OpenSSH 服务器互通（密钥由 ssh-keygen 生成），再经隧道连到真实的 Hermes。只有设置了环境变量才跑：
 *   HERMES_IT_SSH_DIR=<放 user_ed25519 等私钥和 host_ed25519.pub 的目录> HERMES_IT_SSH_PORT=2222 HERMES_IT_SSH_USER=root
 *   HERMES_IT_KEY=<API_SERVER_KEY>
 */
class OpenSshIT {
    private val dir = System.getenv("HERMES_IT_SSH_DIR")?.let(::File)
    private val sshPort = System.getenv("HERMES_IT_SSH_PORT")?.toIntOrNull() ?: 22
    private val user = System.getenv("HERMES_IT_SSH_USER") ?: "root"
    private val key = System.getenv("HERMES_IT_KEY")

    @Test
    fun openSshKeysAndTunnelToRealHermes() {
        if (dir == null || key == null) {
            println("跳过：没有设置 HERMES_IT_SSH_DIR / HERMES_IT_KEY")
            return
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // ssh-keygen -lf host_ed25519.pub 的输出：256 SHA256:xxx comment (ED25519)
            val expectedFingerprint = ProcessBuilder("ssh-keygen", "-lf", File(dir, "host_ed25519.pub").path).start()
                .inputStream.bufferedReader().readText().split(" ")[1]

            for (type in listOf("ed25519", "rsa", "ecdsa")) {
                val privateKey = File(dir, "user_$type").readText()
                val profile = ConnectionProfile(
                    id = "os-$type", name = "OpenSSH $type", type = ConnectionType.Ssh, host = "127.0.0.1", port = 8642,
                    sshUser = user, sshPort = sshPort, sshAuth = SshAuthMethod.Key,
                )
                val repo = RemoteHermesRepository(scope, MemoryLocalStore(), FakeSecretBox())
                val steps = runBlocking { withTimeout(30_000) { repo.testConnection(profile, key, privateKey).toList().last() } }
                println("$type 密钥：" + steps.joinToString { "${it.title}=${it.status}" })
                assertTrue(steps.all { it.status == TestStepStatus.Passed }, steps.toString())
                assertTrue(steps[0].detail!!.contains(expectedFingerprint), "指纹和 ssh-keygen 显示的一致：${steps[0].detail}")
            }

            // 用 Ed25519 密钥真正连上并聊一句
            val repo = RemoteHermesRepository(scope, MemoryLocalStore(), FakeSecretBox())
            val profile = ConnectionProfile(
                id = "os", name = "OpenSSH", type = ConnectionType.Ssh, host = "127.0.0.1", port = 8642,
                sshUser = user, sshPort = sshPort, sshAuth = SshAuthMethod.Key,
            )
            repo.saveConnection(profile, key, File(dir, "user_ed25519").readText())
            val state = runBlocking { withTimeout(30_000) { repo.connectionState.first { it is ConnectionState.Connected || it is ConnectionState.Failed } } }
            assertTrue(state is ConnectionState.Connected, state.toString())
            assertEquals(expectedFingerprint, repo.connections.value.single().sshHostKey)
            val chat = repo.createSession()!!
            repo.sendMessage(chat, "隧道你好", emptyList())
            val items = runBlocking {
                withTimeout(60_000) { repo.chatItems(chat).first { list -> list.any { it is ChatItem.Message && it.role == Role.Assistant } } }
            }
            println("经 OpenSSH 隧道的回复：${items.filterIsInstance<ChatItem.Message>().filter { it.role == Role.Assistant }.map { it.text }}")
            assertTrue(items.any { it is ChatItem.Message && it.text == "收到：隧道你好" })
            runBlocking { repo.session(chat).first() }?.id?.let(repo::deleteSession)
            Thread.sleep(1000)

            // App 生成的密钥：OpenSSH 能读，加进 authorized_keys 后能登录
            val generated = SshKeys.generate()
            val tmp = File.createTempFile("gen", "").apply { writeText(generated.privateKey); setReadable(false, false); setReadable(true, true) }
            val derived = ProcessBuilder("ssh-keygen", "-y", "-f", tmp.path).start().inputStream.bufferedReader().readText().trim()
            tmp.delete()
            fun keyPart(line: String) = line.split(' ').take(2).joinToString(" ")
            assertEquals(keyPart(generated.publicKey), keyPart(derived), "ssh-keygen 能读 App 生成的私钥")
            val authorized = File(dir, "authorized_keys")
            val original = authorized.readText()
            try {
                authorized.appendText(generated.publicKey + "\n")
                val genSteps = runBlocking { withTimeout(30_000) { repo.testConnection(profile.copy(id = "os-gen"), key, generated.privateKey).toList().last() } }
                println("App 生成的密钥：" + genSteps.joinToString { "${it.title}=${it.status}" })
                assertTrue(genSteps.all { it.status == TestStepStatus.Passed }, genSteps.toString())
            } finally {
                authorized.writeText(original)
            }

            // 普通文件经 SFTP 上传到真正的 OpenSSH（中文文件名保持原样）
            repo.switchConnection("os")
            runBlocking { withTimeout(30_000) { repo.connectionState.first { it is ConnectionState.Connected } } }
            val bytes = ByteArray(200_000) { (it % 253).toByte() }
            val local = File.createTempFile("upload", ".bin").apply { writeBytes(bytes) }
            val chat2 = repo.createSession()!!
            repo.sendMessage(chat2, "收到文件了吗", listOf(com.hermes.mobile.data.model.Attachment.BinaryFile("b", "季度 报表.xlsx", "application/octet-stream", local.path, bytes.size.toLong())))
            val withFile = runBlocking {
                withTimeout(60_000) {
                    repo.chatItems(chat2).first { list ->
                        list.any { it is ChatItem.Message && it.role == Role.Assistant } &&
                            list.filterIsInstance<ChatItem.Message>().none { it.id.startsWith("local-") || it.id.startsWith("reply-") }
                    }
                }
            }
            val note = withFile.filterIsInstance<ChatItem.Message>().first { it.role == Role.User }.files.single().note!!
            println("OpenSSH 上传：$note")
            val remotePath = note.removePrefix("已上传：")
            assertTrue(remotePath.endsWith("-季度 报表.xlsx"), "OpenSSH 接受中文文件名：$remotePath")
            // 测试进程可能没有 UTF-8 区域设置，按目录列出来找（Path 保留原始字节）
            val uploaded = java.nio.file.Files.list(java.nio.file.Paths.get(remotePath.substringBeforeLast('/'))).use { files ->
                files.toList().filter { it.toString().endsWith(".xlsx") }.maxBy { java.nio.file.Files.getLastModifiedTime(it) }
            }
            assertTrue(java.nio.file.Files.readAllBytes(uploaded).contentEquals(bytes), "上传内容完整")
            runBlocking { repo.session(chat2).first() }?.id?.let(repo::deleteSession)

            // 带口令的私钥：给出能看懂的提示
            val protectedSteps = runBlocking {
                withTimeout(30_000) {
                    repo.testConnection(profile.copy(id = "os-p"), key, File(dir, "user_protected").readText()).toList().last()
                }
            }
            println("带口令的私钥：${protectedSteps[1].detail}")
            assertEquals(TestStepStatus.Failed, protectedSteps[1].status)
            assertTrue(protectedSteps[1].detail!!.contains("口令"), protectedSteps[1].detail)
        } finally {
            scope.cancel()
        }
    }

    /**
     * 有 SSH 时：技能接口坏了就直接读技能目录；工具集可以开关（`hermes tools enable|disable`）。
     * 需要 sshd 那边能找到 hermes 命令和 HERMES_HOME（测试用 sshd_config 里 SetEnv）。
     */
    @Test
    fun sshManagesSkillsAndToolsets() {
        if (dir == null || key == null) {
            println("跳过：没有设置 HERMES_IT_SSH_DIR / HERMES_IT_KEY")
            return
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = RemoteHermesRepository(scope, MemoryLocalStore(), FakeSecretBox())
            val profile = ConnectionProfile(
                id = "os", name = "OpenSSH", type = ConnectionType.Ssh, host = "127.0.0.1", port = 8642,
                sshUser = user, sshPort = sshPort, sshAuth = SshAuthMethod.Key,
            )
            repo.saveConnection(profile, key, File(dir, "user_ed25519").readText())
            runBlocking { withTimeout(30_000) { repo.connectionState.first { it is ConnectionState.Connected } } }
            assertTrue(repo.canToggleToolsets())

            val skills = runBlocking { withTimeout(30_000) { repo.skills.first { it.isNotEmpty() } } }
            println("经 SSH 读到 ${skills.size} 个技能，例如：" + skills.take(3).joinToString { "${it.category}/${it.name}: ${it.description}" })
            assertTrue(skills.all { it.name.isNotBlank() })

            val before = runBlocking { withTimeout(15_000) { repo.toolsets.first { list -> list.any { it.name == "tts" } } } }.first { it.name == "tts" }
            repo.toggleToolset("tts")
            val after = runBlocking { withTimeout(60_000) { repo.toolsets.first { list -> list.first { it.name == "tts" }.enabled != before.enabled } } }
            println("tts：${before.enabled} → ${after.first { it.name == "tts" }.enabled}")
            runBlocking { withTimeout(10_000) { repo.toolsetsBusy.first { it.isEmpty() } } }
            repo.toggleToolset("tts")
            runBlocking { withTimeout(60_000) { repo.toolsets.first { list -> list.first { it.name == "tts" }.enabled == before.enabled } } }
        } finally {
            scope.cancel()
        }
    }

    /** 管理功能：记忆、MCP、配置文件、日志，全部对着真实的 sshd + hermes 命令。 */
    @Test
    fun adminOverRealSsh() {
        if (dir == null || key == null) {
            println("跳过：没有设置 HERMES_IT_SSH_DIR / HERMES_IT_KEY")
            return
        }
        val tunnel = com.hermes.mobile.data.ssh.SshTunnel(
            "127.0.0.1", sshPort, user, com.hermes.mobile.data.ssh.SshAuth.Key(File(dir, "user_ed25519").readText()), 8642, null, {},
        )
        try {
            tunnel.connect()
            val admin = com.hermes.mobile.data.remote.HermesAdmin(tunnel, null)
            runBlocking {
                // 记忆：写进去、读回来，再恢复
                val before = admin.memory()
                println("记忆：" + before.joinToString { "${it.target.label} ${it.entries.size} 条 ${it.used}/${it.charLimit}" })
                val user = before.first { it.target == com.hermes.mobile.data.model.MemoryTarget.User }
                admin.saveMemory(user.target, user.entries + "IT 测试：喜欢简短回答")
                assertTrue(admin.memory().first { it.target == user.target }.entries.contains("IT 测试：喜欢简短回答"))
                admin.saveMemory(user.target, user.entries)
                assertEquals(user.entries, admin.memory().first { it.target == user.target }.entries)

                // MCP：新建、改成网址、停用、测试、删除
                admin.saveMcpServer(com.hermes.mobile.data.model.McpServer("ittest", command = "echo", args = listOf("hi there")))
                assertEquals(listOf("hi there"), admin.mcpServers().first { it.name == "ittest" }.args)
                admin.saveMcpServer(com.hermes.mobile.data.model.McpServer("ittest", url = "https://example.com/mcp"), previousName = "ittest")
                val changed = admin.mcpServers().first { it.name == "ittest" }
                assertEquals("https://example.com/mcp", changed.url)
                assertEquals(null, changed.command, "改成网址后旧的命令字段要清掉")
                admin.setMcpEnabled("ittest", false)
                assertEquals(false, admin.mcpServers().first { it.name == "ittest" }.enabled)
                println("MCP 测试输出：" + admin.testMcpServer("ittest").lines().take(3))
                admin.removeMcpServer("ittest")
                assertTrue(admin.mcpServers().none { it.name == "ittest" })

                // 配置文件：坏的不保存，好的保存后能读回
                val original = admin.readConfig()
                assertTrue(original.isNotBlank())
                val e = runCatching { admin.saveConfig(original + "\nbroken: [unclosed\n") }.exceptionOrNull()
                println("坏配置：${e?.message}")
                assertTrue(e is com.hermes.mobile.data.remote.AdminException)
                assertEquals(original, admin.readConfig(), "格式不对时原文件不动")
                admin.saveConfig(original + "\n# hermes-mobile IT\n")
                assertTrue(admin.readConfig().endsWith("# hermes-mobile IT\n"))
                admin.saveConfig(original)
                assertEquals(original, admin.readConfig())

                val logs = admin.logs(20)
                println("日志最后一行：" + logs.lines().lastOrNull())
                assertTrue(logs.isNotBlank())
            }
        } finally {
            tunnel.close()
        }
    }

    /** 经 SSH 连接发照片：真实 Hermes 收到图片，同时原文件落在服务器上，消息里有路径。 */
    @Test
    fun photoOriginalReachesTheServer() {
        if (dir == null || key == null) {
            println("跳过：没有设置 HERMES_IT_SSH_DIR / HERMES_IT_KEY")
            return
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = RemoteHermesRepository(scope, MemoryLocalStore(), FakeSecretBox())
            val profile = ConnectionProfile(
                id = "os", name = "OpenSSH", type = ConnectionType.Ssh, host = "127.0.0.1", port = 8642,
                sshUser = user, sshPort = sshPort, sshAuth = SshAuthMethod.Key,
            )
            repo.saveConnection(profile, key, File(dir, "user_ed25519").readText())
            runBlocking { withTimeout(30_000) { repo.connectionState.first { it is ConnectionState.Connected } } }
            val image = java.awt.image.BufferedImage(320, 200, java.awt.image.BufferedImage.TYPE_INT_RGB).apply {
                createGraphics().apply { color = java.awt.Color(40, 120, 200); fillRect(0, 0, 320, 200); dispose() }
            }
            val photo = File.createTempFile("photo", ".jpg").apply { javax.imageio.ImageIO.write(image, "jpg", this) }
            val chat = repo.createSession()!!
            repo.sendMessage(chat, "把这张图存好", listOf(com.hermes.mobile.data.model.Attachment.Image("p", photo.path, 1.6f)))
            val items = runBlocking {
                withTimeout(60_000) {
                    repo.chatItems(chat).first { list ->
                        list.any { it is ChatItem.Message && it.role == Role.Assistant } &&
                            list.filterIsInstance<ChatItem.Message>().none { it.id.startsWith("local-") || it.id.startsWith("reply-") }
                    }
                }
            }
            val reply = items.filterIsInstance<ChatItem.Message>().last { it.role == Role.Assistant }.text
            println("模型看到的（假模型复述）：${reply.take(400)}")
            val path = Regex("📎 图片文件：(\\S+?)（").find(reply)?.groupValues?.get(1)
            assertTrue(path != null, "模型收到了原文件路径")
            assertTrue(File(path!!).readBytes().contentEquals(photo.readBytes()), "服务器上的原文件和发出的一样：$path")
            val mine = items.filterIsInstance<ChatItem.Message>().last { it.role == Role.User }
            assertEquals("把这张图存好", mine.text)
            assertEquals(1, mine.images.size)
            runBlocking { repo.session(chat).first() }?.id?.let(repo::deleteSession)
            File(path).delete()
        } finally {
            scope.cancel()
        }
    }

    /** 真实 Hermes：建项目、在项目里开新对话、移出项目、归档再取消归档。 */
    @Test
    fun projectsAndArchiveOverRealSsh() {
        if (dir == null || key == null) {
            println("跳过：没有设置 HERMES_IT_SSH_DIR / HERMES_IT_KEY")
            return
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val folder = kotlin.io.path.createTempDirectory("it-project").toFile().canonicalFile
        val name = "it-proj-${System.nanoTime() % 100000}"
        try {
            val repo = RemoteHermesRepository(scope, MemoryLocalStore(), FakeSecretBox())
            val profile = ConnectionProfile(
                id = "os", name = "OpenSSH", type = ConnectionType.Ssh, host = "127.0.0.1", port = 8642,
                sshUser = user, sshPort = sshPort, sshAuth = SshAuthMethod.Key,
            )
            repo.saveConnection(profile, key, File(dir, "user_ed25519").readText())
            runBlocking { withTimeout(30_000) { repo.connectionState.first { it is ConnectionState.Connected } } }
            val admin = assertNotNull(repo.admin())

            val (home, children) = runBlocking { admin.listFolders(folder.parent) }
            assertEquals(folder.parentFile.canonicalPath, home)
            assertTrue(folder.name in children, children.toString())

            runBlocking { admin.createProject(name, folder.path); repo.refreshGroups() }
            val project = repo.sessionGroups.value.projects.firstOrNull { it.name == name }
            assertNotNull(project, repo.sessionGroups.value.projects.toString())
            assertEquals(folder.path, project.path)

            // 在项目里开新对话：发完第一条，它就在项目里
            val chat = repo.createSession()!!
            repo.setDraftProject(chat, project)
            repo.sendMessage(chat, "你好", emptyList())
            runBlocking {
                withTimeout(60_000) {
                    repo.chatItems(chat).first { list -> list.any { it is ChatItem.Message && it.role == Role.Assistant && !it.id.startsWith("reply-") } }
                }
            }
            val real = runBlocking { repo.session(chat).first() }!!.id
            fun waitGroup(expected: String) = runBlocking {
                withTimeout(30_000) {
                    while (true) {
                        repo.refreshGroups()
                        if (repo.sessionGroups.value.groupOf(real) == expected) break
                        kotlinx.coroutines.delay(500)
                    }
                }
            }
            waitGroup(name)
            assertEquals(folder.path, repo.sessionGroups.value.folders[real], "Hermes 里会话的工作目录是项目文件夹")

            repo.moveToProject(real, null)
            waitGroup("默认")

            // 新建文件夹、改名、删除项目（用 Hermes 自己的 projects_db）
            val sub = runBlocking { admin.makeFolder(folder.path, "sub folder") }
            assertTrue(File(sub).isDirectory)
            assertEquals(admin.let { runBlocking { it.hermesHome() } }.isNotBlank(), true)
            runBlocking { admin.renameProject(project.id, "$name-改名"); repo.refreshGroups() }
            assertTrue(repo.sessionGroups.value.projects.any { it.id == project.id && it.name == "$name-改名" }, repo.sessionGroups.value.projects.toString())
            runBlocking { admin.deleteProject(project.id); repo.refreshGroups() }
            assertTrue(repo.sessionGroups.value.projects.none { it.id == project.id }, "删除后项目不在了")
            assertTrue(runCatching { runBlocking { admin.deleteProject(project.id) } }.exceptionOrNull()?.message?.contains("找不到") == true)

            // 归档：Hermes 的列表里不再有，分组脚本能看到它归档了
            repo.setArchived(real, true)
            runBlocking {
                withTimeout(15_000) {
                    while (admin.sessionGroups().archived.none { it.id == real }) kotlinx.coroutines.delay(300)
                    repo.refreshSessions()
                }
            }
            assertTrue(repo.sessions.value.none { it.id == real }, "归档后不在列表里")
            assertTrue(repo.archivedSessions.value.any { it.id == real })
            repo.setArchived(real, false)
            runBlocking { withTimeout(15_000) { repo.sessions.first { list -> list.any { it.id == real } } } }
            repo.deleteSession(real)
            Thread.sleep(500)
        } finally {
            // 测试建的项目归档掉，不留在测试 Hermes 的项目列表里
            runCatching {
                val tunnel = com.hermes.mobile.data.ssh.SshTunnel("127.0.0.1", sshPort, user, com.hermes.mobile.data.ssh.SshAuth.Key(File(dir, "user_ed25519").readText()), 8642, null, {})
                tunnel.connect()
                println("清理：" + tunnel.exec("hermes project archive $name", 30).second.trim())
                tunnel.close()
            }
            folder.deleteRecursively()
            scope.cancel()
        }
    }

    /** Bot 屏幕：状态、经 SSH 接到 RFB socket（读到 VNC 握手）、接管和交还（Hermes 自己的 lease）。 */
    @Test
    fun botScreenOverRealSsh() {
        if (dir == null || key == null) {
            println("跳过：没有设置 HERMES_IT_SSH_DIR / HERMES_IT_KEY")
            return
        }
        val tunnel = com.hermes.mobile.data.ssh.SshTunnel(
            "127.0.0.1", sshPort, user, com.hermes.mobile.data.ssh.SshAuth.Key(File(dir, "user_ed25519").readText()), 8642, null, {},
        )
        try {
            tunnel.connect()
            val admin = com.hermes.mobile.data.remote.HermesAdmin(tunnel, null)
            runBlocking {
                var status = admin.screenStatus()
                println("屏幕：$status")
                if (!status.installed) {
                    println("跳过：主机上没装 Bot 屏幕需要的软件")
                    return@runBlocking
                }
                if (!status.running) status = admin.startScreen()
                assertTrue(status.running)

                // 画面流：先读到 VNC 的版本握手
                val stream = admin.openScreen()
                try {
                    val hello = ByteArray(12)
                    var n = 0
                    while (n < 12) {
                        val r = stream.output.read(hello, n, 12 - n)
                        if (r < 0) break
                        n += r
                    }
                    val got = String(hello, 0, n)
                    // errorText() 要等通道关掉才返回，只在失败时读
                    if (got != "RFB 003.008\n") {
                        stream.input.close()
                        kotlin.test.fail("没读到 VNC 握手：「$got」，错误输出：" + stream.errorText())
                    }
                } finally {
                    stream.close()
                }

                // 接管：Hermes 记下是我；交还后回到 bot
                val me = "hermes-mobile-it-" + System.nanoTime()
                val taken = admin.takeScreen(me, "IT 测试")
                assertTrue(taken.heldBy(me), taken.toString())
                assertTrue(admin.screenStatus().lease.heldBy(me))
                val other = admin.handBackScreen("someone-else")
                assertTrue(other.heldBy(me), "别人不能替我交还")
                val back = admin.handBackScreen(me)
                assertFalse(back.humanHolds, back.toString())
                // 强制交还
                admin.takeScreen(me)
                assertFalse(admin.handBackScreen(null).humanHolds)
            }
        } finally {
            tunnel.close()
        }
    }

    /** Bot：列出所有 bot（Profile），给测试 bot 开通手机访问（multiplex + 它自己的 key）。需要测试 Hermes 里有 testbot。 */
    @Test
    fun botsOverRealSsh() {
        if (dir == null || key == null) {
            println("跳过：没有设置 HERMES_IT_SSH_DIR / HERMES_IT_KEY")
            return
        }
        val tunnel = com.hermes.mobile.data.ssh.SshTunnel(
            "127.0.0.1", sshPort, user, com.hermes.mobile.data.ssh.SshAuth.Key(File(dir, "user_ed25519").readText()), 8642, null, {},
        )
        try {
            tunnel.connect()
            val admin = com.hermes.mobile.data.remote.HermesAdmin(tunnel, null)
            runBlocking {
                val roster = admin.bots()
                println("bots：" + roster.bots.map { "${it.name}(${it.label}, key=${it.hasKey}, canonical=${it.canonical?.id}, last=${it.last?.title})" } + " multiplex=" + roster.multiplex)
                val def = roster.bots.first { it.isDefault }
                assertEquals("default", def.name)
                assertTrue(def.hasKey, "默认 bot 用连接本身的 key")
                val test = roster.bots.firstOrNull { it.name == "testbot" }
                if (test == null) {
                    println("跳过后半段：测试 Hermes 里没有 testbot")
                    return@runBlocking
                }
                assertEquals("A test bot for Hermes Mobile", test.description)
                val access = admin.enableBotAccess("testbot")
                assertTrue(access.key.length >= 32)
                assertEquals(access.key, admin.botKey("testbot"), "开通后读到的就是这个 key")
                val again = admin.enableBotAccess("testbot")
                assertEquals(access.key, again.key, "已有 key 不重新生成")
                assertTrue(again.changed.isEmpty(), "第二次什么都不用改：${again.changed}")
                val after = admin.bots()
                assertTrue(after.bots.first { it.name == "testbot" }.hasKey)
                assertEquals(true, after.multiplex)
                assertTrue(runCatching { admin.botKey("../etc") }.exceptionOrNull() is com.hermes.mobile.data.remote.AdminException, "怪名字不进 shell")
            }
        } finally {
            tunnel.close()
        }
    }

    /** 真实 Hermes：新建 bot（带头像和颜色）、读它的设置、改名字 / 性格 / 去掉头像、选不存在的模型报错、删除。 */
    @Test
    fun createEditDeleteBotOverRealSsh() {
        if (dir == null || key == null) {
            println("跳过：没有设置 HERMES_IT_SSH_DIR / HERMES_IT_KEY")
            return
        }
        val tunnel = com.hermes.mobile.data.ssh.SshTunnel(
            "127.0.0.1", sshPort, user, com.hermes.mobile.data.ssh.SshAuth.Key(File(dir, "user_ed25519").readText()), 8642, null, {},
        )
        val name = "itbot"
        // 8x8 的红色 PNG
        val png = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAEklEQVR4nGP4z8CAFWEXHbQSACj/P8Fu7N9hAAAAAElFTkSuQmCC"
        try {
            tunnel.connect()
            val admin = com.hermes.mobile.data.remote.HermesAdmin(tunnel, null)
            runBlocking {
                if (admin.bots().bots.any { it.name == name }) admin.deleteBot(name)
                val title = "IT \u673a\u5668\u4eba" // “IT 机器人”
                val saved = admin.createBot(
                    com.hermes.mobile.data.model.BotEdit(name, title = title, description = "made by the IT", color = "#E5484D", avatar = png),
                )
                println("新建：$saved")
                assertEquals(name, (saved as com.hermes.mobile.data.model.BotSaved.Done).name)
                val bot = admin.bots().bots.first { it.name == name }
                assertEquals(title, bot.label, "名字（title）就是显示的名字")
                assertEquals("#E5484D", bot.color)
                assertEquals("made by the IT", bot.description)
                assertNotNull(bot.avatarPath, "头像存进了 profile 的 assets")
                assertTrue(bot.avatarVersion > 0)

                val details = admin.describeBot(name)
                println("设置：${details.provider} / ${details.model}，性格前 80 字：${details.soul.take(80)}")
                assertTrue(title in details.soul, "没写性格时按名字自动写了一份")
                assertTrue(details.model.isNotBlank(), "模型从默认 bot 复制过来了")

                admin.configureBot(
                    com.hermes.mobile.data.model.BotEdit(name, title = "Renamed", soul = "You are Renamed.", description = "changed", clearAvatar = true),
                )
                val after = admin.bots().bots.first { it.name == name }
                assertEquals("Renamed", after.label)
                assertEquals("changed", after.description)
                assertEquals(null, after.avatarPath, "头像去掉了")
                assertTrue(admin.describeBot(name).soul.startsWith("You are Renamed."))

                val bad = runCatching {
                    admin.configureBot(com.hermes.mobile.data.model.BotEdit(name, provider = "no-such-provider", model = "nope"))
                }.exceptionOrNull()
                println("坏模型：${bad?.message}")
                assertTrue(bad is com.hermes.mobile.data.remote.AdminException, "选了不存在的 provider 要报错")

                admin.deleteBot(name)
                assertTrue(admin.bots().bots.none { it.name == name }, "删掉了")
                assertTrue(runCatching { admin.deleteBot("default") }.isFailure, "默认 bot 不能删")
            }
        } finally {
            runCatching { runBlocking { com.hermes.mobile.data.remote.HermesAdmin(tunnel, null).deleteBot(name) } }
            tunnel.close()
        }
    }

    /** 真实 Hermes：经 SSH 上的桥连 gateway 的群聊（hosted room）：建群（默认 bot + testbot）、发消息、两个 bot 都回答、改名、解散。 */
    @Test
    fun groupChatOverRealSsh() {
        if (dir == null || key == null) {
            println("跳过：没有设置 HERMES_IT_SSH_DIR / HERMES_IT_KEY")
            return
        }
        val tunnel = com.hermes.mobile.data.ssh.SshTunnel(
            "127.0.0.1", sshPort, user, com.hermes.mobile.data.ssh.SshAuth.Key(File(dir, "user_ed25519").readText()), 8642, null, {},
        )
        try {
            tunnel.connect()
            val admin = com.hermes.mobile.data.remote.HermesAdmin(tunnel, null)
            val rooms = com.hermes.mobile.data.remote.RoomClient { admin.openRooms() }
            runBlocking {
                if (admin.bots().bots.none { it.name == "testbot" }) {
                    println("跳过：测试 Hermes 里没有 testbot")
                    return@runBlocking
                }
                val before = rooms.rooms().size
                val room = rooms.create(
                    "IT group",
                    listOf(
                        com.hermes.mobile.data.model.RoomMember("default", "default", "hermes", "Hermes"),
                        com.hermes.mobile.data.model.RoomMember("testbot", "testbot", "testbot", "Testbot"),
                    ),
                )
                try {
                    assertEquals(before + 1, rooms.rooms().size, "列表里多了这个群")
                    assertEquals(listOf("hermes", "testbot"), room.members.map { it.handle })
                    rooms.send(room.id, "hello both of you")
                    val events = mutableListOf<com.hermes.mobile.data.model.RoomEvent>()
                    withTimeout(120_000) {
                        while (events.none { it.kind == "room.activity" }) {
                            val log = rooms.log(room.id, events.lastOrNull()?.seq ?: 0)
                            events += log.events
                            if (log.events.isEmpty()) kotlinx.coroutines.delay(1500)
                        }
                    }
                    events.forEach { println("${it.seq} ${it.kind} ${it.actorId}: ${it.text.take(60)}") }
                    val user = events.first { it.kind == "message.user" }
                    assertTrue(user.fromUser)
                    assertEquals("hello both of you", user.text)
                    val speakers = events.filter { it.kind == "message.member" }.map { it.actorProfile }.toSet()
                    assertEquals(setOf("default", "testbot"), speakers, "没 @ 谁时两个 bot 都回答")
                    val status = rooms.status(room.id)
                    assertTrue(status.driverRunning)
                    assertFalse(status.working, "讨论结束了")
                    rooms.rename(room.id, "IT group 2")
                    assertEquals("IT group 2", rooms.status(room.id).room.name)
                    // 只读新记录
                    assertTrue(rooms.log(room.id, events.last().seq).events.none { it.isMessage })
                } finally {
                    rooms.disband(room.id)
                }
                assertTrue(rooms.rooms().none { it.id == room.id }, "解散后列表里没有了")
                val bad = runCatching { rooms.status("no-such-room") }.exceptionOrNull()
                println("不存在的群：${bad?.message}")
                assertTrue(bad is com.hermes.mobile.data.remote.AdminException)
            }
            rooms.close()
        } finally {
            tunnel.close()
        }
    }

    /** 真实 Hermes：换成 testbot 聊天（它自己的 key、/p/testbot），在它的 Bot Chat 里收到回复，再换回默认 bot。 */
    @Test
    fun chatWithAnotherBotOverRealSsh() {
        if (dir == null || key == null) {
            println("跳过：没有设置 HERMES_IT_SSH_DIR / HERMES_IT_KEY")
            return
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = RemoteHermesRepository(scope, MemoryLocalStore(), FakeSecretBox())
            val profile = ConnectionProfile(
                id = "os", name = "OpenSSH", type = ConnectionType.Ssh, host = "127.0.0.1", port = 8642,
                sshUser = user, sshPort = sshPort, sshAuth = SshAuthMethod.Key,
            )
            repo.saveConnection(profile, key, File(dir, "user_ed25519").readText())
            runBlocking { withTimeout(30_000) { repo.connectionState.first { it is ConnectionState.Connected } } }
            runBlocking { withTimeout(30_000) { repo.bots.first { it != null } } }
            if (repo.bots.value!!.bots.none { it.name == "testbot" }) {
                println("跳过：测试 Hermes 里没有 testbot")
                return
            }
            assertEquals("default", repo.activeBot.value)
            val switched = runBlocking { repo.switchBot("testbot") }
            println("换到 testbot：$switched")
            assertEquals(com.hermes.mobile.data.model.BotSwitch.Done, switched)
            assertEquals("testbot", repo.activeBot.value)

            val chat = assertNotNull(repo.openBotChat())
            repo.sendMessage(chat, "你好，testbot", emptyList())
            val items = runBlocking {
                withTimeout(60_000) {
                    repo.chatItems(chat).first { list -> list.any { it is ChatItem.Message && it.role == Role.Assistant && !it.id.startsWith("reply-") } }
                }
            }
            println("testbot 回复：" + items.filterIsInstance<ChatItem.Message>().last { it.role == Role.Assistant }.text.take(120))
            val real = runBlocking { repo.session(chat).first() }!!
            assertEquals("Bot Chat", real.title, "固定对话的标题是 Bot Chat（桌面版按它认）")
            // 这条对话存在 testbot 自己的库里
            runBlocking { repo.refreshBots() }
            assertEquals(real.id, repo.bots.value!!.bots.first { it.name == "testbot" }.canonical?.id)
            assertTrue(File("/home/user/ref/hermes-home/profiles/testbot/state.db").exists())

            // 换回默认 bot：会话列表里没有 testbot 的对话
            assertEquals(com.hermes.mobile.data.model.BotSwitch.Done, runBlocking { repo.switchBot("default") })
            runBlocking { withTimeout(10_000) { repo.sessions.first { list -> list.isNotEmpty() } } }
            assertTrue(repo.sessions.value.none { it.id == real.id }, "默认 bot 的列表里不该有 testbot 的对话")
            // 再换回去时，它的 Bot Chat 还是同一个
            assertEquals(com.hermes.mobile.data.model.BotSwitch.Done, runBlocking { repo.switchBot("testbot") })
            assertEquals(real.id, repo.openBotChat())
            runBlocking { repo.switchBot("default") }
        } finally {
            scope.cancel()
        }
    }
}

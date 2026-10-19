package com.hermes.mobile.data.remote

import com.hermes.mobile.data.model.McpServer
import com.hermes.mobile.data.model.MemoryTarget
import com.hermes.mobile.data.ssh.RemoteShell
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 管理功能发出的命令和对输出的解析（服务器换成假的 shell）。 */
class HermesAdminTest {
    private class FakeShell : RemoteShell {
        val commands = mutableListOf<String>()
        val files = mutableMapOf<String, String>()
        var mcpJson: String? = null
        var configCheckOutput = "\"x\""

        override fun exec(command: String, timeoutSeconds: Long): Pair<Int, String> {
            commands += command
            return when {
                "config path" in command -> 0 to "\u001B[2m\u001B[0m/home/u/.hermes/config.yaml\n"
                "config get memory --json" in command -> 0 to """{"memory_char_limit": 100, "user_char_limit": 50, "memory_enabled": true, "user_profile_enabled": false}"""
                "config get mcp_servers --json" in command -> 0 to (mcpJson ?: "Config key not set: mcp_servers")
                "HERMES_HOME=" in command -> 0 to configCheckOutput
                "mv -f " in command -> {
                    val (from, to) = Regex("mv -f '([^']+)' '([^']+)'").find(command)!!.destructured
                    files[to] = files.remove(from)!!
                    0 to ""
                }
                command.startsWith("rm -f ") -> {
                    files.remove(Regex("'([^']+)'").find(command)!!.groupValues[1])
                    0 to ""
                }
                "nohermes" in command -> 127 to "bash: hermes: command not found"
                else -> 0 to "✓ ok"
            }
        }

        override fun readFile(path: String) = files[path]?.toByteArray()

        override fun writeFile(path: String, bytes: ByteArray) {
            files[path] = bytes.toString(Charsets.UTF_8)
        }
    }

    @Test
    fun memoryIsSplitAndLimitsComeFromConfig() = runBlocking<Unit> {
        val shell = FakeShell()
        shell.files["/home/u/.hermes/memories/USER.md"] = "叫小张\n§\n住杭州\n"
        val books = HermesAdmin(shell, null).memory()
        assertEquals(listOf("叫小张", "住杭州"), books.first { it.target == MemoryTarget.User }.entries)
        assertEquals(50, books.first { it.target == MemoryTarget.User }.charLimit)
        assertEquals(false, books.first { it.target == MemoryTarget.User }.enabled)
        assertEquals(emptyList(), books.first { it.target == MemoryTarget.Notes }.entries, "文件不存在就是空的")
    }

    @Test
    fun memoryIsWrittenAtomicallyAndRespectsTheLimit() = runBlocking<Unit> {
        val shell = FakeShell()
        val admin = HermesAdmin(shell, null)
        admin.saveMemory(MemoryTarget.User, listOf(" 叫小张 ", "", "住杭州"))
        assertEquals("叫小张\n§\n住杭州", shell.files["/home/u/.hermes/memories/USER.md"])
        assertTrue(shell.commands.any { it.startsWith("mkdir -p '/home/u/.hermes/memories'") })
        val e = assertFailsWith<AdminException> { admin.saveMemory(MemoryTarget.User, listOf("很长".repeat(30))) }
        assertTrue("上限 50" in e.message!!, e.message)
    }

    @Test
    fun mcpServersAreReadAndWrittenThroughConfigCommands() = runBlocking<Unit> {
        val shell = FakeShell()
        val admin = HermesAdmin(shell, "work")
        assertEquals(emptyList(), admin.mcpServers())
        shell.mcpJson = "Loading…\n" + """{"fs": {"command": "npx", "args": ["-y", "pkg"], "enabled": false}, "web": {"url": "https://x/mcp"}}"""
        val servers = admin.mcpServers()
        assertEquals(listOf(McpServer("fs", command = "npx", args = listOf("-y", "pkg"), enabled = false), McpServer("web", url = "https://x/mcp")), servers)

        shell.commands.clear()
        admin.saveMcpServer(McpServer("files", command = "npx", args = listOf("-y", "it's")), previousName = "fs")
        val cmd = shell.commands.single()
        assertTrue(cmd.startsWith("hermes -p work config unset mcp_servers.fs && hermes -p work config unset mcp_servers.files && "), cmd)
        assertTrue("config set mcp_servers.files.command 'npx'" in cmd, cmd)
        assertTrue("""config set mcp_servers.files.args '["-y","it'\''s"]'""" in cmd, "参数按 JSON 列表写，引号转义：$cmd")
        assertTrue(cmd.endsWith("config set mcp_servers.files.enabled true"), cmd)

        assertFailsWith<AdminException> { admin.saveMcpServer(McpServer("bad name; rm -rf /", url = "x")) }
        assertFailsWith<AdminException> { admin.removeMcpServer("x; reboot") }
    }

    @Test
    fun brokenConfigIsRejectedWithoutTouchingTheFile() = runBlocking<Unit> {
        val shell = FakeShell()
        shell.files["/home/u/.hermes/config.yaml"] = "model: a\n"
        val admin = HermesAdmin(shell, null)
        shell.configCheckOutput = "Your settings file (/tmp/x/config.yaml) has a formatting error at line 3. …\n    Details: expected ',' or ']'"
        val e = assertFailsWith<AdminException> { admin.saveConfig("model: [a\n") }
        assertTrue("第 3 行" in e.message!! && "expected" in e.message!!, e.message)
        assertEquals("model: a\n", shell.files["/home/u/.hermes/config.yaml"], "原文件不动")

        shell.configCheckOutput = "\"a\""
        admin.saveConfig("model: b\n")
        assertEquals("model: b\n", shell.files["/home/u/.hermes/config.yaml"])
        assertTrue(shell.commands.any { "config.yaml.mobile-backup" in it }, "保存前备份")
    }

    @Test
    fun missingHermesCommandIsExplained() = runBlocking<Unit> {
        val shell = object : RemoteShell {
            override fun exec(command: String, timeoutSeconds: Long) = 127 to "bash: hermes: command not found"
            override fun readFile(path: String): ByteArray? = null
            override fun writeFile(path: String, bytes: ByteArray) = Unit
        }
        val e = assertFailsWith<AdminException> { HermesAdmin(shell, null).memory() }
        assertTrue("找不到 hermes" in e.message!!, e.message)
    }
    /** 在本机用真的 bash + python3 跑分组脚本（模拟 SSH 那头）。 */
    private class LocalShell(private val home: java.io.File) : RemoteShell {
        override fun exec(command: String, timeoutSeconds: Long): Pair<Int, String> {
            if ("config path" in command) return 0 to "${home.path}/config.yaml\n"
            val p = ProcessBuilder("bash", "-c", command).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            return p.waitFor() to out
        }
        override fun readFile(path: String): ByteArray? = java.io.File(path).takeIf { it.exists() }?.readBytes()
        override fun writeFile(path: String, bytes: ByteArray) = java.io.File(path).writeBytes(bytes)
    }

    private fun sql(db: java.io.File, statements: String) {
        // 中文经 stdin 按 UTF-8 传（测试 JVM 的命令行参数编码不一定是 UTF-8）
        val pb = ProcessBuilder("python3", "-c", "import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); c.executescript(sys.stdin.buffer.read().decode('utf-8')); c.commit()", db.path)
            .redirectErrorStream(true)
        val p = pb.start()
        p.outputStream.use { it.write(statements.toByteArray(Charsets.UTF_8)) }
        assertEquals(0, p.waitFor(), p.inputStream.readBytes().toString(Charsets.UTF_8))
    }

    @Test
    fun sessionsAreGroupedByProjectThenFolder() = runBlocking<Unit> {
        val home = kotlin.io.path.createTempDirectory("hermes-home").toFile()
        sql(
            java.io.File(home, "projects.db"),
            """
            create table projects (id text primary key, slug text, name text, primary_path text, created_at integer, archived integer default 0);
            create table project_folders (project_id text, path text, label text, is_primary integer, added_at integer);
            insert into projects values ('p1', 'downloads', 'downloads', '/home/u/Downloads', 1, 0);
            insert into projects values ('p2', 'site', '网站', '/home/u/site', 2, 0);
            insert into projects values ('p3', 'old', '旧项目', '/home/u/old', 3, 1);
            insert into project_folders values ('p1', '/home/u/Downloads', null, 1, 1);
            insert into project_folders values ('p2', '/home/u/site', null, 1, 1);
            insert into project_folders values ('p2', '/home/u/Downloads/site-assets', null, 0, 1);
            insert into project_folders values ('p3', '/home/u/old', null, 1, 1);
            """.trimIndent(),
        )
        sql(
            java.io.File(home, "state.db"),
            """
            create table sessions (id text primary key, cwd text, git_repo_root text, title text);
            insert into sessions values ('a', '/home/u/Downloads', null, 'x');
            insert into sessions values ('b', '/home/u/Downloads/site-assets/img', null, 'x');
            insert into sessions values ('c', '/home/u/code/app/src', '/home/u/code/app', 'x');
            insert into sessions values ('d', null, null, 'x');
            insert into sessions values ('e', '/home/u/old/x', null, 'x');
            """.trimIndent(),
        )
        val grouping = HermesAdmin(LocalShell(home), null).sessionGroups()
        assertEquals("downloads", grouping.groupOf("a"))
        assertEquals("网站", grouping.groupOf("b"), "嵌套时最里面的项目优先")
        assertEquals("app", grouping.groupOf("c"), "不属于项目的按 git 仓库文件夹名")
        assertEquals("默认", grouping.groupOf("d"), "没有工作目录的是“默认”")
        assertEquals("x", grouping.groupOf("e"), "归档的项目不算，按文件夹名")
        assertEquals(listOf("downloads", "网站", "app", "x", "默认"), grouping.groups)
    }

    @Test
    fun noProjectsDbStillWorks() = runBlocking<Unit> {
        val home = kotlin.io.path.createTempDirectory("hermes-home2").toFile()
        assertEquals(listOf("默认"), HermesAdmin(LocalShell(home), null).sessionGroups().groups, "什么都没有时只有“默认”")
    }
}

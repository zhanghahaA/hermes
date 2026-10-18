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
}

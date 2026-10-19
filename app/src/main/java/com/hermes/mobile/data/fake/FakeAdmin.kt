package com.hermes.mobile.data.fake

import com.hermes.mobile.data.model.GatewayStatus
import com.hermes.mobile.data.model.McpServer
import com.hermes.mobile.data.model.MemoryBook
import com.hermes.mobile.data.model.MemoryTarget
import com.hermes.mobile.data.remote.AdminApi

/** 演示用的管理数据（界面预览、没连 Hermes 时）。 */
class FakeAdmin(private val onProjectCreated: (String, String) -> Unit = { _, _ -> }) : AdminApi {
    private var books = listOf(
        MemoryBook(
            MemoryTarget.User,
            listOf(
                "用户叫小林，在苏州，习惯用中文交流，回答尽量简短。",
                "家里的 NAS 是群晖 DS920+，IP 192.168.1.20，用户名 admin。",
                "早上 8 点前不要发通知。",
            ),
            1375,
        ),
        MemoryBook(
            MemoryTarget.Notes,
            listOf(
                "这台机器是 Android 手机上的 Termux，没有 sudo；装软件用 pkg install。",
                "备份脚本在 ~/scripts/backup.sh，每晚 2 点由定时任务运行。",
            ),
            2200,
        ),
    )
    private var servers = listOf(
        McpServer("filesystem", command = "npx", args = listOf("-y", "@modelcontextprotocol/server-filesystem", "/sdcard")),
        McpServer("home-assistant", url = "http://192.168.1.30:8123/mcp_server/sse"),
        McpServer("github", url = "https://api.githubcopilot.com/mcp/", enabled = false),
    )
    private var config = """
        model:
          default: deepseek-v4.1-flash
          provider: deepseek
        memory:
          memory_enabled: true
          user_profile_enabled: true
        platform_toolsets:
          api_server:
            - hermes-api-server
            - clarify
        mcp_servers:
          filesystem:
            command: npx
            args: ["-y", "@modelcontextprotocol/server-filesystem", "/sdcard"]
    """.trimIndent()

    override suspend fun memory() = books
    override suspend fun saveMemory(target: MemoryTarget, entries: List<String>) {
        books = books.map { if (it.target == target) it.copy(entries = entries) else it }
    }
    override suspend fun mcpServers() = servers
    override suspend fun saveMcpServer(server: McpServer, previousName: String?) {
        servers = servers.filterNot { it.name == server.name || it.name == previousName } + server
    }
    override suspend fun removeMcpServer(name: String) {
        servers = servers.filterNot { it.name == name }
    }
    override suspend fun setMcpEnabled(name: String, enabled: Boolean) {
        servers = servers.map { if (it.name == name) it.copy(enabled = enabled) else it }
    }
    override suspend fun testMcpServer(name: String) = "Auth: none\n✓ Connected (0.8s)\n✓ 11 tools: read_file, write_file, list_directory, …"
    override suspend fun readConfig() = config
    override suspend fun saveConfig(text: String) {
        config = text
    }
    override suspend fun logs(lines: Int) =
        "2026-10-18 08:00:02 INFO cron: running job 每日简报\n2026-10-18 08:00:09 INFO cron: job 每日简报 finished (ok)\n" +
            "2026-10-18 09:12:44 INFO api_server: run run_4f2 started (session api_1792)\n2026-10-18 09:12:51 INFO api_server: run run_4f2 completed"

    private val tree = mapOf(
        "/home/me" to listOf("Downloads", "homelab", "notes", "projects"),
        "/home/me/projects" to listOf("hermes-mobile", "blog", "scripts"),
        "/home/me/notes" to listOf("2025", "2026"),
    )

    override suspend fun listFolders(path: String?): Pair<String, List<String>> {
        val dir = path?.trimEnd('/')?.ifEmpty { "/" } ?: "/home/me"
        return dir to tree[dir].orEmpty()
    }

    override suspend fun createProject(name: String, folder: String) = onProjectCreated(name, folder)

    // ---- Bot 屏幕（演示：一直开着，接管谁就记谁） ----

    private var screenHolder: String? = null

    private fun lease() = com.hermes.mobile.data.model.ScreenLease(
        humanHolds = screenHolder != null,
        viewerHash = screenHolder?.let(com.hermes.mobile.data.model.ScreenLease::hashViewer),
        reason = "",
    )

    override suspend fun screenStatus() = com.hermes.mobile.data.model.BotScreenStatus(
        supported = true, installed = true, running = true, geometry = "1440x900", installCommand = null, blocker = null, lease = lease(),
    )

    override suspend fun takeScreen(viewerId: String, reason: String): com.hermes.mobile.data.model.ScreenLease {
        screenHolder = viewerId
        return lease()
    }

    override suspend fun handBackScreen(viewerId: String?): com.hermes.mobile.data.model.ScreenLease {
        if (viewerId == null || viewerId == screenHolder) screenHolder = null
        return lease()
    }

    /** 演示用的“画面流”：什么都不发，也不会断。 */
    override suspend fun openScreen(): com.hermes.mobile.data.ssh.RemoteStream = object : com.hermes.mobile.data.ssh.RemoteStream {
        private val pipe = java.io.PipedOutputStream()
        override val output: java.io.InputStream = java.io.PipedInputStream(pipe)
        override val input: java.io.OutputStream = object : java.io.OutputStream() {
            override fun write(b: Int) = Unit
        }
        override fun errorText() = ""
        override val exitCode: Int? = null
        override fun close() = pipe.close()
    }

    companion object {
        val status = GatewayStatus(
            ok = true,
            state = "running",
            checks = listOf(
                GatewayStatus.Check("state_db", true, null),
                GatewayStatus.Check("config", true, null),
                GatewayStatus.Check("model", true, null),
                GatewayStatus.Check("disk", true, "已用 41%"),
                GatewayStatus.Check("gateway", true, "running"),
            ),
            platforms = listOf(
                GatewayStatus.Platform("api_server", "connected", null),
                GatewayStatus.Platform("telegram", "connected", null),
                GatewayStatus.Platform("discord", "retrying", "Invalid token"),
            ),
            activeAgents = 1,
            requestsToday = 42,
            messagesToday = 18,
            tokensToday = 186_400,
            diskUsedPercent = 41.0,
        )
    }
}

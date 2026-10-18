package com.hermes.mobile.data.remote

import com.hermes.mobile.data.model.McpServer
import com.hermes.mobile.data.model.MemoryBook
import com.hermes.mobile.data.model.MemoryTarget
import com.hermes.mobile.data.ssh.RemoteShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** 管理功能出错：[message] 是给人看的一句话。 */
class AdminException(message: String) : Exception(message)

/** API 管不到、要经 SSH 在服务器上做的事。 */
interface AdminApi {
    suspend fun memory(): List<MemoryBook>
    suspend fun saveMemory(target: MemoryTarget, entries: List<String>)
    suspend fun mcpServers(): List<McpServer>
    /** 新建或修改；[previousName] 是改名前的名字。 */
    suspend fun saveMcpServer(server: McpServer, previousName: String? = null)
    suspend fun removeMcpServer(name: String)
    suspend fun setMcpEnabled(name: String, enabled: Boolean)
    /** `hermes mcp test` 的输出。 */
    suspend fun testMcpServer(name: String): String
    suspend fun readConfig(): String
    /** 先在临时目录里让 Hermes 读一遍，格式没问题才替换（原文件备份为 config.yaml.mobile-backup）。 */
    suspend fun saveConfig(text: String)
    suspend fun logs(lines: Int = 200): String
}

/**
 * 经 SSH 管理 Hermes。尽量用 `hermes` 自己的命令（config get/set/unset、logs、mcp test），
 * 只有记忆和 config.yaml 原文直接读写文件。
 */
class HermesAdmin(private val shell: RemoteShell, profile: String?) : AdminApi {
    private val hermes = "hermes" + (profile?.trim()?.replace(Regex("[^A-Za-z0-9_.-]"), "")?.takeIf { it.isNotEmpty() }?.let { " -p $it" } ?: "")

    @Volatile private var home: String? = null

    private suspend fun run(command: String, timeoutSeconds: Long = 60, tolerate: (String) -> Boolean = { false }): String = withContext(Dispatchers.IO) {
        val (code, output) = try {
            shell.exec(command, timeoutSeconds)
        } catch (e: java.io.IOException) {
            throw AdminException("SSH 执行失败：${e.message}")
        }
        val clean = output.stripAnsi()
        if (code == 127 || "command not found" in clean) throw AdminException("服务器上找不到 hermes 命令（SSH 登录后的 PATH 里没有它）")
        if (code != 0 && !tolerate(clean)) throw AdminException(clean.lines().lastOrNull { it.isNotBlank() }?.trim()?.take(300) ?: "命令失败（退出码 $code）")
        clean
    }

    /** Hermes 的目录（config.yaml 所在的地方），用 `hermes config path` 问出来，Profile 也对。 */
    private suspend fun home(): String = home ?: run("$hermes config path").lines()
        .map { it.trim() }.lastOrNull { it.startsWith("/") && it.endsWith("config.yaml") }
        ?.removeSuffix("/config.yaml")?.also { home = it }
        ?: throw AdminException("问不出 Hermes 的目录（hermes config path 没有输出路径）")

    private suspend fun read(path: String): String? = withContext(Dispatchers.IO) {
        try {
            shell.readFile(path)?.toString(Charsets.UTF_8)
        } catch (e: java.io.IOException) {
            throw AdminException("读不了 $path：${e.message}")
        }
    }

    private suspend fun write(path: String, text: String) = withContext(Dispatchers.IO) {
        try {
            shell.writeFile(path, text.toByteArray(Charsets.UTF_8))
        } catch (e: java.io.IOException) {
            throw AdminException("写不了 $path：${e.message}")
        }
    }

    /** `hermes config get <key> --json`；没设返回 null。 */
    private suspend fun configJson(key: String): kotlinx.serialization.json.JsonElement? {
        // 没设过的键：hermes 会说 “Config key not set”，退出码不是 0
        val out = run("$hermes config get $key --json") { "Config key not set" in it }
        if ("Config key not set" in out) return null
        val start = out.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        return runCatching { HermesJson.parseToJsonElement(out.substring(start).trim()) }.getOrNull()
    }

    // ---- 记忆 ----

    override suspend fun memory(): List<MemoryBook> {
        val dir = home() + "/memories"
        val cfg = configJson("memory") as? JsonObject
        fun int(key: String, default: Int) = (cfg?.get(key) as? JsonPrimitive)?.intOrNull ?: default
        fun bool(key: String) = (cfg?.get(key) as? JsonPrimitive)?.booleanOrNull ?: true
        return listOf(
            MemoryBook(MemoryTarget.User, splitMemory(read("$dir/${MemoryTarget.User.fileName}")), int("user_char_limit", 1375), bool("user_profile_enabled")),
            MemoryBook(MemoryTarget.Notes, splitMemory(read("$dir/${MemoryTarget.Notes.fileName}")), int("memory_char_limit", 2200), bool("memory_enabled")),
        )
    }

    override suspend fun saveMemory(target: MemoryTarget, entries: List<String>) {
        val clean = entries.map { it.trim() }.filter { it.isNotEmpty() }
        val book = memory().first { it.target == target }
        val text = clean.joinToString(MemoryBook.MemoryDelimiter)
        if (text.length > book.charLimit) throw AdminException("${target.label}一共 ${text.length} 字，超过了 Hermes 的上限 ${book.charLimit} 字")
        val dir = home() + "/memories"
        run("mkdir -p ${q(dir)}")
        val file = "$dir/${target.fileName}"
        write("$file.mobile-tmp", text)
        run("mv -f ${q("$file.mobile-tmp")} ${q(file)}")
    }

    // ---- MCP ----

    override suspend fun mcpServers(): List<McpServer> {
        val obj = configJson("mcp_servers") as? JsonObject ?: return emptyList()
        return obj.mapNotNull { (name, value) ->
            val server = value as? JsonObject ?: return@mapNotNull null
            McpServer(
                name = name,
                url = (server["url"] as? JsonPrimitive)?.contentOrNull,
                command = (server["command"] as? JsonPrimitive)?.contentOrNull,
                args = (server["args"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                enabled = (server["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
            )
        }.sortedBy { it.name.lowercase() }
    }

    override suspend fun saveMcpServer(server: McpServer, previousName: String?) {
        val name = server.name.trim()
        if (!McpName.matches(name)) throw AdminException("名字只能用英文字母、数字、- 和 _")
        if (server.url.isNullOrBlank() && server.command.isNullOrBlank()) throw AdminException("填一个网址，或者一条启动命令")
        val key = "mcp_servers.$name"
        val steps = buildList {
            if (previousName != null && previousName != name) add("$hermes config unset mcp_servers.${safeName(previousName)}")
            // 先清掉旧的字段（比如从命令改成网址），再一项项写
            if (previousName != null) add("$hermes config unset $key")
            if (!server.url.isNullOrBlank()) {
                add("$hermes config set $key.url ${q(server.url.trim())}")
            } else {
                add("$hermes config set $key.command ${q(server.command!!.trim())}")
                if (server.args.isNotEmpty()) add("$hermes config set $key.args ${q(JsonArray(server.args.map(::JsonPrimitive)).toString())}")
            }
            add("$hermes config set $key.enabled ${server.enabled}")
        }
        // 一次 SSH 执行，任何一步失败就停
        run(steps.joinToString(" && "), timeoutSeconds = 120)
    }

    override suspend fun removeMcpServer(name: String) {
        run("$hermes config unset mcp_servers.${safeName(name)}")
    }

    override suspend fun setMcpEnabled(name: String, enabled: Boolean) {
        run("$hermes config set mcp_servers.${safeName(name)}.enabled $enabled")
    }

    override suspend fun testMcpServer(name: String): String = withContext(Dispatchers.IO) {
        // 连接失败时 hermes 也会正常退出，输出里有结果；stdin 关掉，避免它停下来问问题
        val (_, output) = try {
            shell.exec("$hermes mcp test ${safeName(name)} < /dev/null", 90)
        } catch (e: java.io.IOException) {
            throw AdminException("SSH 执行失败：${e.message}")
        }
        output.stripAnsi().lines().map { it.trimEnd() }.filter { it.isNotBlank() }.joinToString("\n").trim()
    }

    // ---- 配置文件 ----

    override suspend fun readConfig(): String = read(home() + "/config.yaml").orEmpty()

    override suspend fun saveConfig(text: String) {
        val dir = home()
        val candidate = "$dir/.config.yaml.mobile"
        write(candidate, text)
        // 在临时目录里让 Hermes 读一遍：格式有错它会说 “formatting error”（退出码还是 0）
        val check = run(
            "T=$(mktemp -d) && cp ${q(candidate)} \"\$T/config.yaml\" && HERMES_HOME=\"\$T\" hermes config get model --json; " +
                "s=\$?; rm -rf \"\$T\"; exit \$s",
            timeoutSeconds = 90,
        )
        if ("formatting error" in check) {
            run("rm -f ${q(candidate)}")
            val details = check.lines().firstOrNull { "Details:" in it }?.substringAfter("Details:")?.trim()
            val line = Regex("at line (\\d+)").find(check)?.groupValues?.get(1)
            throw AdminException("格式不对" + (line?.let { "（第 $it 行）" } ?: "") + (details?.let { "：$it" } ?: "") + "，没有保存")
        }
        run("cp -f ${q("$dir/config.yaml")} ${q("$dir/config.yaml.mobile-backup")} 2>/dev/null; mv -f ${q(candidate)} ${q("$dir/config.yaml")}")
    }

    override suspend fun logs(lines: Int): String =
        run("$hermes logs -n ${lines.coerceIn(10, 2000)}", timeoutSeconds = 60).trimEnd()

    private fun safeName(name: String): String {
        if (!McpName.matches(name)) throw AdminException("名字不对：$name")
        return name
    }

    private companion object {
        val McpName = Regex("[A-Za-z0-9_-]+")
        val Ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

        fun String.stripAnsi() = replace(Ansi, "")

        /** 单引号包起来给 shell。 */
        fun q(text: String) = "'" + text.replace("'", "'\\''") + "'"

        fun splitMemory(text: String?): List<String> =
            text.orEmpty().split(MemoryBook.MemoryDelimiter).map { it.trim() }.filter { it.isNotEmpty() }
    }
}

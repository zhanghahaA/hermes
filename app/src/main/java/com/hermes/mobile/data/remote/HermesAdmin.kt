package com.hermes.mobile.data.remote

import com.hermes.mobile.data.model.Bot
import com.hermes.mobile.data.model.BotAccess
import com.hermes.mobile.data.model.BotDetails
import com.hermes.mobile.data.model.BotEdit
import com.hermes.mobile.data.model.BotSaved
import com.hermes.mobile.data.model.BotRoster
import com.hermes.mobile.data.model.BotScreenStatus
import com.hermes.mobile.data.model.BotSessionRef
import com.hermes.mobile.data.model.McpServer
import com.hermes.mobile.data.model.ScreenLease
import com.hermes.mobile.data.model.MemoryBook
import com.hermes.mobile.data.model.MemoryTarget
import com.hermes.mobile.data.model.SessionGrouping
import com.hermes.mobile.data.model.ArchivedRef
import com.hermes.mobile.data.model.ProjectInfo
import com.hermes.mobile.data.ssh.RemoteShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    suspend fun modelSettings(program: String, input: String): String = throw AdminException("当前连接不支持模型设置")
    suspend fun readConfig(): String
    /** 先在临时目录里让 Hermes 读一遍，格式没问题才替换（原文件备份为 config.yaml.mobile-backup）。 */
    suspend fun saveConfig(text: String)
    suspend fun logs(lines: Int = 200): String
    /** 会话分组（项目 / 工作目录）。 */
    suspend fun sessionGroups(): SessionGrouping = SessionGrouping()
    /** 新建 Hermes 项目（`hermes project create`），[folder] 是电脑上的文件夹。 */
    suspend fun createProject(name: String, folder: String): Unit = throw AdminException("不支持")
    /** 列出文件夹：返回绝对路径和下一级的子文件夹名。[path] 为 null 时从家目录开始。 */
    suspend fun listFolders(path: String?): Pair<String, List<String>> = throw AdminException("不支持")
    /** 把会话的工作目录改成 [folder]（桌面版拖到项目里就是这样）；null 表示移出项目。 */
    suspend fun moveSession(sessionId: String, folder: String?): Unit = throw AdminException("不支持")
    /** 在 [parent] 下新建文件夹 [name]，返回新文件夹的绝对路径。 */
    suspend fun makeFolder(parent: String, name: String): String = throw AdminException("不支持")
    /** 删除项目（只删项目本身；对话和文件夹都还在，和桌面版的删除一样）。 */
    suspend fun deleteProject(projectId: String): Unit = throw AdminException("不支持")
    suspend fun renameProject(projectId: String, name: String): Unit = throw AdminException("不支持")
    /** Hermes 的数据目录（项目、会话都存在这里）：手机和电脑看到的不一样时用来对照。 */
    suspend fun hermesHome(): String = throw AdminException("不支持")
    /** 所有 bot（Profile）。 */
    suspend fun bots(): BotRoster = throw AdminException("不支持")
    /** bot 自己的 API Key（profile 的 .env 里的 API_SERVER_KEY）；没有返回 null。 */
    suspend fun botKey(name: String): String? = throw AdminException("不支持")
    /** 开通手机访问：打开 multiplex_profiles、给 bot 生成自己的 API Key（已有就用原来的）。 */
    suspend fun enableBotAccess(name: String): BotAccess = throw AdminException("不支持")
    /** 重启 gateway（`hermes gateway restart`），返回输出。 */
    suspend fun restartGateway(): String = throw AdminException("不支持")
    /** 编辑 bot 用：描述、性格、模型。 */
    suspend fun describeBot(name: String): BotDetails = throw AdminException("不支持")
    /** 新建 bot（和桌面版一样：从默认 bot 复制模型、Key 等设置，不复制聊天平台的 bot token）。 */
    suspend fun createBot(edit: BotEdit): BotSaved = throw AdminException("不支持")
    suspend fun configureBot(edit: BotEdit): BotSaved = throw AdminException("不支持")
    /** 删除 bot：它的对话、记忆、技能都从电脑上删掉。默认 bot 不能删。 */
    suspend fun deleteBot(name: String): Unit = throw AdminException("不支持")
    /** 读服务器上的文件（头像等）。 */
    suspend fun readRemoteFile(path: String): ByteArray? = throw AdminException("不支持")
    /** Bot 屏幕的状态（`hermes computer-use screen status --json`）。 */
    suspend fun screenStatus(): BotScreenStatus = throw AdminException("不支持")
    suspend fun startScreen(): BotScreenStatus = throw AdminException("不支持")
    /** 接管屏幕：bot 的 computer_use 和浏览器工具会被拒绝，直到交还。 */
    suspend fun takeScreen(viewerId: String, reason: String = ""): ScreenLease = throw AdminException("不支持")
    /** 交还给 bot。[viewerId] 为 null 是强制交还（不管谁接管的）。 */
    suspend fun handBackScreen(viewerId: String?): ScreenLease = throw AdminException("不支持")
    /** 打开屏幕的 RFB（VNC）字节流。 */
    suspend fun openScreen(): com.hermes.mobile.data.ssh.RemoteStream = throw AdminException("不支持")
    /** 开一条到 gateway 群聊接口（groups.* JSON-RPC）的通道，一行请求一行回复，见 [RoomClient]。 */
    suspend fun openRooms(): com.hermes.mobile.data.ssh.RemoteStream = throw AdminException("不支持")
}

/**
 * 经 SSH 管理 Hermes。尽量用 `hermes` 自己的命令（config get/set/unset、logs、mcp test），
 * 只有记忆和 config.yaml 原文直接读写文件。
 */
class HermesAdmin(private val shell: RemoteShell, val profileName: String?) : AdminApi {
    /** 不带 Profile 参数的 hermes（--print-runtime-command 必须是第一个参数；Profile 已经由 HERMES_HOME 定了）。 */
    private val hermesBare = "hermes"
    private val hermes = "hermes" + (profileName?.trim()?.replace(Regex("[^A-Za-z0-9_.-]"), "")?.takeIf { it.isNotEmpty() }?.let { " -p $it" } ?: "")

    @Volatile private var home: String? = null

    private suspend fun run(command: String, timeoutSeconds: Long = 60, tolerate: (String) -> Boolean = { false }): String = withContext(Dispatchers.IO) {
        val (code, output) = try {
            shell.exec(command, timeoutSeconds)
        } catch (e: java.io.IOException) {
            throw AdminException("SSH 执行失败：${e.message}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // SSH 库偶尔抛别的异常（连接状态不对等）：当成普通的执行失败，不能让 App 崩掉
            throw AdminException("SSH 执行失败：${e.message ?: e.javaClass.simpleName}")
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AdminException("读不了 $path：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun write(path: String, text: String) = withContext(Dispatchers.IO) {
        try {
            shell.writeFile(path, text.toByteArray(Charsets.UTF_8))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AdminException("写不了 $path：${e.message ?: e.javaClass.simpleName}")
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

    override suspend fun sessionGroups(): SessionGrouping {
        val dir = home()
        val script = "python3 - ${q(dir)} <<'HERMES_MOBILE_PY'\n$GroupsScript\nHERMES_MOBILE_PY"
        val out = run(script, timeoutSeconds = 60)
        if ("command not found" in out) throw AdminException("服务器上没有 python3，读不了项目分组")
        val json = runCatching { HermesJson.parseToJsonElement(out.substring(out.indexOf('{')).trim()) as JsonObject }.getOrNull()
            ?: throw AdminException("读项目分组失败：${out.lines().lastOrNull { it.isNotBlank() }?.take(200)}")
        val projects = (json["projects"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { p -> (p["id"] as? JsonPrimitive)?.contentOrNull to (p["name"] as? JsonPrimitive)?.contentOrNull } }
            .filter { it.first != null && !it.second.isNullOrBlank() }
            .associate { it.first!! to it.second!! }
        val sessions = (json["sessions"] as? JsonObject).orEmpty()
        val folderNames = mutableSetOf<String>()
        val of = sessions.mapNotNull { (id, value) ->
            val v = value as? JsonObject ?: return@mapNotNull null
            val project = (v["project"] as? JsonPrimitive)?.contentOrNull?.let(projects::get)
            val folder = (v["folder"] as? JsonPrimitive)?.contentOrNull?.trimEnd('/', '\\')?.substringAfterLast('/')?.substringAfterLast('\\')
            val group = project ?: folder?.takeIf { it.isNotBlank() }?.also { folderNames += it } ?: return@mapNotNull null
            id to group
        }.toMap()
        val groups = projects.values.distinct() + (folderNames - projects.values.toSet()).sorted() + SessionGrouping.DefaultGroup
        val projectInfos = (json["projects"] as? JsonArray).orEmpty().mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            val id = (p["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val name = (p["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ProjectInfo(id, name, (p["path"] as? JsonPrimitive)?.contentOrNull)
        }
        val folders = sessions.mapNotNull { (id, value) ->
            ((value as? JsonObject)?.get("cwd") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { id to it }
        }.toMap()
        val archived = (json["archived"] as? JsonArray).orEmpty().mapNotNull { e ->
            val a = e as? JsonObject ?: return@mapNotNull null
            val id = (a["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val at = (a["at"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L
            ArchivedRef(id, (a["title"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: "未命名会话", at)
        }
        return SessionGrouping(groups.distinct(), of, projectInfos, folders, archived)
    }

    // ---- Bot 屏幕 ----

    override suspend fun screenStatus(): BotScreenStatus {
        // 屏幕没开时命令的退出码是 1，但照样输出 JSON
        val out = run("$hermes computer-use screen status --json", tolerate = { "{" in it })
        if ("invalid choice" in out || "{" !in out) throw AdminException("这台 Hermes 的版本还没有 Bot 屏幕（需要较新的版本）")
        val json = runCatching { HermesJson.parseToJsonElement(out.substring(out.indexOf('{'))) as JsonObject }.getOrNull()
            ?: throw AdminException("读不懂 Bot 屏幕的状态：${out.lines().lastOrNull { it.isNotBlank() }?.take(200)}")
        fun str(key: String) = (json[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        fun bool(key: String) = (json[key] as? JsonPrimitive)?.booleanOrNull == true
        return BotScreenStatus(
            supported = bool("supported"),
            installed = bool("installed"),
            running = bool("running"),
            geometry = str("geometry"),
            installCommand = str("install_command"),
            blocker = str("blocker"),
            lease = parseLease(json["lease"] as? JsonObject),
        )
    }

    override suspend fun startScreen(): BotScreenStatus {
        val out = run("$hermes computer-use screen start", timeoutSeconds = 120, tolerate = { true })
        val status = screenStatus()
        if (!status.running) throw AdminException(out.lines().lastOrNull { it.isNotBlank() }?.removePrefix("Bot Desktop:")?.trim()?.take(300) ?: "没能启动屏幕")
        return status
    }

    override suspend fun takeScreen(viewerId: String, reason: String): ScreenLease = lease("take", viewerId, reason)

    override suspend fun handBackScreen(viewerId: String?): ScreenLease = lease("give", viewerId.orEmpty(), "")

    private suspend fun lease(op: String, viewerId: String, reason: String): ScreenLease {
        val dir = home()
        val script = hermesPython(dir, LeaseScript, listOf(op, viewerId, reason))
        val out = run(script)
        val line = out.lines().lastOrNull { it.startsWith("LEASE") } ?: throw AdminException("接管失败：${out.lines().lastOrNull { it.isNotBlank() }?.take(200)}")
        return parseLease(HermesJson.parseToJsonElement(line.removePrefix("LEASE")) as? JsonObject)
    }

    private fun parseLease(json: JsonObject?): ScreenLease = ScreenLease(
        humanHolds = (json?.get("holder") as? JsonPrimitive)?.contentOrNull == "human",
        viewerHash = (json?.get("viewer_hash") as? JsonPrimitive)?.contentOrNull,
        reason = (json?.get("reason") as? JsonPrimitive)?.contentOrNull.orEmpty(),
    )

    override suspend fun openScreen(): com.hermes.mobile.data.ssh.RemoteStream = withContext(Dispatchers.IO) {
        val dir = home()
        // 桥接脚本从 -c 传进去：stdin 要留给画面数据
        val command = hermesPython(dir, BridgeScript, emptyList())
        try {
            shell.openStream(command)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AdminException("SSH 打不开通道：${e.message ?: e.javaClass.simpleName}")
        }
    }

    override suspend fun openRooms(): com.hermes.mobile.data.ssh.RemoteStream = withContext(Dispatchers.IO) {
        val command = hermesPython(home(), GroupBridgeScript, emptyList())
        try {
            shell.openStream(command)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AdminException("SSH 打不开通道：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 用 Hermes 自己的 Python 环境跑一段脚本（脚本里能 import Hermes 的代码）。
     * 新的源码安装：`hermes` 启动器是“自带的 Python + -I + 引导代码”，裸跑那个 Python 是 import 不到 Hermes 的；
     * 用 `hermes --print-runtime-command` 拿到它启动自己的那条命令，把最后“运行 hermes_cli.main”换成运行我们的脚本。
     * 旧的安装方式（venv / pip）没有这个参数，就照老办法顺着启动脚本找 Python。
     * 脚本先写进临时文件（读完就删），stdin 留给脚本用（Bot 屏幕的画面数据走 stdin/stdout）。
     */
    internal fun hermesPython(dir: String, script: String, args: List<String>): String = runtimePython(dir, script, args)

    override suspend fun modelSettings(program: String, input: String): String = withContext(Dispatchers.IO) {
        // 密钥通过 SSH 的标准输入发送，不放进远端命令行、临时脚本或诊断。
        val command = runtimePython(home(), program, emptyList())
        kotlinx.coroutines.runInterruptible(Dispatchers.IO) { shell.openStream(command).use { stream ->
            stream.input.write(input.toByteArray(Charsets.UTF_8))
            stream.input.close()
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.output.read(buffer)
                if (count < 0) break
                check(output.size() + count <= 512 * 1024) { "模型配置回包过大" }
                output.write(buffer, 0, count)
            }
            output.toByteArray().toString(Charsets.UTF_8)
        } }
    }

    private fun checkFolder(folder: String) = "[ -d ${q(folder)} ] || { echo $NoSuchFolder >&2; exit 2; }; "

    private inline fun <T> noSuchFolder(folder: String?, block: () -> T): T = try {
        block()
    } catch (e: AdminException) {
        throw if (e.message?.contains(NoSuchFolder) == true) AdminException("服务器上没有这个文件夹：$folder") else e
    }

    override suspend fun createProject(name: String, folder: String) {
        if (name.isBlank()) throw AdminException("项目名不能为空")
        noSuchFolder(folder) { run("${checkFolder(folder)}$hermes project create ${q(name.trim())} ${q(folder)}") }
    }

    override suspend fun listFolders(path: String?): Pair<String, List<String>> {
        val target = path?.let(::q) ?: "\"\$HOME\""
        val out = run("cd $target 2>/dev/null || exit 3; pwd; ls -1p 2>/dev/null | grep '/\$' | sed 's#/\$##'")
        val lines = out.lines().map { it.trimEnd() }.filter { it.isNotEmpty() }
        val here = lines.firstOrNull()?.takeIf { it.startsWith("/") } ?: throw AdminException("打不开这个文件夹")
        return here to lines.drop(1).sortedBy { it.lowercase() }
    }

    override suspend fun makeFolder(parent: String, name: String): String {
        val clean = name.trim()
        if (clean.isEmpty() || clean == "." || clean == ".." || '/' in clean || '\u0000' in clean) throw AdminException("文件夹名不能为空，也不能带 /")
        val path = parent.trimEnd('/') + "/" + clean
        noSuchFolder(parent) {
            run("${checkFolder(parent)}[ -e ${q(path)} ] && { echo 已经有同名的文件或文件夹 >&2; exit 4; }; mkdir -- ${q(path)} && echo HERMES_MOBILE_OK")
        }
        return path
    }

    override suspend fun deleteProject(projectId: String) = project("delete", projectId, "")

    override suspend fun renameProject(projectId: String, name: String) {
        if (name.isBlank()) throw AdminException("项目名不能为空")
        project("rename", projectId, name.trim())
    }

    override suspend fun hermesHome(): String = home()

    // ---- Bot（Profile） ----

    override suspend fun bots(): BotRoster {
        val out = run(hermesPython(home(), BotsScript, emptyList()), timeoutSeconds = 60)
        val line = out.lines().lastOrNull { it.startsWith("BOTS") } ?: throw AdminException("读不到 bot 列表：${out.lines().lastOrNull { it.isNotBlank() }?.take(200)}")
        val json = HermesJson.parseToJsonElement(line.removePrefix("BOTS")) as? JsonObject ?: throw AdminException("bot 列表格式不对")
        fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        fun JsonObject.ref(k: String): BotSessionRef? = (this[k] as? JsonObject)?.let { r ->
            val id = r.str("id") ?: return@let null
            BotSessionRef(id, r.str("title").orEmpty(), ((r["last_active"] as? JsonPrimitive)?.doubleOrNull ?: 0.0).times(1000).toLong(), r.str("preview").orEmpty())
        }
        val bots = (json["bots"] as? JsonArray).orEmpty().mapNotNull { e ->
            val b = e as? JsonObject ?: return@mapNotNull null
            Bot(
                name = b.str("name") ?: return@mapNotNull null,
                isDefault = (b["is_default"] as? JsonPrimitive)?.booleanOrNull == true,
                displayName = b.str("display_name").orEmpty(),
                title = b.str("title").orEmpty(),
                description = b.str("description").orEmpty(),
                model = b.str("model"),
                hasKey = (b["has_key"] as? JsonPrimitive)?.booleanOrNull == true,
                avatarPath = b.str("avatar"),
                color = b.str("color"),
                canonical = b.ref("canonical"),
                last = b.ref("last"),
                avatarVersion = (b["avatar_mtime"] as? JsonPrimitive)?.longOrNull ?: 0,
            )
        }
        return BotRoster(bots, (json["multiplex"] as? JsonPrimitive)?.booleanOrNull, json.str("active"))
    }

    override suspend fun botKey(name: String): String? {
        val out = run(hermesPython(home(), BotKeyScript, listOf("read", botName(name))))
        return out.lines().lastOrNull { it.startsWith("KEY") }?.removePrefix("KEY")?.trim()?.takeIf { it.length >= 16 }
    }

    override suspend fun enableBotAccess(name: String): BotAccess {
        val out = run(hermesPython(home(), BotKeyScript, listOf("enable", botName(name))), timeoutSeconds = 60)
        val line = out.lines().lastOrNull { it.startsWith("ACCESS") } ?: throw AdminException("没开通成功：${out.lines().lastOrNull { it.isNotBlank() }?.take(200)}")
        val json = HermesJson.parseToJsonElement(line.removePrefix("ACCESS")) as JsonObject
        val key = (json["key"] as? JsonPrimitive)?.contentOrNull ?: throw AdminException("没拿到 key")
        val changed = (json["changed"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        // key 是 Hermes 每次请求现读的，不用重启；只有刚打开 multiplex 才要重启 gateway
        return BotAccess(key, "multiplex" in changed, changed)
    }

    /**
     * 没装成系统服务的 gateway，`hermes gateway restart` 会先停掉它、再在自己这个进程里前台跑起来；
     * 直接在 SSH 命令里跑，SSH 一断 gateway 就跟着没了。所以放到后台、脱离这次 SSH 会话去跑，日志写进 Hermes 的 logs。
     */
    override suspend fun restartGateway(): String {
        val log = home().trimEnd('/') + "/logs/hermes-mobile-restart.log"
        return run(
            "mkdir -p ${q(log.substringBeforeLast('/'))}; " +
                "(setsid nohup $hermesBare gateway restart > ${q(log)} 2>&1 < /dev/null &) ; echo HERMES_MOBILE_RESTARTING",
            timeoutSeconds = 30,
            tolerate = { true },
        ).trim()
    }

    override suspend fun describeBot(name: String): BotDetails {
        val json = botEdit("describe", buildJsonObject { put("name", botName(name)) })
        fun str(k: String) = (json[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return BotDetails(str("description"), str("soul"), str("provider"), str("model"))
    }

    override suspend fun createBot(edit: BotEdit): BotSaved {
        val json = botEdit("create", edit.toJson(), timeoutSeconds = 180)
        return BotSaved.Done((json["name"] as? JsonPrimitive)?.contentOrNull ?: edit.name, (json["needs_model"] as? JsonPrimitive)?.contentOrNull)
    }

    override suspend fun configureBot(edit: BotEdit): BotSaved {
        val json = botEdit("configure", edit.toJson())
        (json["confirm"] as? JsonPrimitive)?.contentOrNull?.let { return BotSaved.ConfirmModel(it) }
        return BotSaved.Done(edit.name)
    }

    override suspend fun deleteBot(name: String) {
        if (name == "default") throw AdminException("默认 bot 不能删")
        botEdit("delete", buildJsonObject { put("name", botName(name)) }, timeoutSeconds = 120)
    }

    private fun BotEdit.toJson() = buildJsonObject {
        put("name", botName(name))
        title?.let { put("title", it.trim()) }
        description?.let { put("description", it.trim()) }
        soul?.let { put("soul", it) }
        color?.let { put("color", it) }
        avatar?.let { put("avatar", it) }
        if (clearAvatar) put("clear_avatar", true)
        if (!provider.isNullOrBlank() && !model.isNullOrBlank()) {
            put("provider", provider)
            put("model", model)
            if (confirmExpensiveModel) put("confirm_expensive_model", true)
        }
    }

    /**
     * 跑 [BotEditScript]：参数（可能带头像图片）编成 base64 直接写进脚本里，不走命令行参数。
     * 脚本调用 Hermes 桌面版用的同一套 profiles.create / configure / set_asset。
     */
    private suspend fun botEdit(op: String, params: JsonObject, timeoutSeconds: Long = 90): JsonObject {
        val encoded = java.util.Base64.getEncoder().encodeToString(params.toString().toByteArray(Charsets.UTF_8))
        val out = run(hermesPython(home(), BotEditScript.replace("@@PARAMS@@", encoded), listOf(op)), timeoutSeconds = timeoutSeconds)
        out.lines().lastOrNull { it.startsWith("EDITERR") }?.let { line ->
            val reason = runCatching { (HermesJson.parseToJsonElement(line.removePrefix("EDITERR")) as JsonObject)["error"] as JsonPrimitive }.getOrNull()?.contentOrNull
            throw AdminException(reason ?: "没改成")
        }
        val line = out.lines().lastOrNull { it.startsWith("EDIT") } ?: throw AdminException("没改成：${out.lines().lastOrNull { it.isNotBlank() }?.take(200)}")
        return HermesJson.parseToJsonElement(line.removePrefix("EDIT")) as? JsonObject ?: throw AdminException("服务器回的格式不对")
    }

    override suspend fun readRemoteFile(path: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            shell.readFile(path)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AdminException("读不了 $path：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun botName(name: String): String {
        if (!BotNamePattern.matches(name)) throw AdminException("bot 名字不对：$name")
        return name
    }

    /** 用 Hermes 自己的 projects_db（桌面版改项目也是调它）。 */
    private suspend fun project(op: String, projectId: String, name: String) {
        val dir = home()
        val script = hermesPython(dir, ProjectScript, listOf(op, projectId, name))
        val out = run(script)
        if ("NOTFOUND" in out) throw AdminException("Hermes 里找不到这个项目（可能已经被删了）")
        if ("OK" !in out) throw AdminException("没改成：${out.lines().lastOrNull { it.isNotBlank() }?.take(200)}")
    }

    override suspend fun moveSession(sessionId: String, folder: String?) {
        if (!SessionIdPattern.matches(sessionId)) throw AdminException("会话 id 不对：$sessionId")
        val dir = home()
        val check = folder?.let(::checkFolder).orEmpty()
        // 用 Hermes 自己的 Python 跑 Hermes 自己的代码（和桌面版拖动会话调用的是同一个函数），找不到才用系统 python3
        val script = check + hermesPython(dir, MoveScript, listOf(sessionId, folder.orEmpty(), dir))
        val out = noSuchFolder(folder) { run(script, timeoutSeconds = 60) }
        if ("NOTFOUND" in out) throw AdminException("Hermes 里找不到这个会话")
        if ("OK" !in out) throw AdminException("移动失败：${out.lines().lastOrNull { it.isNotBlank() }?.take(200)}")
    }


    override suspend fun logs(lines: Int): String =
        run("$hermes logs -n ${lines.coerceIn(10, 2000)}", timeoutSeconds = 60).trimEnd()

    private fun safeName(name: String): String {
        if (!McpName.matches(name)) throw AdminException("名字不对：$name")
        return name
    }

    companion object {
        internal fun runtimePython(dir: String, script: String, args: List<String>): String {
            val argv = args.joinToString(" ") { q(it) }
            return listOf(
                "HM_SCRIPT=\$(mktemp \"\${TMPDIR:-/tmp}/hermes-mobile.XXXXXX\") || exit 1",
                "cat > \"\$HM_SCRIPT\" <<'HERMES_MOBILE_PY'",
                script,
                "HERMES_MOBILE_PY",
                "HM_LOADER=${q(PythonLoader)}",
                "export HM_SCRIPT HM_LOADER HERMES_HOME=${q(dir)}",
                "HM_RC=\$(hermes --print-runtime-command 2>/dev/null | tail -n 1)",
                "case \"\$HM_RC\" in",
                "  '[\"'*)",
                "    export HM_RC",
                "    HM_PY=\$(printf '%s' \"\$HM_RC\" | sed -n 's/^\\[\"\\([^\"]*\\)\".*/\\1/p')",
                "    if [ -x \"\$HM_PY\" ]; then \"\$HM_PY\" -I -c ${q(RuntimeShim)} $argv; HM_CODE=\$?; [ \$HM_CODE -ne 70 ] && exit \$HM_CODE; fi",
                "    ;;",
                "esac",
                FindHermesPython,
                "exec \$PY -c \"\$HM_LOADER\" $argv",
            ).joinToString("\n")
        }


        /**
         * 只读地查 Hermes 的两个库：projects.db（项目和它们的文件夹）、state.db（每个会话的工作目录）。
         * 匹配规则和 Hermes 一样：文件夹等于或包含工作目录的项目里，取路径最长的那个。
         */
        val GroupsScript = """
import json, os, sqlite3, sys
home = sys.argv[1]
def open_db(name):
    path = os.path.join(home, name)
    if not os.path.exists(path):
        return None
    try:
        return sqlite3.connect("file:" + path + "?mode=ro", uri=True)
    except Exception:
        return sqlite3.connect(path)
out = {"projects": [], "sessions": {}}
folders = []
db = open_db("projects.db")
if db is not None:
    try:
        for pid, name, path in db.execute("select id, name, primary_path from projects where archived = 0 order by created_at"):
            out["projects"].append({"id": pid, "name": name, "path": path})
        folders = list(db.execute("select pf.project_id, pf.path from project_folders pf join projects p on p.id = pf.project_id where p.archived = 0"))
    except Exception:
        pass
def owner(path):
    best, found = -1, None
    for pid, folder in folders:
        stem = folder.rstrip("/\\")
        if path == folder or path.startswith(stem + "/") or path.startswith(stem + "\\"):
            if len(folder) > best:
                best, found = len(folder), pid
    return found
db = open_db("state.db")
if db is not None:
    cols = [r[1] for r in db.execute("pragma table_info(sessions)")]
    root = "git_repo_root" if "git_repo_root" in cols else "NULL"
    if "cwd" in cols:
        for sid, cwd, repo in db.execute("select id, cwd, " + root + " from sessions where coalesce(cwd, '') != '' or coalesce(" + root + ", '') != ''"):
            cwd = (cwd or "").strip()
            repo = (repo or "").strip()
            out["sessions"][sid] = {"project": owner(cwd) or (owner(repo) if repo else None), "folder": repo or cwd, "cwd": cwd}
    if "archived" in cols:
        at = "last_activity_at" if "last_activity_at" in cols else "started_at"
        out["archived"] = [{"id": i, "title": t, "at": a} for i, t, a in db.execute(
            "select id, title, " + at + " from sessions where archived = 1 order by " + at + " desc limit 200")]
print(json.dumps(out))
""".trimIndent()

        val SessionIdPattern = Regex("[A-Za-z0-9_.:-]+")

        /**
         * 找 Hermes 用的 Python：顺着 `hermes` 启动器（可能是符号链接、`exec` 转发的 shell 脚本或带 shebang 的
         * Python 脚本）找到它旁边的 python，或 shebang 里的解释器。找不到就用系统的 python3。
         */
        val FindHermesPython = """
PY=""
f=${'$'}(command -v hermes 2>/dev/null)
i=0
while [ -n "${'$'}f" ] && [ ${'$'}i -lt 5 ]; do
  i=${'$'}((i + 1))
  f=${'$'}(readlink -f "${'$'}f" 2>/dev/null || echo "${'$'}f")
  d=${'$'}(dirname "${'$'}f")
  for c in "${'$'}d/python" "${'$'}d/python3"; do [ -x "${'$'}c" ] && { PY="${'$'}c"; break 2; }; done
  first=${'$'}(head -n 1 "${'$'}f" 2>/dev/null)
  case "${'$'}first" in
    '#!'*python*) PY=${'$'}{first#\#!}; break ;;  # 可能是 “/usr/bin/env python3”，所以下面调用时不加引号
  esac
  f=${'$'}(grep -m1 -oE 'exec "?/[^ "]+' "${'$'}f" 2>/dev/null | sed -E 's/^exec "?//')
done
[ -z "${'$'}PY" ] && PY=python3
""".trimIndent()

        /** 改会话的工作目录：优先用 Hermes 自己的 SessionDB.update_session_cwd（和桌面版一样），不行再直接改库。 */
        val MoveScript = """
import os, sqlite3, subprocess, sys
sid, cwd, home = sys.argv[1], sys.argv[2], sys.argv[3]
def git(*args):
    try:
        return subprocess.run(["git", "-C", cwd] + list(args), capture_output=True, text=True, timeout=10).stdout.strip()
    except Exception:
        return ""
if cwd:
    try:
        from hermes_state import SessionDB
        try:
            from tui_gateway import git_probe
            branch, root = git_probe.branch(cwd), git_probe.common_repo_root(cwd)
        except Exception:
            branch, root = git("rev-parse", "--abbrev-ref", "HEAD"), git("rev-parse", "--show-toplevel")
        result = SessionDB().update_session_cwd(sid, cwd, branch, root, replace_git_meta=True)
        print("OK" if result is not None else "NOTFOUND")
        sys.exit(0)
    except ImportError:
        pass
db = sqlite3.connect(os.path.join(home, "state.db"), timeout=10)
if db.execute("select 1 from sessions where id = ?", (sid,)).fetchone() is None:
    print("NOTFOUND")
    sys.exit(0)
branch = git("rev-parse", "--abbrev-ref", "HEAD") if cwd else ""
root = git("rev-parse", "--show-toplevel") if cwd else ""
db.execute("update sessions set cwd = ?, git_branch = ?, git_repo_root = ?, git_metadata_generation = coalesce(git_metadata_generation, 0) + 1 where id = ?",
           (cwd or None, branch or None, root or None, sid))
db.commit()
print("OK")
""".trimIndent()

        val BotNamePattern = Regex("[a-z0-9][a-z0-9_-]{0,63}")

        /**
         * 列出所有 bot：Hermes 自己的 list_profiles + 每个 profile 的 profile.yaml、头像、state.db（固定对话「Bot Chat」和最近的对话）。
         * 只读；不输出 key 本身，只说有没有。
         */
        val BotsScript = """
import json, os, sqlite3, sys
from pathlib import Path
from hermes_cli.profiles import list_profiles
try:
    from hermes_cli.profiles import read_profile_meta
except Exception:
    read_profile_meta = None
try:
    from hermes_cli.profiles import get_active_profile_name
except Exception:
    get_active_profile_name = lambda: None
try:
    import hermes_yaml as yaml
except ImportError:
    import yaml

def load_yaml(path):
    try:
        with open(path, encoding="utf-8") as f:
            data = yaml.safe_load(f) or {}
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}

def has_key(profile_dir):
    try:
        with open(Path(profile_dir) / ".env", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line.startswith("export "):
                    line = line[7:].strip()
                if line.startswith("API_SERVER_KEY="):
                    return len(line.split("=", 1)[1].strip().strip(chr(34)).strip(chr(39))) >= 16
    except Exception:
        pass
    return False

def preview_of(conn, sid):
    row = conn.execute("select content from messages where session_id = ? and role in ('user', 'assistant') and coalesce(content, '') != '' order by id desc limit 1", (sid,)).fetchone()
    if not row:
        return ""
    text = row[0]
    try:
        parsed = json.loads(text)
        if isinstance(parsed, list):
            text = " ".join(str(p.get("text", "")) for p in parsed if isinstance(p, dict))
    except Exception:
        pass
    return " ".join(str(text).split())[:160]

def sessions(profile_dir):
    db = Path(profile_dir) / "state.db"
    out = {}
    if not db.exists():
        return out
    try:
        conn = sqlite3.connect("file:%s?mode=ro" % db, uri=True, timeout=5)
        cols = {r[1] for r in conn.execute("pragma table_info(sessions)")}
        when = "coalesce(last_activity_at, started_at)" if "last_activity_at" in cols else "started_at"
        alive = " and coalesce(archived, 0) = 0" if "archived" in cols else ""
        def pick(where, args=()):
            r = conn.execute("select id, title, " + when + " from sessions where " + where + alive + " order by " + when + " desc limit 1", args).fetchone()
            if not r:
                return None
            return {"id": r[0], "title": r[1] or "", "last_active": r[2] or 0, "preview": preview_of(conn, r[0])}
        out["canonical"] = pick("title = ?", ("Bot Chat",))
        out["last"] = pick("parent_session_id is null" if "parent_session_id" in cols else "1 = 1")
    except Exception as exc:
        out["error"] = str(exc)
    return out

bots = []
for p in list_profiles():
    path = Path(str(p.path))
    meta = {}
    if read_profile_meta is not None:
        try:
            meta = read_profile_meta(path) or {}
        except Exception:
            meta = {}
    raw = load_yaml(path / "profile.yaml")
    ui = raw.get("ui_meta") if isinstance(raw.get("ui_meta"), dict) else {}
    bot_meta = ui.get("hermes-bots") if isinstance(ui.get("hermes-bots"), dict) else {}
    avatar, avatar_mtime = None, 0
    for ext in ("png", "jpg", "jpeg", "webp", "gif"):
        if (path / "assets" / ("avatar." + ext)).is_file():
            avatar = str(path / "assets" / ("avatar." + ext))
            try:
                avatar_mtime = int(os.path.getmtime(avatar) * 1000)
            except Exception:
                pass
            break
    row = {
        "name": p.name, "is_default": bool(p.is_default), "model": p.model, "provider": p.provider,
        "display_name": meta.get("display_name") or raw.get("display_name") or getattr(p, "display_name", "") or "",
        "title": meta.get("bot_title") or bot_meta.get("title") or "",
        "description": meta.get("description") or raw.get("description") or getattr(p, "description", "") or "",
        "has_key": True if p.is_default else has_key(path), "avatar": avatar, "avatar_mtime": avatar_mtime,
        "color": bot_meta.get("color"),
    }
    row.update(sessions(path))
    bots.append(row)

root = load_yaml(Path(os.environ["HERMES_HOME"]).resolve() / "config.yaml")
try:
    from hermes_constants import get_default_hermes_root
    root = load_yaml(Path(get_default_hermes_root()) / "config.yaml")
except Exception:
    pass
multiplex = root.get("multiplex_profiles")
if multiplex is None and isinstance(root.get("gateway"), dict):
    multiplex = root["gateway"].get("multiplex_profiles")
env_override = os.environ.get("GATEWAY_MULTIPLEX_PROFILES", "").strip().lower()
if env_override in ("1", "true", "yes", "on"):
    multiplex = True
active = None
try:
    active = get_active_profile_name()
except Exception:
    pass
print("BOTS" + json.dumps({"bots": bots, "multiplex": multiplex, "active": active}, default=str, ensure_ascii=False))
""".trimIndent()

        /**
         * 读 / 开通 bot 的手机访问：read 只读 bot 的 API_SERVER_KEY；
         * enable 在需要时打开根配置的 gateway.multiplex_profiles、给 bot 的 .env 写一个新 key（权限 600），
         * 输出 key 和改了什么（改了才需要重启 gateway）。默认 bot 用连接本身的 key，不动。
         */
        val BotKeyScript = """
import json, os, secrets, sys
from pathlib import Path
from hermes_cli.profiles import get_profile_dir
op, name = sys.argv[1], sys.argv[2]
profile_dir = Path(get_profile_dir(name))
env_path = profile_dir / ".env"

def read_key():
    try:
        with open(env_path, encoding="utf-8") as f:
            for line in f:
                s = line.strip()
                if s.startswith("export "):
                    s = s[7:].strip()
                if s.startswith("API_SERVER_KEY="):
                    v = s.split("=", 1)[1].strip().strip(chr(34)).strip(chr(39))
                    if len(v) >= 16:
                        return v
    except FileNotFoundError:
        pass
    return None

if op == "read":
    key = read_key()
    print("KEY" + (key or ""))
    sys.exit(0)

changed = []
key = read_key()
if key is None:
    key = secrets.token_urlsafe(32)
    lines = []
    if env_path.exists():
        with open(env_path, encoding="utf-8") as f:
            lines = [l for l in f.read().splitlines()
                     if not (l.strip()[7:].strip() if l.strip().startswith("export ") else l.strip()).startswith("API_SERVER_KEY=")]
    lines.append("API_SERVER_KEY=" + key)
    profile_dir.mkdir(parents=True, exist_ok=True)
    tmp = env_path.with_name(".env.hermes-mobile")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    os.replace(tmp, env_path)
    changed.append("key")

try:
    import hermes_yaml as yaml
except ImportError:
    import yaml
try:
    from hermes_constants import get_default_hermes_root
    root = Path(get_default_hermes_root())
except Exception:
    root = Path(os.environ["HERMES_HOME"])
cfg_path = root / "config.yaml"
cfg = {}
if cfg_path.exists():
    with open(cfg_path, encoding="utf-8") as f:
        cfg = yaml.safe_load(f) or {}
gw = cfg.get("gateway") if isinstance(cfg.get("gateway"), dict) else {}
on = cfg.get("multiplex_profiles")
if on is None:
    on = gw.get("multiplex_profiles")
if on is not True:
    import subprocess
    r = subprocess.run(["hermes", "config", "set", "gateway.multiplex_profiles", "true"], capture_output=True, text=True,
                       env=dict(os.environ, HERMES_HOME=str(root)))
    if r.returncode != 0:
        sys.stderr.write((r.stderr or r.stdout)[-300:])
        sys.exit(3)
    changed.append("multiplex")
print("ACCESS" + json.dumps({"key": key, "changed": changed}))
""".trimIndent()

        /**
         * 新建 / 读 / 改 / 删 bot。参数在 @@PARAMS@@（base64 的 JSON），操作在 argv[1]。
         * 改动走 Hermes 自己的 tui_gateway（桌面版 Bot 用的就是这些方法），删除用 hermes_cli 的 delete_profile。
         * 结果一行 EDIT{json}；出错一行 EDITERR{"error": …}。
         */
        val BotEditScript = """
import base64, json, os, sys, time
op = sys.argv[1]
# tui_gateway.server 一 import 就把 stdout 换成 stderr（它的 stdout 是 JSON-RPC 通道），先留一份真的 stdout
_out = os.fdopen(os.dup(1), "w", encoding="utf-8")
params = json.loads(base64.b64decode("@@PARAMS@@").decode("utf-8"))

def done(obj):
    _out.write("EDIT" + json.dumps(obj, ensure_ascii=False, default=str) + "\n")
    _out.flush()
    sys.exit(0)

def fail(msg):
    _out.write("EDITERR" + json.dumps({"error": str(msg)[-400:]}, ensure_ascii=False) + "\n")
    _out.flush()
    sys.exit(0)

# 当前用的可能是某个 bot 的目录；管理 bot 要站在主目录上（新建时从这里复制模型和 key）
try:
    from hermes_constants import get_default_hermes_root
    os.environ["HERMES_HOME"] = str(get_default_hermes_root())
except Exception:
    pass

if op == "delete":
    from hermes_cli.profiles import delete_profile, get_profile_dir
    from pathlib import Path
    name = params["name"]
    if name.strip().lower() == "default":
        fail("默认 bot 不能删")
    path = Path(get_profile_dir(name))
    try:
        delete_profile(name, yes=True)
    except Exception as exc:
        if path.exists():
            fail(exc)
    done({"ok": not path.exists()})

try:
    import tui_gateway.server as _srv
    methods = _srv._methods
except Exception as exc:
    fail("这个版本的 Hermes 不支持在手机上改 bot（%s）" % exc)

def call(method, p):
    fn = methods.get(method)
    if fn is None:
        fail("这个版本的 Hermes 不支持 " + method)
    r = fn("hermes-mobile", p) or {}
    if r.get("error"):
        fail(r["error"].get("message") or method + " 失败")
    return r.get("result") or {}

def ui_meta(name):
    from hermes_cli.profiles import get_profile_dir
    from pathlib import Path
    try:
        import hermes_yaml as yaml
    except ImportError:
        import yaml
    try:
        with open(Path(get_profile_dir(name)) / "profile.yaml", encoding="utf-8") as f:
            raw = yaml.safe_load(f) or {}
    except Exception:
        raw = {}
    meta = raw.get("ui_meta") if isinstance(raw, dict) and isinstance(raw.get("ui_meta"), dict) else {}
    look = meta.get("hermes-bots")
    return dict(look) if isinstance(look, dict) else {}

def protocol(name, roster):
    handle = "hermes" if name == "default" else name
    mates = [b for b in roster if b.get("name") != name]
    lines = [
        "## Messaging other agents", "",
        "You work alongside other named agents. Every agent (including you) has",
        "ONE canonical conversation titled \"Bot Chat\" — created with the agent,",
        "so it always exists. Agent-to-agent messages are delivered straight",
        "into it, like a DM. To message a teammate, run:", "",
        "```",
        "hermes -p <agent-name> chat --in ~ -c \"Bot Chat\" --create-if-missing -Q -q \"Message from \U0001F916 " + handle + " (@" + handle + "): your message\"",
        "",
        "Run the send with background=true and notify_on_complete=true on the",
        "terminal tool, then finish your turn — the reply arrives later as a",
        "background process notification. Never block waiting for it.",
        "```", "",
        "(`--in ~ -c \"Bot Chat\" --create-if-missing` resumes their canonical",
        "conversation in the home workspace, creating it if the target has no",
        "\"Bot Chat\" yet. `-Q` keeps output clean. Always open with the",
        "\"Message from \U0001F916 " + handle + " (@" + handle + "):\" prefix so they know",
        "who is talking (the @handle lets the app show your avatar to them).",
        "Their reply prints to stdout — relay the relevant part back to the",
        "user, and say which agent it came from.)", "",
        "If a message in YOUR chat starts with \"Message from \U0001F916 <name>\", it is",
        "a teammate messaging you, not the user. Answer it directly — your reply",
        "reaches them via their own delivery — and use the same command if you",
        "need to start a conversation yourself.", "",
        "When the user writes @<agent-name> or says \"ask <name> to ...\" /",
        "\"tell <name> ...\", that is a handoff: message that agent, wait for the",
        "reply, and report back.", "",
        "The roster grows over time — run `hermes profile list` for the LIVE",
        "teammate list before a handoff. Teammates when you were created:",
    ]
    lines += ["- `%s`%s" % (b["name"], " — " + b["description"] if b.get("description") else "") for b in mates] or ["- (none yet)"]
    return "\n".join(lines)

def with_protocol(soul, name):
    listed = call("profiles.list", {"include_sessions": False})
    if listed.get("bot_mode_protocol") or "## Messaging other agents" in soul:
        return soul
    section = protocol(name, listed.get("profiles") or [])
    return soul + "\n\n" + section if soul else section

def save_look(name, title, color):
    look = ui_meta(name)
    if title is not None:
        if title:
            look["title"] = title
        else:
            look.pop("title", None)
    if color:
        look["color"] = color
    return call("profiles.configure", {"name": name, "ui_meta": {"hermes-bots": look}}).get("applied", {}).get("ui_meta", False)

def save_avatar(name):
    if params.get("clear_avatar"):
        call("profiles.set_asset", {"name": name, "asset": "avatar", "clear": True})
        look = ui_meta(name)
        look["imageKind"] = "shape"
        call("profiles.configure", {"name": name, "ui_meta": {"hermes-bots": look}})
    elif params.get("avatar"):
        call("profiles.set_asset", {"name": name, "asset": "avatar", "data": params["avatar"]})
        look = ui_meta(name)
        look["imageKind"] = "photo"
        look["custom"] = True
        call("profiles.configure", {"name": name, "ui_meta": {"hermes-bots": look}})

if op == "describe":
    d = call("profiles.describe", {"name": params["name"]})
    model = d.get("model") or {}
    done({"description": d.get("description") or "", "soul": d.get("soul") or "",
          "provider": model.get("provider") or "", "model": model.get("default") or ""})

if op == "create":
    name, title, description = params["name"], params.get("title") or "", params.get("description") or ""
    soul = (params.get("soul") or "").strip()
    if not soul:
        who = title or " ".join(w[:1].upper() + w[1:] for w in name.replace("_", " ").replace("-", " ").split())
        soul = "\n".join(l for l in [
            "# " + who, "",
            "**Role:** " + title if title else None,
            "**Mission:** " + description if description else None, "",
            "You are %s, a persistent named agent (profile `%s`) on this machine." % (who, name),
            "You keep your own memory, skills, and conversation history across sessions.",
        ] if l is not None)
    created = call("profiles.create", {"name": name, "description": description, "clone_from": "default",
                                       "share_auth": True, "soul": with_protocol(soul, name)})
    look = {"title": title, "color": params.get("color"), "imageKind": "shape", "shape": "circle",
            "created": int(time.time() * 1000)}
    call("profiles.configure", {"name": name, "ui_meta": {"hermes-bots": {k: v for k, v in look.items() if v}}})
    save_avatar(name)
    ready = None
    if "setup.runtime_check" in methods:
        try:
            r = methods["setup.runtime_check"]("hermes-mobile", {"profile": name}) or {}
            res = r.get("result") or {}
            ready = None if res.get("ok") is not False else (res.get("error") or "这个 bot 还没有能用的模型")
        except Exception:
            ready = None
    done({"name": created.get("name") or name, "needs_model": ready})

if op == "configure":
    name = params["name"]
    applied = {}
    if "title" in params or params.get("color"):
        applied["look"] = save_look(name, params.get("title"), params.get("color"))
    save_avatar(name)
    p = {"name": name}
    if isinstance(params.get("description"), str):
        p["description"] = params["description"]
    if isinstance(params.get("soul"), str):
        p["soul"] = with_protocol(params["soul"].strip(), name)
    if params.get("model") and params.get("provider"):
        p.update(model=params["model"], provider=params["provider"],
                 confirm_expensive_model=bool(params.get("confirm_expensive_model")))
    if len(p) > 1:
        r = call("profiles.configure", p)
        applied.update(r.get("applied") or {})
        if r.get("confirm_required"):
            done({"applied": applied, "confirm": r.get("confirm_message") or "这个模型比较贵，确定要用吗？"})
        if applied.get("model") is False:
            # configure 把原因吞掉了：直接再试一次，拿到 Hermes 说的原因
            try:
                from pathlib import Path
                from hermes_cli.profiles import get_profile_dir
                from hermes_cli.web_routers.profiles import _write_profile_model
                _write_profile_model(Path(get_profile_dir(name)), p["provider"], p["model"])
                applied["model"] = True
            except Exception as exc:
                fail("模型没改成：" + str(getattr(exc, "detail", None) or exc))
    failed = [k for k, v in applied.items() if v is False]
    if failed:
        fail("这几项没保存成功：" + ", ".join(failed))
    done({"applied": applied})

fail("unknown op " + op)
""".trimIndent()

        /**
         * 群聊的桥：群聊（hosted rooms）在 gateway 进程里跑，只有 /api/ws 的 groups.* JSON-RPC 能用。
         * 桥用 Hermes 自己的 gateway_client：经本机控制 socket 换一张 host 范围的一次性票据，连上后
         * stdin 一行一个请求 {id, method, params}，stdout 一行一个回复 {id, result | error}；第一行是 {ready}。
         */
        val GroupBridgeScript = """
import asyncio, json, os, sys
# 群聊在 gateway 里跑（hosted rooms），只能经它的 /api/ws JSON-RPC 访问：
# 用本机控制 socket 换一张一次性票据（和电脑版、TUI 一样），连上后把 stdin 的每行请求转发过去，回复写到 stdout。
try:
    from hermes_constants import get_default_hermes_root
    os.environ["HERMES_HOME"] = str(get_default_hermes_root())
except Exception:
    pass
out = os.fdopen(os.dup(1), "w", encoding="utf-8")
sys.stdout = sys.stderr

def emit(obj):
    out.write(json.dumps(obj, ensure_ascii=False, default=str) + "\n")
    out.flush()

async def main():
    try:
        from hermes_constants import get_hermes_home
        from hermes_cli.gateway_runtime import discover_gateway_endpoint, attachable, gateway_profile_home
        from hermes_cli.gateway_client import GatewayClient, _session_ticket, gateway_ws_target
        from websockets.asyncio.client import connect
    except Exception as exc:
        emit({"ready": False, "error": "这个版本的 Hermes 不支持群聊（%s）" % exc})
        return
    home = gateway_profile_home(get_hermes_home())
    found = await asyncio.to_thread(discover_gateway_endpoint, home)
    if not attachable(found):
        emit({"ready": False, "error": "Hermes gateway 没在运行（%s）" % (found.reason_code or found.state)})
        return
    try:
        # host 范围：房间里能坐这个 gateway 服务的所有 bot（电脑版也是这样连的）
        ticket = await asyncio.to_thread(_session_ticket, home, found.endpoint, scope="host")
        url, protocols = gateway_ws_target(found.endpoint, ticket)
        ws_cm = connect(url, subprotocols=protocols, open_timeout=10, max_size=8 * 1024 * 1024, proxy=None)
        ws = await ws_cm.__aenter__()
    except Exception as exc:
        emit({"ready": False, "error": "连不上 Hermes gateway：%s" % exc})
        return
    async with GatewayClient(ws) as client:
        emit({"ready": True})
        loop = asyncio.get_running_loop()
        reader = asyncio.StreamReader()
        await loop.connect_read_pipe(lambda: asyncio.StreamReaderProtocol(reader), sys.stdin)

        async def handle(req):
            rid = req.get("id")
            try:
                result = await client.rpc(req["method"], **(req.get("params") or {}))
                emit({"id": rid, "result": result})
            except Exception as exc:
                emit({"id": rid, "error": {"reason": str(getattr(exc, "reason", None) or exc),
                                           "data": getattr(exc, "data", None)}})

        while True:
            line = await reader.readline()
            if not line:
                break
            try:
                req = json.loads(line)
            except ValueError:
                continue
            asyncio.ensure_future(handle(req))
    await ws_cm.__aexit__(None, None, None)

asyncio.run(main())
""".trimIndent()

        /** 读出临时文件里的脚本，删掉文件，当作 __main__ 运行。 */
        val PythonLoader = """
import os, sys
_hm_path = os.environ.pop("HM_SCRIPT")
with open(_hm_path, encoding="utf-8") as _hm_file:
    _hm_source = _hm_file.read()
os.unlink(_hm_path)
exec(compile(_hm_source, "hermes-mobile", "exec"), {"__name__": "__main__"})
""".trimIndent()

        /**
         * 拿 `hermes --print-runtime-command` 打印的命令（[Python, "-I", "-c", 引导代码]），
         * 把引导代码最后的 runpy.run_module(…) 换成 [PythonLoader]，原样 exec。格式不对时退出码 70，外面改用老办法。
         */
        val RuntimeShim = """
import json, os, sys
try:
    cmd = json.loads(os.environ["HM_RC"])
    code = cmd[3] if len(cmd) > 3 and cmd[1:3] == ["-I", "-c"] else ""
except Exception:
    code = ""
i = code.rfind("runpy.run_module(")
if i < 0:
    sys.exit(70)
os.execv(cmd[0], cmd[:3] + [code[:i] + "\n" + os.environ["HM_LOADER"]] + sys.argv[1:])
""".trimIndent()

        /** 删除 / 重命名项目：调 Hermes 自己的 projects_db。 */
        val ProjectScript = """
import sys
from hermes_cli import projects_db as pdb
op, pid, name = sys.argv[1], sys.argv[2], sys.argv[3]
with pdb.connect_closing() as conn:
    if pdb.get_project(conn, pid) is None:
        print("NOTFOUND")
        sys.exit(0)
    if op == "delete":
        pdb.delete_project(conn, pid)
    elif op == "rename":
        pdb.update_project(conn, pid, name=name)
print("OK")
""".trimIndent()

        /** 接管 / 交还：调 Hermes 自己的 lease 模块（和桌面版一样），输出公开的 lease。 */
        val LeaseScript = """
import json, sys
from tools.bot_desktop import lease
op, viewer, reason = sys.argv[1], sys.argv[2], sys.argv[3]
if op == "take":
    result = lease.acquire(viewer, reason=reason)
else:
    result = lease.release(viewer or None)
print("LEASE" + json.dumps(lease.public_view(result)))
""".trimIndent()

        /**
         * 把 SSH 通道接到屏幕的 RFB socket 上（屏幕在沙盒里时用 Hermes 提供的流）。
         * stdout 只能有画面数据，出错信息走 stderr；屏幕没开时退出码 3。
         */
        val BridgeScript = """
import os, select, sys
from tools.bot_desktop import runtime
path = runtime.rfb_socket_path()
proc = None
if path is not None:
    import socket
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.connect(str(path))
    rd = wr = sock.fileno()
else:
    try:
        proc = runtime.open_rfb_stream()
    except Exception as exc:
        sys.stderr.write("SCREEN_DOWN %s\n" % exc)
        sys.exit(3)
    rd, wr = proc.stdout.fileno(), proc.stdin.fileno()
inp, out = sys.stdin.fileno(), sys.stdout.fileno()
def write_all(fd, data):
    while data:
        data = data[os.write(fd, data):]
try:
    while True:
        ready = select.select([inp, rd], [], [])[0]
        if inp in ready:
            data = os.read(inp, 65536)
            if not data:
                break
            write_all(wr, data)
        if rd in ready:
            data = os.read(rd, 262144)
            if not data:
                break
            write_all(out, data)
finally:
    if proc is not None:
        proc.kill()
""".trimIndent()

        private const val NoSuchFolder = "HERMES_MOBILE_NO_SUCH_FOLDER"
        val McpName = Regex("[A-Za-z0-9_-]+")
        val Ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

        fun String.stripAnsi() = replace(Ansi, "")

        /** 单引号包起来给 shell。 */
        fun q(text: String) = "'" + text.replace("'", "'\\''") + "'"

        fun splitMemory(text: String?): List<String> =
            text.orEmpty().split(MemoryBook.MemoryDelimiter).map { it.trim() }.filter { it.isNotEmpty() }
    }
}

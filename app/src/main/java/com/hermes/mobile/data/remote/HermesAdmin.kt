package com.hermes.mobile.data.remote

import com.hermes.mobile.data.model.BotScreenStatus
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
    /** Bot 屏幕的状态（`hermes computer-use screen status --json`）。 */
    suspend fun screenStatus(): BotScreenStatus = throw AdminException("不支持")
    suspend fun startScreen(): BotScreenStatus = throw AdminException("不支持")
    /** 接管屏幕：bot 的 computer_use 和浏览器工具会被拒绝，直到交还。 */
    suspend fun takeScreen(viewerId: String, reason: String = ""): ScreenLease = throw AdminException("不支持")
    /** 交还给 bot。[viewerId] 为 null 是强制交还（不管谁接管的）。 */
    suspend fun handBackScreen(viewerId: String?): ScreenLease = throw AdminException("不支持")
    /** 打开屏幕的 RFB（VNC）字节流。 */
    suspend fun openScreen(): com.hermes.mobile.data.ssh.RemoteStream = throw AdminException("不支持")
}

/**
 * 经 SSH 管理 Hermes。尽量用 `hermes` 自己的命令（config get/set/unset、logs、mcp test），
 * 只有记忆和 config.yaml 原文直接读写文件。
 */
class HermesAdmin(private val shell: RemoteShell, profile: String?) : AdminApi {
    /** 不带 Profile 参数的 hermes（--print-runtime-command 必须是第一个参数；Profile 已经由 HERMES_HOME 定了）。 */
    private val hermesBare = "hermes"
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
        } catch (e: java.io.IOException) {
            throw AdminException("SSH 打不开通道：${e.message}")
        }
    }

    /**
     * 用 Hermes 自己的 Python 环境跑一段脚本（脚本里能 import Hermes 的代码）。
     * 新的源码安装：`hermes` 启动器是“自带的 Python + -I + 引导代码”，裸跑那个 Python 是 import 不到 Hermes 的；
     * 用 `hermes --print-runtime-command` 拿到它启动自己的那条命令，把最后“运行 hermes_cli.main”换成运行我们的脚本。
     * 旧的安装方式（venv / pip）没有这个参数，就照老办法顺着启动脚本找 Python。
     * 脚本先写进临时文件（读完就删），stdin 留给脚本用（Bot 屏幕的画面数据走 stdin/stdout）。
     */
    internal fun hermesPython(dir: String, script: String, args: List<String>): String {
        val argv = args.joinToString(" ") { q(it) }
        return listOf(
            "HM_SCRIPT=\$(mktemp \"\${TMPDIR:-/tmp}/hermes-mobile.XXXXXX\") || exit 1",
            "cat > \"\$HM_SCRIPT\" <<'HERMES_MOBILE_PY'",
            script,
            "HERMES_MOBILE_PY",
            "HM_LOADER=${q(PythonLoader)}",
            "export HM_SCRIPT HM_LOADER HERMES_HOME=${q(dir)}",
            "HM_RC=\$($hermesBare --print-runtime-command 2>/dev/null | tail -n 1)",
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

    private companion object {
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

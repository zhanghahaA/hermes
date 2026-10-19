package com.hermes.mobile.data.remote

import com.hermes.mobile.data.model.McpServer
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

    override suspend fun moveSession(sessionId: String, folder: String?) {
        if (!SessionIdPattern.matches(sessionId)) throw AdminException("会话 id 不对：$sessionId")
        val dir = home()
        val check = folder?.let(::checkFolder).orEmpty()
        // 用 Hermes 自己的 Python 跑 Hermes 自己的代码（和桌面版拖动会话调用的是同一个函数），找不到才用系统 python3
        val script = check + FindHermesPython + "\nHERMES_HOME=${q(dir)} \$PY - ${q(sessionId)} ${q(folder.orEmpty())} ${q(dir)} <<'HERMES_MOBILE_PY'\n$MoveScript\nHERMES_MOBILE_PY"
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

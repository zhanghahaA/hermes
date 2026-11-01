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
        shell.files["/home/u/.hermes/memories/USER.md"] = "叫小林\n§\n住苏州\n"
        val books = HermesAdmin(shell, null).memory()
        assertEquals(listOf("叫小林", "住苏州"), books.first { it.target == MemoryTarget.User }.entries)
        assertEquals(50, books.first { it.target == MemoryTarget.User }.charLimit)
        assertEquals(false, books.first { it.target == MemoryTarget.User }.enabled)
        assertEquals(emptyList(), books.first { it.target == MemoryTarget.Notes }.entries, "文件不存在就是空的")
    }

    @Test
    fun memoryIsWrittenAtomicallyAndRespectsTheLimit() = runBlocking<Unit> {
        val shell = FakeShell()
        val admin = HermesAdmin(shell, null)
        admin.saveMemory(MemoryTarget.User, listOf(" 叫小林 ", "", "住苏州"))
        assertEquals("叫小林\n§\n住苏州", shell.files["/home/u/.hermes/memories/USER.md"])
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
    private class LocalShell(private val home: java.io.File, private val path: String? = null) : RemoteShell {
        override fun exec(command: String, timeoutSeconds: Long): Pair<Int, String> {
            if ("config path" in command) return 0 to "${home.path}/config.yaml\n"
            val pb = ProcessBuilder("bash", "-c", command).redirectErrorStream(true)
            if (path != null) pb.environment()["PATH"] = path
            val p = pb.start()
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

    /** 一个假的 hermes 命令：只把参数记下来（不是 Python 脚本，移动会话时就走直接改库那条路）。 */
    private fun fakeHermesBin(log: java.io.File): String {
        val bin = kotlin.io.path.createTempDirectory("fake-bin").toFile()
        java.io.File(bin, "hermes").apply {
            writeText("#!/bin/bash\nprintf '%s\\n' \"${'$'}@\" >> ${log.path}\n")
            setExecutable(true)
        }
        return "${bin.path}:/usr/bin:/bin"
    }

    @Test
    fun foldersAreListedAndProjectsCreated() = runBlocking<Unit> {
        val home = kotlin.io.path.createTempDirectory("hermes-home3").toFile()
        val root = kotlin.io.path.createTempDirectory("work").toFile()
        java.io.File(root, "b-site").mkdirs()
        java.io.File(root, "A photos").mkdirs()
        java.io.File(root, "file.txt").writeText("x")
        val log = java.io.File(home, "calls.txt")
        val admin = HermesAdmin(LocalShell(home, fakeHermesBin(log)), null)

        val (dir, children) = admin.listFolders(root.path)
        assertEquals(root.canonicalPath, dir)
        assertEquals(listOf("A photos", "b-site"), children, "只列文件夹，不分大小写排序，带空格的名字也行")
        assertTrue(runCatching { admin.listFolders(root.path + "/nope") }.exceptionOrNull() is AdminException)

        admin.createProject("my 'site'", root.path + "/b-site")
        assertEquals(listOf("project", "create", "my 'site'", root.path + "/b-site"), log.readLines(), "名字原样传给 hermes project create")
        val missing = runCatching { admin.createProject("x", root.path + "/nope") }.exceptionOrNull()
        assertTrue(missing is AdminException && "没有这个文件夹" in missing.message!!, missing.toString())
    }

    @Test
    fun foldersCanBeCreated() = runBlocking<Unit> {
        val home = kotlin.io.path.createTempDirectory("hermes-home5").toFile()
        val root = kotlin.io.path.createTempDirectory("work2").toFile().canonicalFile
        val admin = HermesAdmin(LocalShell(home, fakeHermesBin(java.io.File(home, "calls.txt"))), null)
        val made = admin.makeFolder(root.path, "new project")
        assertEquals(root.path + "/new project", made)
        assertTrue(java.io.File(made).isDirectory)
        assertEquals(listOf("new project"), admin.listFolders(root.path).second)
        assertTrue(runCatching { admin.makeFolder(root.path, "new project") }.exceptionOrNull() is AdminException, "同名的不能再建")
        for (bad in listOf("", "  ", "..", "a/b")) {
            assertTrue(runCatching { admin.makeFolder(root.path, bad) }.exceptionOrNull() is AdminException, "「$bad」不该能建")
        }
        assertTrue(runCatching { admin.makeFolder(root.path + "/nope", "x") }.exceptionOrNull() is AdminException, "上级文件夹不存在")
    }

    @Test
    fun movingASessionChangesItsWorkingFolder() = runBlocking<Unit> {
        val home = kotlin.io.path.createTempDirectory("hermes-home4").toFile()
        val site = kotlin.io.path.createTempDirectory("site").toFile()
        sql(
            java.io.File(home, "state.db"),
            """
            create table sessions (id text primary key, cwd text, git_repo_root text, git_branch text, git_metadata_generation integer, title text);
            insert into sessions values ('api_1', '/old', '/old', 'main', 3, 'x');
            """.trimIndent(),
        )
        sql(
            java.io.File(home, "projects.db"),
            """
            create table projects (id text primary key, slug text, name text, primary_path text, created_at integer, archived integer default 0);
            create table project_folders (project_id text, path text, label text, is_primary integer, added_at integer);
            insert into projects values ('p1', 'site', '网站', '${site.path}', 1, 0);
            insert into project_folders values ('p1', '${site.path}', null, 1, 1);
            """.trimIndent(),
        )
        val admin = HermesAdmin(LocalShell(home, fakeHermesBin(java.io.File(home, "calls.txt"))), null)

        admin.moveSession("api_1", site.path)
        var grouping = admin.sessionGroups()
        assertEquals("网站", grouping.groupOf("api_1"))
        assertEquals(site.path, grouping.folders["api_1"])

        admin.moveSession("api_1", null)
        grouping = admin.sessionGroups()
        assertEquals("默认", grouping.groupOf("api_1"), "移出项目后回到“默认”")

        assertTrue(runCatching { admin.moveSession("api_2", site.path) }.exceptionOrNull()?.message?.contains("找不到") == true)
        assertTrue(runCatching { admin.moveSession("x; rm -rf /", site.path) }.exceptionOrNull() is AdminException, "奇怪的 id 不进 shell")
        assertTrue(runCatching { admin.moveSession("api_1", site.path + "/nope") }.exceptionOrNull() is AdminException)
    }

    /**
     * 新的源码安装：hermes 启动器支持 --print-runtime-command（自带 Python + -I + 引导代码）。
     * 用测试机上真的 Hermes 源码（_launchers.py）模拟这种启动器，脚本要在 Hermes 的引导环境里跑。
     */
    @Test
    fun scriptsRunInsideHermesOwnRuntime() {
        val root = java.io.File("/home/user/ref/hermes-head")
        val venvPython = java.io.File("/home/user/ref/hvenv/bin/python")
        if (!root.resolve("hermes_cli/_launchers.py").exists() || !venvPython.exists()) {
            println("跳过：这台机器上没有 Hermes 源码")
            return
        }
        val home = kotlin.io.path.createTempDirectory("hermes-home6").toFile()
        val bin = kotlin.io.path.createTempDirectory("new-launcher").toFile()
        java.io.File(bin, "hermes").apply {
            writeText(
                "#!/bin/sh\n" +
                    "if [ \"${'$'}1\" = \"--print-runtime-command\" ]; then exec ${venvPython.path} ${root.path}/hermes_cli/_launchers.py --print-runtime-command; fi\n" +
                    "echo 'hermes: unexpected call' >&2; exit 2\n",
            )
            setExecutable(true)
        }
        val admin = HermesAdmin(LocalShell(home, "${bin.path}:/usr/bin:/bin"), null)
        val script = """
            import sys
            import tools.bot_desktop.lease
            print("ISOLATED", sys.flags.isolated, "BOOT", "hermes_bootstrap" in sys.modules, "ARGS", sys.argv[1:])
        """.trimIndent()
        val (code, out) = LocalShell(home, "${bin.path}:/usr/bin:/bin").exec(admin.hermesPython(home.path, script, listOf("a b", "c'd")), 60)
        assertEquals(0, code, out)
        assertTrue("ISOLATED 1 BOOT True ARGS ['a b', \"c'd\"]" in out, out)
        assertTrue(home.parentFile.listFiles()!!.none { it.name.startsWith("hermes-mobile.") && it.lastModified() > System.currentTimeMillis() - 60_000 }, "临时脚本读完就删")
    }

    /** 旧的安装方式（hermes 是 venv 里的脚本，不认 --print-runtime-command）照样能跑。 */
    @Test
    fun scriptsFallBackToOldLaunchers() {
        val home = kotlin.io.path.createTempDirectory("hermes-home7").toFile()
        val log = java.io.File(home, "calls.txt")
        val shell = LocalShell(home, fakeHermesBin(log))
        val admin = HermesAdmin(shell, null)
        val (code, out) = shell.exec(admin.hermesPython(home.path, "import sys\nprint('RAN', sys.argv[1:])", listOf("x")), 60)
        assertEquals(0, code, out)
        assertTrue("RAN ['x']" in out, out)
    }

    /**
     * 照 Hermes 打包安装（Termux 的 hermes-agent 软件包）造一个假安装：$PREFIX/bin/hermes 是指向 lib/hermes-agent/bin/hermes 的链接，
     * 后者是 sh 启动器，最后一行 exec 自带的 venv/bin/python。系统 python3 里没有 hermes_cli，只有在启动器的环境里才能导入。
     * 三种写法照 hermes-agent 的 scripts/build/launchers.py（09-11 起）、scripts/bundles/payload.py（09-06）、scripts/termux/build_deb.sh（更早）。
     */
    private fun payloadInstall(launcher: (String) -> String): Pair<java.io.File, String> {
        val prefix = kotlin.io.path.createTempDirectory("termux-usr").toFile()
        val root = java.io.File(prefix, "lib/hermes-agent").apply { mkdirs() }
        java.io.File(root, "app/hermes_cli").mkdirs()
        java.io.File(root, "app/hermes_cli/__init__.py").writeText("MARK = 'bundled'\n")
        java.io.File(root, "app/hermes_cli/main.py").writeText("import sys\ndef main():\n    print('usage: hermes', file=sys.stderr)\n    return 2\n")
        val site = java.io.File(root, "venv/lib/python3/site-packages").apply { mkdirs() }
        java.io.File(root, "extra").mkdirs()
        java.io.File(root, "extra/pthmod.py").writeText("OK = True\n")
        java.io.File(site, "extra.pth").writeText("../../../../extra\n")
        java.io.File(root, "venv/bin").mkdirs()
        java.nio.file.Files.createSymbolicLink(java.io.File(root, "venv/bin/python").toPath(), java.nio.file.Paths.get("/usr/bin/python3"))
        java.io.File(root, "bin").mkdirs()
        java.io.File(root, "bin/hermes").apply { writeText(launcher("python3")); setExecutable(true) }
        java.io.File(prefix, "bin").mkdirs()
        java.nio.file.Files.createSymbolicLink(java.io.File(prefix, "bin/hermes").toPath(), java.nio.file.Paths.get("../lib/hermes-agent/bin/hermes"))
        return prefix to "${prefix.path}/bin:/usr/bin:/bin"
    }

    private val currentLauncher = { minor: String ->
        """
        #!/bin/sh
        set -eu
        self="§0"
        while [ -L "§self" ]; do
            target="§(readlink "§self")"
            case "§target" in
                /*) self="§target" ;;
                *) self="§(dirname "§self")/§target" ;;
            esac
        done
        root="§(cd "§(dirname "§self")/.." && pwd)"
        PYTHON="§root"/venv/bin/python
        REPO="§root"/app
        SITE="§root"/venv/lib/$minor/site-packages
        [ -x "§PYTHON" ] || { printf '%s\n' 'Bundled interpreter missing; reinstall Hermes.' >&2; exit 2; }
        unset PYTHONPATH PYTHONHOME
        export PYTHONPATH="§REPO:§SITE"
        export HERMES_SITE="§SITE"
        export PYTHONPYCACHEPREFIX="§{PYTHONPYCACHEPREFIX:-§{XDG_CACHE_HOME:-§HOME/.cache}/hermes-pycache}"
        PREFIX="§{PREFIX:-/data/data/com.termux/files/usr}"
        export PREFIX
        export HERMES_PYTHON="§PYTHON"
        exec "§PYTHON" -P -c 'import os, site, sys; sys.argv[0]='"'"'hermes'"'"'; site.addsitedir(os.environ['"'"'HERMES_SITE'"'"']); from hermes_cli.main import main; sys.exit(main())' "§@"
        """.trimIndent().replace('§', '$') + "\n"
    }

    @Test
    fun scriptsRunInsidePackagedLaunchers() {
        val september6 = { minor: String ->
            currentLauncher(minor).replace("export HERMES_SITE=\"§SITE\"\n".replace('§', '$'), "")
                .replace(Regex("exec .*")) { "exec \"§PYTHON\" -P -c 'import sys; sys.argv[0]='\"'\"'hermes'\"'\"'; from hermes_cli.main import main; sys.exit(main())' \"§@\"".replace('§', '$') }
        }
        val trampoline = { _: String ->
            """
            #!/bin/sh
            PREFIX="§{PREFIX:-/data/data/com.termux/files/usr}"
            self="§{0}"
            while [ -L "§self" ]; do
                tgt="§(readlink "§self")"
                case "§tgt" in
                    /*) self="§tgt" ;;
                    *) self="§(dirname "§self")/§tgt" ;;
                esac
            done
            self_dir="§(cd "§(dirname "§self")" && pwd)"
            LD_LIBRARY_PATH="§self_dir/../python§PREFIX/lib:§PREFIX/lib" \
            export LD_LIBRARY_PATH
            PYTHONPATH="§self_dir/../app" \
            exec "§self_dir/../venv/bin/python" -m hermes_cli.main "§@"
            """.trimIndent().replace('§', '$') + "\n"
        }
        for ((name, launcher) in listOf("current" to currentLauncher, "september6" to september6, "trampoline" to trampoline)) {
            val (_, path) = payloadInstall(launcher)
            val home = kotlin.io.path.createTempDirectory("hermes-home8").toFile()
            val pth = if (name == "current") "import pthmod\n" else ""  // 只有 09-11 起的启动器处理 .pth（HERMES_SITE）
            val script = "import sys, hermes_cli\n${pth}print('RAN', hermes_cli.MARK, sys.argv[1:], sys.stdin.read())"
            // Termux 发的命令开头带 set -eu（TermuxRuntimeBackend.Header）
            val command = "set -eu\n" + HermesAdmin.runtimePython(home.path, script, listOf("a b", "c'd"))
            val pb = ProcessBuilder("bash", "-c", command).redirectErrorStream(true)
            pb.environment()["PATH"] = path
            pb.environment()["TMPDIR"] = home.path
            val p = pb.start()
            p.outputStream.use { it.write("{\"action\":\"inspect\"}".toByteArray()) }
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            assertEquals(0, p.waitFor(), "$name: $out")
            assertTrue("RAN bundled ['a b', \"c'd\"] {\"action\":\"inspect\"}" in out, "$name: $out")
            assertTrue(home.listFiles()!!.none { it.name.startsWith("hermes-mobile.") }, "$name: 临时脚本读完就删")
        }
    }

    /** 自带 Python 坏了（启动器自己报错退出）时如实失败，不退回系统 python3 假装能跑。 */
    @Test
    fun brokenPackagedInterpreterIsReportedNotReplaced() {
        val (prefix, path) = payloadInstall(currentLauncher)
        java.io.File(prefix, "lib/hermes-agent/venv/bin/python").delete()
        val home = kotlin.io.path.createTempDirectory("hermes-home9").toFile()
        val pb = ProcessBuilder("bash", "-c", "set -eu\n" + HermesAdmin.runtimePython(home.path, "print('RAN')", emptyList())).redirectErrorStream(true)
        pb.environment()["PATH"] = path
        pb.environment()["TMPDIR"] = home.path
        val p = pb.start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(2, p.waitFor(), out)
        assertTrue("Bundled interpreter missing" in out && "RAN" !in out, out)
        assertTrue(home.listFiles()!!.none { it.name.startsWith("hermes-mobile.") }, "失败时也删掉临时脚本")
    }
}

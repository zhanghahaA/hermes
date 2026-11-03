package com.hermes.mobile.data.local

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class TermuxRepositoryTest {
    private val environment = RuntimeEnvironment(true, true, true, true, 4L * 1024 * 1024 * 1024)

    @Test fun missingKeyOrIndexStopsBeforeDependenciesAndNeverSwitchesChannel() {
        for (missing in listOf("key.asc", "InRelease")) {
            val result = runInstaller(missing, 404)
            assertEquals(44, result.code)
            assertTrue(result.output.contains("HM_REPOSITORY_UNAVAILABLE"))
            assertFalse(result.calls.any { it.startsWith("apt ") || it == "hermes" })
            assertTrue(result.calls.filter { it.startsWith("curl ") }.all { it.contains("/stable/") })
            assertFalse(result.wroteRepository)
        }
    }

    @Test fun removedSourceIsDifferentFromServerFailure() {
        assertEquals(44, runInstaller("key.asc", 410).code)
        val failure = runInstaller("key.asc", 503)
        assertEquals(22, failure.code)
        assertFalse(failure.output.contains("HM_REPOSITORY_UNAVAILABLE"))
        assertFalse(failure.wroteRepository)
    }

    @Test fun availableSelectedChannelStillRequiresPinnedKeyBeforeWritingSource() {
        for (channel in ReleaseChannel.entries) {
            val result = runInstaller(channel = channel)
            assertEquals(0, result.code, result.output)
            assertTrue(result.calls.take(2).all { it.startsWith("curl ") && it.contains("/${channel.id}/") })
            assertTrue(result.calls[2].endsWith("update"))
            assertTrue(result.calls[3].endsWith("install -y gnupg"))
            assertTrue(result.wroteRepository)
            assertTrue(result.source.contains("/${channel.id} hermes-${channel.id} main"))
            assertEquals("hermes", result.calls.last())
        }
        val badKey = runInstaller(fingerprint = "INVALID_FIXTURE_FINGERPRINT")
        assertEquals(23, badKey.code)
        assertFalse(badKey.wroteRepository)
        assertFalse(badKey.calls.any { it.endsWith("install -y hermes-agent") })
    }

    @Test fun backendMapsOnlyExplicitRepositoryFailureAndManagerStopsBeforePython() = runBlocking {
        val scripts = mutableListOf<String>()
        val backend = TermuxRuntimeBackend(object : TermuxTransport {
            override fun environment() = environment
            override fun openViewer() = Unit
            override suspend fun run(script: String, input: String, readOnly: Boolean): TermuxCommandResult {
                scripts += script
                return when {
                    script.contains("hm_fetch()") -> TermuxCommandResult(44, stderr = "HM_REPOSITORY_UNAVAILABLE\n")
                    script.contains("HM_PYTHON_MISSING") -> TermuxCommandResult(0, "HM_PYTHON_MISSING")
                    else -> TermuxCommandResult(0)
                }
            }
        }, "pass")
        val manager = LocalRuntimeManager(this, backend, object : LocalConnectionAccess {
            override fun save(apiKey: String) = error("失败时不能保存凭据")
            override suspend fun verifyAndActivate() = error("失败时不能连接")
        })
        manager.install().join()
        assertEquals(RuntimeIssue.RepositoryUnavailable, manager.state.value.issue)
        assertEquals(RuntimeSetupStep.SourceUnavailable, manager.state.value.setupStep())
        assertFalse(scripts.any { it.contains("hm_apt install -y python") })
        assertEquals(RuntimeCheckStatus.Passed, manager.state.value.diagnosticChecks().single { it.id == "command" }.status)
        assertFalse(manager.state.value.diagnosticReport().contains("HM_REPOSITORY"))
    }

    @Test fun missingAiohttpIsInstalledWithBundledUvAtHermesPinnedVersion() {
        val result = runApiServerDependencies()
        assertEquals(0, result.code, result.output)
        val uv = result.calls.single { it.startsWith("uv ") }
        assertTrue(uv.contains("pip install --no-config --default-index https://pypi.tuna.tsinghua.edu.cn/simple"), uv)
        assertTrue(uv.contains("/venv/bin/python aiohttp==3.14.3"), uv)
        assertTrue(result.calls.count { it == "python import aiohttp" } == 2, "装完要再确认一次能 import")
    }

    @Test fun presentAiohttpOrUnpackagedHermesNeedsNoDownload() {
        val present = runApiServerDependencies(hasAiohttp = true)
        assertEquals(0, present.code, present.output)
        assertFalse(present.calls.any { it.startsWith("uv ") })
        val unpackaged = runApiServerDependencies(packaged = false)
        assertEquals(0, unpackaged.code, unpackaged.output)
        assertTrue(unpackaged.calls.isEmpty())
    }

    @Test fun failedDependencyInstallHasItsOwnMarker() {
        for (result in listOf(runApiServerDependencies(uvFails = true), runApiServerDependencies(uvPresent = false))) {
            assertEquals(45, result.code, result.output)
            assertTrue(result.output.lines().contains("HM_API_DEPENDENCY_MISSING"))
        }
    }

    @Test fun backendChecksDependenciesBeforeStartAndMapsFailure() = runBlocking {
        val scripts = mutableListOf<String>()
        val backend = TermuxRuntimeBackend(object : TermuxTransport {
            override fun environment() = environment
            override fun openViewer() = Unit
            override suspend fun run(script: String, input: String, readOnly: Boolean): TermuxCommandResult {
                scripts += script
                return if (script.contains("HM_API_DEPENDENCY_MISSING")) TermuxCommandResult(45, stderr = "HM_API_DEPENDENCY_MISSING\n")
                else TermuxCommandResult(0)
            }
        }, "pass")
        val failure = assertFailsWith<RuntimeCommandException> { backend.execute(RuntimeRequest(RuntimeAction.StartHermes)) {} }
        assertEquals(RuntimeIssue.ApiServerDependency, failure.issue)
        assertEquals(1, scripts.size, "依赖没补上时不能去启动 gateway")
        val state = LocalRuntimeState(environment, issue = RuntimeIssue.ApiServerDependency, failed = true)
        assertEquals(RuntimeSetupStep.Reconnect, state.setupStep())
    }

    /** 执行生产补依赖脚本：假的打包安装目录 + 记录调用的 python / uv 替代程序，不触网。 */
    private fun runApiServerDependencies(hasAiohttp: Boolean = false, packaged: Boolean = true,
                                         uvPresent: Boolean = true, uvFails: Boolean = false): Result {
        val folder = Files.createTempDirectory("hermes-api-deps").toFile()
        try {
            val log = folder.resolve("calls")
            val root = folder.resolve("lib/hermes-agent")
            fun executable(file: java.io.File, body: String) {
                file.parentFile.mkdirs()
                file.writeText("#!/bin/sh\n" + body.replace('§', '$') + "\n")
                check(file.setExecutable(true))
            }
            executable(root.resolve("bin/hermes"), "exit 0")
            val bin = folder.resolve("bin").apply { mkdir() }
            java.nio.file.Files.createSymbolicLink(bin.resolve("hermes").toPath(), root.resolve("bin/hermes").toPath())
            root.resolve("app").mkdirs()
            root.resolve("app/pyproject.toml").writeText("messaging = [\n  \"aiohttp==3.14.3\",\n]\nsms = [\"aiohttp==3.14.3\"]\n")
            val marker = folder.resolve("installed")
            if (hasAiohttp) marker.writeText("yes")
            if (packaged) executable(root.resolve("venv/bin/python"), """
                printf 'python %s\n' "§2" >> "§TEST_LOG"
                [ -f "§MARKER" ]
            """.trimIndent())
            if (uvPresent) executable(root.resolve("tools/uv/data/data/com.termux/files/usr/bin/uv"), """
                printf 'uv %s\n' "§*" >> "§TEST_LOG"
                [ -z "§UV_FAILS" ] || exit 1
                printf yes > "§MARKER"
            """.trimIndent())
            val builder = ProcessBuilder("/bin/bash", "-c", "set -eu\numask 077\n" + TermuxRuntimeBackend.ApiServerDependencies)
            builder.environment().apply {
                put("PREFIX", folder.resolve("prefix").absolutePath)
                put("PATH", "${bin.absolutePath}:/usr/bin:/bin")
                put("TEST_LOG", log.absolutePath)
                put("MARKER", marker.absolutePath)
                put("UV_FAILS", if (uvFails) "1" else "")
            }
            val process = builder.redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            return Result(code, if (log.exists()) log.readLines() else emptyList(), output, false, "")
        } finally { folder.deleteRecursively() }
    }

    private data class Result(val code: Int, val calls: List<String>, val output: String,
                              val wroteRepository: Boolean, val source: String)

    /** 执行生产安装脚本；下载、包管理、签名工具和 Hermes 均由隔离的替代程序记录，不触网或装包。 */
    private fun runInstaller(missing: String = "", status: Int = 200,
                             channel: ReleaseChannel = ReleaseChannel.Stable,
                             fingerprint: String = TermuxRuntimeBackend.Fingerprint): Result {
        val folder = Files.createTempDirectory("hermes-source-fixture").toFile()
        try {
            val bin = folder.resolve("bin").apply { mkdir() }
            fun stub(name: String, body: String) {
                val executable = bin.resolve(name)
                executable.writeText("#!/bin/sh\n" + body.replace('§', '$') + "\n")
                check(executable.setExecutable(true))
            }
            stub("uname", "echo aarch64")
            stub("curl", """
                url=''
                target=''
                while [ "§#" -gt 0 ]; do
                    case "§1" in
                        https://*) url=§1 ;;
                        -o) shift; target=§1 ;;
                    esac
                    shift
                done
                printf 'curl %s\n' "§url" >> "§TEST_LOG"
                case "§url" in
                    *"§MISSING") if [ -n "§MISSING" ]; then printf '%s' "§HTTP_STATUS"; exit 22; fi ;;
                esac
                printf fixture > "§target"
                printf 200
            """.trimIndent())
            stub("apt-get", "printf 'apt %s\\n' \"§*\" >> \"§TEST_LOG\"")
            stub("gpg", "printf 'pub:::::::::\\nfpr:::::::::%s:\\n' \"§FINGERPRINT\"")
            stub("hermes", "echo hermes >> \"§TEST_LOG\"")
            // 仅去掉 Android 路径限制前缀，安装脚本正文原样执行，写入目录限制在临时测试目录。
            val script = TermuxRuntimeBackend.installScript(channel, false).removePrefix(TermuxRuntimeBackend.Header)
            val builder = ProcessBuilder("/bin/bash", "-c", "set -eu\numask 077\n" + script)
            val log = folder.resolve("calls")
            builder.environment().apply {
                put("PREFIX", folder.resolve("prefix").absolutePath)
                put("HERMES_MOBILE_ROOT", folder.resolve("runtime").absolutePath)
                put("PATH", "${bin.absolutePath}:/usr/bin:/bin")
                put("TMPDIR", folder.absolutePath)
                put("TEST_LOG", log.absolutePath)
                put("MISSING", missing)
                put("HTTP_STATUS", status.toString())
                put("FINGERPRINT", fingerprint)
            }
            val process = builder.redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            val source = folder.resolve("prefix/etc/apt/sources.list.d/hermes-agent.list")
            return Result(code, if (log.exists()) log.readLines() else emptyList(), output,
                source.exists(), if (source.exists()) source.readText() else "")
        } finally { folder.deleteRecursively() }
    }
}

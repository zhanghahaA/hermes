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
            assertFalse(result.calls.any { it.startsWith("pkg ") || it == "hermes" })
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
            assertEquals("pkg install -y gnupg", result.calls[2])
            assertTrue(result.wroteRepository)
            assertTrue(result.source.contains("/${channel.id} hermes-${channel.id} main"))
            assertEquals("hermes", result.calls.last())
        }
        val badKey = runInstaller(fingerprint = "INVALID_FIXTURE_FINGERPRINT")
        assertEquals(23, badKey.code)
        assertFalse(badKey.wroteRepository)
        assertFalse(badKey.calls.contains("pkg install -y hermes-agent"))
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
        assertFalse(scripts.any { it.contains("pkg install -y python") })
        assertEquals(RuntimeCheckStatus.Passed, manager.state.value.diagnosticChecks().single { it.id == "command" }.status)
        assertFalse(manager.state.value.diagnosticReport().contains("HM_REPOSITORY"))
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
            stub("pkg", "printf 'pkg %s\\n' \"§*\" >> \"§TEST_LOG\"")
            stub("gpg", "printf 'pub:::::::::\\nfpr:::::::::%s:\\n' \"§FINGERPRINT\"")
            stub("hermes", "echo hermes >> \"§TEST_LOG\"")
            // 仅去掉 Android 路径限制前缀，安装脚本正文原样执行，写入目录限制在临时测试目录。
            val script = TermuxRuntimeBackend.installScript(channel, false).removePrefix(TermuxRuntimeBackend.Header)
            val builder = ProcessBuilder("/bin/bash", "-c", "set -eu\numask 077\n" + script)
            val log = folder.resolve("calls")
            builder.environment().apply {
                put("PREFIX", folder.resolve("prefix").absolutePath)
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

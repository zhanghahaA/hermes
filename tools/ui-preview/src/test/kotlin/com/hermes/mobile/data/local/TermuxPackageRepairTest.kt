package com.hermes.mobile.data.local

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class TermuxPackageRepairTest {
    private val environment = RuntimeEnvironment(true, true, true, true, 4L * 1024 * 1024 * 1024)
    private val linkerError = """CANNOT LINK EXECUTABLE "curl": cannot locate symbol "SSL_set_quic_tls_early_data_enabled" referenced by "/private/fixture/libcurl.so"""

    @Test fun inspectDetectsLinkerConflictWithoutInstallingAndReportsSafeCause() = runBlocking {
        val commands = mutableListOf<String>()
        val backend = TermuxRuntimeBackend(object : TermuxTransport {
            override fun environment() = environment
            override fun openViewer() = Unit
            override suspend fun run(script: String, input: String, readOnly: Boolean): TermuxCommandResult {
                assertTrue(readOnly)
                commands += script
                return when {
                    script.contains("curl --version") -> TermuxCommandResult(1, stderr = linkerError)
                    script.contains("HM_PYTHON_MISSING") -> TermuxCommandResult(0, "HM_PYTHON_MISSING")
                    else -> TermuxCommandResult(0)
                }
            }
        }, "pass")
        val manager = LocalRuntimeManager(this, backend, access())
        manager.inspect().join()
        val state = manager.state.value
        assertEquals(RuntimeSetupStep.Repair, state.setupStep())
        assertEquals(RuntimeIssue.PackageMismatch, state.issue)
        assertFalse(state.message.contains("/private/fixture"))
        assertTrue(state.diagnosticReport().contains("版本不匹配"))
        assertEquals(RuntimeCheckStatus.Passed, state.diagnosticChecks().single { it.id == "command" }.status)
        assertFalse(commands.any { it.contains("apt-get") || it.contains("pkg install") })
        assertEquals(RuntimeSetupStep.Storage, state.copy(environment = environment.copy(freeBytes = 0)).setupStep())
    }

    @Test fun networkFailureDoesNotRecommendUpgradingEveryPackage() = runBlocking {
        val backend = TermuxRuntimeBackend(object : TermuxTransport {
            override fun environment() = environment
            override fun openViewer() = Unit
            override suspend fun run(script: String, input: String, readOnly: Boolean) =
                TermuxCommandResult(1, stderr = "curl: could not resolve host")
        }, "pass")
        val failure = assertFailsWith<RuntimeCommandException> { backend.execute(RuntimeRequest(RuntimeAction.Prepare)) {} }
        assertEquals(RuntimeIssue.CommandFailed, failure.issue)
    }

    @Test fun failedRepairDoesNotProceedToInstallOrSaveCredentials() = runBlocking {
        val calls = mutableListOf<RuntimeAction>()
        val manager = LocalRuntimeManager(this, object : LocalRuntimeBackend {
            override val name = "fixture"
            override val externalApps = false
            override fun environment() = environment
            override suspend fun execute(request: RuntimeRequest, progress: (String) -> Unit): RuntimeResponse {
                calls += request.action
                error("fixture APT update failed")
            }
        }, access())
        manager.install(repairEnvironment = true).join()
        assertEquals(listOf(RuntimeAction.RepairEnvironment), calls)
        assertTrue(manager.state.value.failed)
    }

    @Test fun repairRunsAptDirectlyPreservesConfigurationAndRefusesRemovals() {
        val (code, calls) = runRepair()
        assertEquals(0, code)
        assertEquals(3, calls.size)
        assertTrue(calls[0].contains("APT::Update::Error-Mode=any update"))
        assertTrue(calls[1].contains("--no-remove"))
        assertTrue(calls[1].contains("--force-confold"))
        assertTrue(calls[1].endsWith("dist-upgrade"))
        assertEquals("curl --version", calls[2])
    }

    @Test fun aptFailureStopsBeforeNextStepAndCurlFailureIsNotSuccess() {
        val update = runRepair("update")
        assertEquals(9, update.first)
        assertEquals(1, update.second.size)
        val upgrade = runRepair("upgrade")
        assertEquals(9, upgrade.first)
        assertEquals(2, upgrade.second.size)
        val curl = runRepair("curl")
        assertEquals(9, curl.first)
        assertEquals(3, curl.second.size)
    }

    private fun access() = object : LocalConnectionAccess {
        override fun save(apiKey: String) = error("本测试不应写入连接")
        override suspend fun verifyAndActivate() = error("本测试不应切换连接")
    }

    /** 执行实际修复脚本，APT/curl 由临时程序替代，绝不调用系统包管理器或访问网络。 */
    private fun runRepair(fail: String = ""): Pair<Int, List<String>> {
        val folder = Files.createTempDirectory("hermes-apt-fixture").toFile()
        try {
            val log = folder.resolve("calls")
            val apt = folder.resolve("apt-get")
            apt.writeText("""
                #!/bin/sh
                printf 'apt-get %s\n' "§*" >> "§TEST_LOG"
                case "§*" in
                    *' update') [ "§FAIL_STEP" != update ] || exit 9 ;;
                    *' dist-upgrade') [ "§FAIL_STEP" != upgrade ] || exit 9 ;;
                    *) exit 99 ;;
                esac
            """.trimIndent().replace('§', '$') + "\n")
            check(apt.setExecutable(true))
            val curl = folder.resolve("curl")
            curl.writeText("""
                #!/bin/sh
                printf 'curl %s\n' "§*" >> "§TEST_LOG"
                [ "§FAIL_STEP" != curl ] || exit 9
            """.trimIndent().replace('§', '$') + "\n")
            check(curl.setExecutable(true))
            val builder = ProcessBuilder("/bin/bash", "-c", "set -eu\n" + TermuxRuntimeBackend.RepairPackages)
            builder.environment().apply {
                put("PATH", folder.absolutePath + ":/usr/bin:/bin")
                put("HERMES_MOBILE_ROOT", folder.resolve("runtime").absolutePath)
                put("PREFIX", folder.resolve("prefix").absolutePath)
                put("TEST_LOG", log.absolutePath)
                put("FAIL_STEP", fail)
            }
            builder.redirectErrorStream(true)
            val process = builder.start()
            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            assertEquals("", output)
            return code to log.readLines()
        } finally { folder.deleteRecursively() }
    }
}

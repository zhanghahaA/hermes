package com.hermes.mobile.data.local

import kotlinx.coroutines.*
import kotlin.test.*

class RuntimeDiagnosticsTest {
    private val ready = RuntimeEnvironment(true, true, true, true, 4L * 1024 * 1024 * 1024, version = "0.118.3")

    @Test fun actualVersionParsingAcceptsCompatibleVersionsAndRejectsUnknownOrOldOnes() {
        listOf("0.118", "0.118.3", "0.118.3-beta", "0.119.0", "1.0.0").forEach { assertTrue(supportsTermuxVersion(it), it) }
        listOf("", "unknown", "0.117.9", "0.118x", "999999999999999999999.0").forEach { assertFalse(supportsTermuxVersion(it), it) }
    }

    @Test fun missingPermissionAndMissingAndroidRegistrationHaveDifferentRemedies() {
        val denied = ready.copy(permission = false).checks().single { it.id == "permission" }
        assertEquals(RuntimeCheckStatus.Blocked, denied.status)
        assertEquals(RuntimeRemedy.Permission, denied.remedy)
        val unregistered = ready.copy(permission = false, permissionRegistered = false)
        val row = unregistered.checks().single { it.id == "permission" }
        assertEquals(RuntimeRemedy.PermissionSettings, row.remedy)
        assertTrue(row.detail.contains("覆盖安装"))
        assertTrue(unregistered.blockers.contains("系统尚未识别 Termux 执行权限"))
    }

    @Test fun unavailableServiceAndInsufficientSpaceExplainDisabledInstallation() {
        val env = ready.copy(commandServiceAvailable = false)
        assertEquals(RuntimeCheckStatus.Blocked, env.checks().single { it.id == "compatible" }.status)
        assertTrue(LocalRuntimeState(env).installBlockReason()!!.contains("命令执行入口"))
        val lowSpace = ready.copy(freeBytes = 100 * 1024 * 1024)
        assertTrue(lowSpace.blockers.isEmpty(), "低空间不应阻止只读命令检查")
        assertEquals(RuntimeRemedy.Storage, lowSpace.checks().single { it.id == "storage" }.remedy)
        assertTrue(LocalRuntimeState(lowSpace).installBlockReason()!!.contains("空间不足"))
    }

    @Test fun commandProbeDoesNotClaimSuccessBeforeResponseAndCopyExcludesRawDetails() {
        val state = LocalRuntimeState(ready, message = "sensitive-account", output = "sensitive-key")
        assertEquals(RuntimeCheckStatus.Pending, state.diagnosticChecks().single { it.id == "command" }.status)
        val timeout = state.copy(probe = RuntimeProbe.TimedOut)
        assertEquals(RuntimeCheckStatus.Blocked, timeout.diagnosticChecks().single { it.id == "command" }.status)
        assertTrue(timeout.diagnosticReport().contains("不要重复安装"))
        assertFalse(timeout.diagnosticReport().contains("sensitive-"))
        assertEquals(RuntimeCheckStatus.Passed, state.copy(probe = RuntimeProbe.Passed).diagnosticChecks().single { it.id == "command" }.status)
    }

    @Test fun permissionChangeInvalidatesOldProbeButFreeSpaceChangesDoNot() = runBlocking {
        var env = ready
        val manager = LocalRuntimeManager(this, object : LocalRuntimeBackend {
            override val name = "test"
            override val externalApps = true
            override fun environment() = env
            override suspend fun execute(request: RuntimeRequest, progress: (String) -> Unit) = RuntimeResponse(RuntimeSnapshot())
        }, object : LocalConnectionAccess {
            override fun save(apiKey: String) = Unit
            override suspend fun verifyAndActivate() = Unit
        })
        manager.inspect().join()
        assertEquals(RuntimeProbe.Passed, manager.state.value.probe)
        env = ready.copy(freeBytes = ready.freeBytes - 4096)
        manager.refreshEnvironment()
        assertEquals(RuntimeProbe.Passed, manager.state.value.probe)
        env = env.copy(permission = false)
        manager.refreshEnvironment()
        assertEquals(RuntimeProbe.NotChecked, manager.state.value.probe)
        assertFalse(manager.state.value.checked)
        assertEquals(RuntimeCheckStatus.Pending, manager.state.value.diagnosticChecks().single { it.id == "command" }.status)
    }
}

package com.hermes.mobile.data.local

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class TermuxResultTest {
    private val env = RuntimeEnvironment(true, true, true, true, 4L * 1024 * 1024 * 1024)
    @Test fun termuxSuccessIsMinusOneAndShellExitCodeIsIndependent() {
        val ok = decodeTermuxResult(-1, 0, "HM_PYTHON_READY", "", "")
        assertNull(ok.issue)
        assertEquals(0, ok.exitCode)
        assertEquals("HM_PYTHON_READY", ok.stdout)
        val shellError = decodeTermuxResult(-1, 127, "", "missing command", "")
        assertNull(shellError.issue)
        assertEquals(127, shellError.exitCode)
    }
    @Test fun canceledZeroAndMissingResultAreNotSuccess() {
        assertEquals(RuntimeIssue.Rejected, decodeTermuxResult(0, 0, "", "", "canceled").issue)
        assertEquals(RuntimeIssue.InvalidResult, decodeTermuxResult(null, 0, "", "", "").issue)
        assertEquals(RuntimeIssue.InvalidResult, decodeTermuxResult(-1, null, "", "", "").issue)
    }
    @Test fun rejectionClassifiesRemedyWithoutExposingCommandOrCredentials() = runBlocking {
        val result = decodeTermuxResult(1500, null, "private-output", "private-error", "allow-external-apps=false command secret-token")
        assertEquals(RuntimeIssue.ExternalApps, result.issue)
        assertEquals("", result.stdout)
        assertEquals("", result.stderr)
        val backend = TermuxRuntimeBackend(object : TermuxTransport {
            override fun environment() = env
            override fun openViewer() = Unit
            override suspend fun run(script: String, input: String, readOnly: Boolean) = result
        }, "pass")
        val manager = LocalRuntimeManager(this, backend, object : LocalConnectionAccess {
            override fun save(apiKey: String) = Unit
            override suspend fun verifyAndActivate() = Unit
        })
        manager.inspect().join()
        val state = manager.state.value
        assertEquals(RuntimeSetupStep.Initialize, state.setupStep())
        assertTrue(state.diagnosticReport().contains("1500"))
        assertTrue(state.diagnosticReport().contains("尚未允许外部调用"))
        assertFalse(state.toString().contains("secret-token"))
        assertFalse(state.diagnosticReport().contains("private-"))
    }
    @Test fun successfulUpstreamCallbackMakesBackendProbePass() = runBlocking {
        val backend = TermuxRuntimeBackend(object : TermuxTransport {
            override fun environment() = env
            override fun openViewer() = Unit
            override suspend fun run(script: String, input: String, readOnly: Boolean) = decodeTermuxResult(-1, 0,
                if (script.contains("HM_PYTHON_MISSING")) "HM_PYTHON_MISSING\n" else "", "", "")
        }, "pass")
        val result = backend.execute(RuntimeRequest(RuntimeAction.Inspect)) {}
        assertFalse(result.snapshot.toolsReady)
        assertFalse(result.snapshot.hermesInstalled)
    }
}

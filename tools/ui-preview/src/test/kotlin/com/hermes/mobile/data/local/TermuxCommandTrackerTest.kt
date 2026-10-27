package com.hermes.mobile.data.local

import kotlinx.coroutines.*
import kotlin.test.*

class TermuxCommandTrackerTest {
    @Test fun lostInspectionCallbackDoesNotBlockNextCheck() = runBlocking {
        val commands = TermuxCommandTracker()
        val first = commands.begin(readOnly = true)
        var disposed = 0
        commands.registerCallback(first) { disposed++ }
        assertFailsWith<TimeoutCancellationException> { withTimeout(15) { commands.await(first) } }
        assertEquals(1, disposed)
        val retry = commands.begin(readOnly = true)
        commands.finish(retry.id, TermuxCommandResult(0, "checked"))
        assertEquals("checked", commands.await(retry).stdout)
    }

    @Test fun lateOrDuplicateOldResultCannotCompleteOrReleaseNewCall() = runBlocking {
        val commands = TermuxCommandTracker()
        val old = commands.begin(readOnly = true)
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { commands.await(old) }
        waiting.cancelAndJoin()
        val current = commands.begin(readOnly = true)
        commands.finish(old.id, TermuxCommandResult(0, "stale"))
        assertFailsWith<RuntimeCommandException> { commands.begin(readOnly = false) }
        commands.finish(current.id, TermuxCommandResult(0, "current"))
        assertEquals("current", commands.await(current).stdout)
        val next = commands.begin(readOnly = false)
        commands.finish(current.id, TermuxCommandResult(0, "duplicate"))
        assertFailsWith<RuntimeCommandException> { commands.begin(readOnly = true) }
        commands.finish(next.id, TermuxCommandResult(0))
    }

    @Test fun timedOutInstallationRetainsProtectionUntilActualCallback() = runBlocking {
        val commands = TermuxCommandTracker()
        val install = commands.begin(readOnly = false)
        var disposed = false
        commands.registerCallback(install) { disposed = true }
        assertFailsWith<TimeoutCancellationException> { withTimeout(15) { commands.await(install) } }
        assertFalse(disposed)
        val failure = assertFailsWith<RuntimeCommandException> { commands.begin(readOnly = false) }
        assertEquals(RuntimeIssue.CommandPending, failure.issue)
        assertFailsWith<RuntimeCommandException> { commands.begin(readOnly = true) }
        commands.finish(install.id, TermuxCommandResult(0))
        assertTrue(disposed)
        val next = commands.begin(readOnly = true)
        commands.finish(next.id, TermuxCommandResult(0))
        assertEquals(0, commands.await(next).exitCode)
    }

    @Test fun creationOrLaunchFailureReleasesSlotAndDoesNotMaskResult() = runBlocking {
        val commands = TermuxCommandTracker()
        val call = commands.begin(readOnly = false)
        commands.registerCallback(call) { error("fixture callback already canceled") }
        commands.finish(call.id, TermuxCommandResult(-1, issue = RuntimeIssue.LaunchFailed))
        assertEquals(RuntimeIssue.LaunchFailed, commands.await(call).issue)
        val retry = commands.begin(readOnly = true)
        commands.finish(retry.id, TermuxCommandResult(0))
    }

    @Test fun cancellationBeforeCallbackRegistrationDisposesLateCallback() = runBlocking {
        val commands = TermuxCommandTracker()
        val call = commands.begin(readOnly = true)
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { commands.await(call) }
        waiting.cancelAndJoin()
        var disposed = false
        commands.registerCallback(call) { disposed = true }
        assertTrue(disposed)
    }

    @Test fun realBackendUsesRetryableChecksButKeepsPackageAndConfigurationCommandsProtected() = runBlocking {
        val requests = mutableListOf<Pair<String, Boolean>>()
        val transport = object : TermuxTransport {
            override fun environment() = RuntimeEnvironment(true, true, true, true, 4L * 1024 * 1024 * 1024)
            override fun openViewer() = Unit
            override suspend fun run(script: String, input: String, readOnly: Boolean): TermuxCommandResult {
                requests += (script + input) to readOnly
                return TermuxCommandResult(0, when {
                    script.contains("HM_PYTHON_READY") -> "HM_PYTHON_READY"
                    input.contains("ConfigureHermes") -> "HMRUNTIME{\"snapshot\":{\"toolsReady\":true},\"connectionKey\":\"${"a".repeat(64)}\"}"
                    input.isNotEmpty() -> "HMRUNTIME{\"snapshot\":{\"toolsReady\":true}}"
                    else -> ""
                })
            }
        }
        val backend = TermuxRuntimeBackend(transport, "pass")
        backend.execute(RuntimeRequest(RuntimeAction.Inspect)) {}
        assertTrue(requests.isNotEmpty() && requests.all { it.second })
        requests.clear()
        backend.execute(RuntimeRequest(RuntimeAction.Prepare)) {}
        assertFalse(requests.single { it.first.contains("pkg install") }.second)
        assertTrue(requests.last().second)
        requests.clear()
        backend.execute(RuntimeRequest(RuntimeAction.ConfigureHermes)) {}
        assertFalse(requests.last().second)
    }

    @Test fun managerCanInspectAgainAfterLostCallbackUsingProductionTracker() = runBlocking {
        val tracker = TermuxCommandTracker()
        var loseResult = true
        var sent = 0
        val transport = object : TermuxTransport {
            override fun environment() = RuntimeEnvironment(true, true, true, true, 4L * 1024 * 1024 * 1024)
            override fun openViewer() = Unit
            override suspend fun run(script: String, input: String, readOnly: Boolean): TermuxCommandResult {
                sent++
                val call = tracker.begin(readOnly)
                if (!loseResult) tracker.finish(call.id, TermuxCommandResult(0,
                    if (script.contains("HM_PYTHON_MISSING")) "HM_PYTHON_MISSING" else ""))
                return tracker.await(call)
            }
        }
        val manager = LocalRuntimeManager(this, TermuxRuntimeBackend(transport, "pass"), object : LocalConnectionAccess {
            override fun save(apiKey: String) = Unit
            override suspend fun verifyAndActivate() = Unit
        }, timeoutMillis = 30)
        manager.inspect().join()
        assertEquals(RuntimeProbe.TimedOut, manager.state.value.probe)
        assertEquals(RuntimeSetupStep.Recover, manager.state.value.setupStep())
        assertTrue(manager.state.value.diagnosticReport().contains("不必重复初始化"))
        loseResult = false
        manager.inspect().join()
        assertEquals(RuntimeProbe.Passed, manager.state.value.probe)
        assertEquals(RuntimeSetupStep.Install, manager.state.value.setupStep())
        assertFalse(manager.state.value.failed)
        assertEquals(3, sent)
    }
}

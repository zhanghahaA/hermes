package com.hermes.mobile.data.local

import kotlinx.coroutines.*
import kotlin.test.*

class LocalRuntimeTest {
    private val environment = RuntimeEnvironment(true, true, true, true, 4L * 1024 * 1024 * 1024, true, "0.118.3")
    private class Access : LocalConnectionAccess {
        var saved: String? = null
        var activated = false
        override fun save(apiKey: String) { saved = apiKey }
        override suspend fun verifyAndActivate() { activated = true }
    }
    private class Backend(val env: RuntimeEnvironment) : LocalRuntimeBackend {
        override val name = "test"
        override val externalApps = false
        val calls = mutableListOf<RuntimeAction>()
        var delayUntil: CompletableDeferred<Unit>? = null
        override fun environment() = env
        override suspend fun execute(request: RuntimeRequest, progress: (String) -> Unit): RuntimeResponse {
            calls += request.action
            delayUntil?.await()
            return RuntimeResponse(RuntimeSnapshot(true, true, true, true), connectionKey = "test-local-key".takeIf { request.action == RuntimeAction.ConfigureHermes })
        }
    }

    @Test fun deploymentSavesCredentialsBeforeActivation() = runBlocking {
        val backend = Backend(environment)
        val access = Access()
        val manager = LocalRuntimeManager(this, backend, access)
        manager.deploy(LocalModelSetup("fake-model", "test-key"), ReleaseChannel.Stable, true).join()
        assertEquals(listOf(RuntimeAction.InstallHermes, RuntimeAction.Prepare, RuntimeAction.ConfigureHermes, RuntimeAction.StartHermes), backend.calls)
        assertEquals("test-local-key", access.saved)
        assertTrue(access.activated)
        assertFalse(manager.state.value.failed)
        assertFalse(manager.state.value.toString().contains("test-local-key"))
    }

    @Test fun missingAuthorizationExecutesNothing() = runBlocking {
        val backend = Backend(environment.copy(permission = false))
        val manager = LocalRuntimeManager(this, backend, Access())
        manager.inspect().join()
        assertTrue(backend.calls.isEmpty())
        assertTrue(manager.state.value.failed)
        assertFalse(manager.state.value.busy)
    }

    @Test fun savingLocalCredentialsDoesNotActivateAnUncheckedConnection() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = com.hermes.mobile.data.remote.RemoteHermesRepository(scope,
                com.hermes.mobile.platform.MemoryLocalStore(), com.hermes.mobile.platform.FakeSecretBox())
            RepositoryLocalConnection { repo }.save("test-local-key")
            assertEquals(LocalRuntimeManager.ConnectionId, repo.connections.value.single().id)
            assertNull(repo.activeConnectionId.value)
            assertEquals(com.hermes.mobile.data.model.ConnectionState.Disconnected, repo.connectionState.value)
        } finally { scope.cancel() }
    }

    @Test fun repeatedClicksDoNotRunConcurrentCommands() = runBlocking {
        val backend = Backend(environment)
        backend.delayUntil = CompletableDeferred()
        val manager = LocalRuntimeManager(this, backend, Access())
        val first = manager.inspect()
        yield()
        manager.inspect().join()
        assertEquals(1, backend.calls.size)
        backend.delayUntil!!.complete(Unit)
        first.join()
        assertFalse(manager.state.value.busy)
    }

    @Test fun timeoutIsReportedAsUnknownBackgroundState() = runBlocking {
        val backend = Backend(environment)
        backend.delayUntil = CompletableDeferred()
        val manager = LocalRuntimeManager(this, backend, Access(), timeoutMillis = 10)
        manager.inspect().join()
        assertTrue(manager.state.value.failed)
        assertFalse(manager.state.value.checked)
        assertTrue(manager.state.value.message.contains("可能仍在执行"))
    }

    @Test fun unsafeServiceArgumentsAreRejectedBeforeExecution() {
        assertFailsWith<IllegalArgumentException> { PythonService("../escape", "test", "print(1)") }
        assertFailsWith<IllegalArgumentException> { PythonService("test-id", "test", "print(1)", listOf("--index-url")) }
        assertFailsWith<IllegalArgumentException> { ReverseProxyService("test-id", "test", 8642, "https://example.invalid") }
        assertFailsWith<IllegalArgumentException> { LocalModelSetup("test", "key", "http://example.invalid") }
        assertFalse(LocalModelSetup("test", "private-value").toString().contains("private-value"))
    }

    @Test fun malformedReceiptDoesNotExposeRawCredentials() = runBlocking {
        val secret = "hidden-" + java.util.UUID.randomUUID()
        val transport = object : TermuxTransport {
            override fun environment() = this@LocalRuntimeTest.environment
            override fun openViewer() = Unit
            var count = 0
            override suspend fun run(script: String, input: String): TermuxCommandResult = if (count++ == 0)
                TermuxCommandResult(0, "HM_PYTHON_READY") else TermuxCommandResult(0, "HMRUNTIME$secret")
        }
        val failure = assertFailsWith<IllegalStateException> { TermuxRuntimeBackend(transport, "pass").execute(RuntimeRequest(RuntimeAction.Inspect)) {} }
        assertFalse(failure.message.orEmpty().contains(secret))
    }
}

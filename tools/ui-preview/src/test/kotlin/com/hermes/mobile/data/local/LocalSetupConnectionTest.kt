package com.hermes.mobile.data.local

import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.fake.FakeHermesRepository
import com.hermes.mobile.data.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class LocalSetupConnectionTest {
    private val environment = RuntimeEnvironment(true, true, true, true, 4L * 1024 * 1024 * 1024)
    private class Repository(scope: CoroutineScope, val response: (Int) -> List<ConnectionTestStep>) :
        HermesRepository by FakeHermesRepository(scope) {
        override val connections = MutableStateFlow(listOf(ConnectionProfile(LocalRuntimeManager.ConnectionId, "fixture", ConnectionType.Local, "127.0.0.1")))
        override val activeConnectionId = MutableStateFlow<String?>("previous-fixture")
        var calls = 0
        override fun testConnection(profile: ConnectionProfile, apiKey: String?, sshSecret: String?) = flow {
            emit(response(++calls))
        }
        override fun switchConnection(id: String) { activeConnectionId.value = id }
    }

    @Test fun slowLocalStartupCanPassAfterMoreThanTenChecks() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = Repository(scope) { count -> listOf(when {
                count <= 6 -> ConnectionTestStep("API Key 鉴权", TestStepStatus.Failed, "请求超时")
                count <= 12 -> ConnectionTestStep("识别 Hermes", TestStepStatus.Failed, "服务器已收到请求，健康检查返回 HTTP 503")
                else -> ConnectionTestStep("网络可达", TestStepStatus.Passed)
            }) }
            RepositoryLocalConnection({ repo }, startupTimeoutMillis = 2000, retryDelayMillis = 1).verifyAndActivate()
            assertEquals(13, repo.calls)
            assertEquals(LocalRuntimeManager.ConnectionId, repo.activeConnectionId.value)
        } finally { scope.cancel() }
    }

    @Test fun authenticationAndCapabilityFailuresStopImmediatelyWithoutLeakingDetails() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            for ((title, issue) in listOf("API Key 鉴权" to RuntimeIssue.LocalApiUnauthorized, "能力探测" to RuntimeIssue.LocalApiUnsupported)) {
                val repo = Repository(scope) { listOf(ConnectionTestStep(title, TestStepStatus.Failed, "API Key 不对 private-fixture-response")) }
                val failure = assertFailsWith<RuntimeCommandException> {
                    RepositoryLocalConnection({ repo }, 1000, 1).verifyAndActivate()
                }
                assertEquals(issue, failure.issue)
                assertFalse(failure.message.orEmpty().contains("private-fixture"))
                assertEquals(1, repo.calls)
                assertEquals("previous-fixture", repo.activeConnectionId.value)
            }
        } finally { scope.cancel() }
    }

    @Test fun startupTimeoutReportsLocalInterfaceFailureInsteadOfPendingTermuxCommand() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = Repository(scope) { listOf(ConnectionTestStep("网络可达", TestStepStatus.Failed)) }
            val failure = assertFailsWith<RuntimeCommandException> {
                RepositoryLocalConnection({ repo }, 30, 1).verifyAndActivate()
            }
            assertEquals(RuntimeIssue.LocalApiNotReady, failure.issue)
            assertEquals("previous-fixture", repo.activeConnectionId.value)
        } finally { scope.cancel() }
    }

    @Test fun configurationIsRequiredBeforeStartupAndSavedConfigurationCanRestart() = runBlocking {
        var modelReady = false
        var verified = false
        val calls = mutableListOf<RuntimeAction>()
        val manager = LocalRuntimeManager(this, object : LocalRuntimeBackend {
            override val name = "fixture"
            override val externalApps = false
            override fun environment() = environment
            override suspend fun execute(request: RuntimeRequest, progress: (String) -> Unit): RuntimeResponse {
                calls += request.action
                return RuntimeResponse(RuntimeSnapshot(toolsReady = true, hermesInstalled = true, configured = true, modelConfigured = modelReady))
            }
        }, object : LocalConnectionAccess {
            override fun save(apiKey: String) = Unit
            override suspend fun verifyAndActivate() { verified = true }
        })
        manager.connect().join()
        assertEquals(listOf(RuntimeAction.Inspect), calls)
        assertFalse(verified)
        assertEquals(RuntimeSetupStep.Model, manager.state.value.setupStep())
        modelReady = true
        calls.clear()
        manager.connect(restart = true).join()
        assertEquals(listOf(RuntimeAction.Inspect, RuntimeAction.StopHermes, RuntimeAction.ConfigureHermes, RuntimeAction.StartHermes), calls)
        assertTrue(verified)
        assertFalse(manager.state.value.failed)
    }

    @Test fun actualBackendSeparatesPhoneInterfaceConfigurationFromModelConfiguration() = runBlocking {
        var modelResponse = """{"ok":true,"snapshot":{"provider":"","model":"","hasKey":false}}"""
        val requests = mutableListOf<Pair<String, Boolean>>()
        val backend = TermuxRuntimeBackend(object : TermuxTransport {
            override fun environment() = environment
            override fun openViewer() = Unit
            override suspend fun run(script: String, input: String, readOnly: Boolean): TermuxCommandResult {
                requests += input to readOnly
                return TermuxCommandResult(0, when {
                    input.contains("\"inspect\"") -> "HMMODELS$modelResponse"
                    script.contains("HM_PYTHON_READY") -> "HM_PYTHON_READY"
                    else -> """HMRUNTIME{"snapshot":{"toolsReady":true,"hermesInstalled":true,"configured":true}}"""
                })
            }
        }, "fixture runtime", "fixture model program")
        assertFalse(backend.execute(RuntimeRequest(RuntimeAction.Inspect)) {}.snapshot.modelConfigured)
        assertTrue(requests.all { it.second })
        modelResponse = """{"ok":true,"snapshot":{"provider":"openrouter","model":"fixture-model","hasKey":true}}"""
        assertTrue(backend.execute(RuntimeRequest(RuntimeAction.Inspect)) {}.snapshot.modelConfigured)
        modelResponse = """{"ok":false,"error":"fixture rejected","snapshot":{"provider":"openrouter","model":"fixture-model","hasKey":true}}"""
        assertFalse(backend.execute(RuntimeRequest(RuntimeAction.Inspect)) {}.snapshot.modelConfigured)
    }
}

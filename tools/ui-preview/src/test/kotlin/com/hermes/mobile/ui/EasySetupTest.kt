package com.hermes.mobile.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import com.hermes.mobile.data.HermesRepository
import com.hermes.mobile.data.fake.FakeHermesRepository
import com.hermes.mobile.data.model.*
import com.hermes.mobile.data.setup.ConnectionSetup
import com.hermes.mobile.data.ssh.SshKeys
import com.hermes.mobile.platform.DesktopSetupClipboard
import com.hermes.mobile.ui.settings.ConnectionEditorScreen
import com.hermes.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import java.util.Base64
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class EasySetupTest {
    private class Repo(scope: CoroutineScope, val passed: Boolean) : HermesRepository by FakeHermesRepository(scope) {
        var saved: Triple<ConnectionProfile, String?, String?>? = null
        var tested: ConnectionProfile? = null
        val resolvedKey = ConnectionSetup.generateApiKey()
        override suspend fun readConnectionSetup(profile: ConnectionProfile, sshSecret: String?, nonce: String) =
            ConnectionSetup.Result(resolvedKey, profile.port, "SHA256:test-host")
        override fun saveConnection(profile: ConnectionProfile, apiKey: String?, sshSecret: String?) { saved = Triple(profile, apiKey, sshSecret) }
        override fun testConnection(profile: ConnectionProfile, apiKey: String?, sshSecret: String?): Flow<List<ConnectionTestStep>> {
            tested = profile
            return flowOf(listOf(ConnectionTestStep("API Key 鉴权", if (passed) TestStepStatus.Passed else TestStepStatus.Failed)))
        }
    }

    private fun ComposeUiTest.fillAndCopy() {
        onNodeWithText("SSH 主机").performTextInput("example.invalid")
        onNodeWithText("用户名").performTextInput("test-user")
        onNodeWithText("生成并复制配置命令").performScrollTo().performClick()
    }

    private fun parameters(): ConnectionSetup.Parameters {
        val encoded = Regex("'(eyJ[A-Za-z0-9+/=]+)'").findAll(DesktopSetupClipboard.text).last().groupValues[1]
        return Json.decodeFromString(ConnectionSetup.Parameters.serializer(), Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8))
    }

    @Test fun sshCopyGeneratesOnlyTheLoginKeyAndSuccessfulCheckSavesWithoutAnotherSaveButton() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = Repo(scope, true)
            var closed = false
            setContent { HermesTheme { CompositionLocalProvider(LocalShowMessage provides {}) {
                ConnectionEditorScreen(repo, null, "Ssh") { closed = true }
            } } }
            fillAndCopy()
            assertTrue(DesktopSetupClipboard.sensitive)
            assertFalse(DesktopSetupClipboard.text.contains("PRIVATE KEY"))
            val parameters = parameters()
            assertTrue(parameters.apiKey.isBlank(), "SSH 走 gateway，配置命令不带连接密钥")
            onNodeWithText("API Key").assertDoesNotExist()
            onNodeWithText("粘贴已有 API Key").assertDoesNotExist()
            onNodeWithText("粘贴配置结果").assertDoesNotExist()
            onNodeWithText("检查并连接").performClick()
            waitUntil(timeoutMillis = 5000) { closed }
            val (profile, key, privateKey) = assertNotNull(repo.saved)
            assertEquals(repo.tested?.id, profile.id, "测试和保存必须使用同一个稳定 ID，才能记住主机指纹")
            assertNull(key, "SSH 连接不保存 API Key")
            assertEquals("SHA256:test-host", profile.sshHostKey)
            assertEquals(parameters.publicKey, SshKeys.publicKeyLine(assertNotNull(privateKey)))
        } finally { scope.cancel() }
    }

    @Test fun failedChecksKeepEditorOpenAndDoNotSave() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = Repo(scope, false)
            var closed = false
            setContent { HermesTheme { ConnectionEditorScreen(repo, null, "Ssh") { closed = true } } }
            fillAndCopy()
            onNodeWithText("检查并连接").performClick()
            waitForIdle()
            assertNull(repo.saved)
            assertFalse(closed)
        } finally { scope.cancel() }
    }

    @Test fun directPasteServerResultReusesItsExistingKeyAndPort() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = Repo(scope, true)
            var closed = false
            setContent { HermesTheme { ConnectionEditorScreen(repo, null, "Direct") { closed = true } } }
            onNodeWithText("地址").performTextInput("127.0.0.1")
            onNodeWithText("生成并复制配置命令").performScrollTo().performClick()
            assertTrue(parameters().apiKey.length >= 16, "直连仍由 App 生成 API Server 密钥")
            val existing = ConnectionSetup.generateApiKey()
            val response = parameters().copy(apiKey = existing, publicKey = "", effectivePort = 8765)
            DesktopSetupClipboard.text = ConnectionSetup.ReceiptPrefix + Base64.getUrlEncoder().encodeToString(
                Json.encodeToString(ConnectionSetup.Parameters.serializer(), response).toByteArray())
            onNodeWithText("粘贴配置结果").performScrollTo().performClick()
            onNodeWithText("检查并连接").performClick()
            waitUntil(timeoutMillis = 5000) { closed }
            assertEquals(existing, assertNotNull(repo.saved).second)
            assertEquals(8765, repo.saved!!.first.port)
        } finally { scope.cancel() }
    }

    @Test fun managedLocalConnectionUsesRuntimeCenterAndNeverOffersGenericConfigurationCommand() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val profile = ConnectionProfile(com.hermes.mobile.data.local.LocalRuntimeManager.ConnectionId, "这台手机", ConnectionType.Local, "127.0.0.1")
            val repo = object : HermesRepository by FakeHermesRepository(scope) {
                override val connections = MutableStateFlow(listOf(profile))
            }
            var opened = false
            setContent { HermesTheme { ConnectionEditorScreen(repo, profile.id, onOpenLocalRuntime = { opened = true }, onClose = {}) } }
            onNodeWithText("生成并复制配置命令").assertDoesNotExist()
            onNodeWithText("连接不上？打开配置向导").assertDoesNotExist()
            onNodeWithText("打开本机运行中心").performScrollTo().performClick()
            assertTrue(opened)
        } finally { scope.cancel() }
    }
}

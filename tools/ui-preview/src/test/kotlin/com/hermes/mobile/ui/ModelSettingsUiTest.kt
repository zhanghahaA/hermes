package com.hermes.mobile.ui

import androidx.compose.ui.test.*
import com.hermes.mobile.data.providers.*
import com.hermes.mobile.ui.settings.ModelSettingsContent
import com.hermes.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ModelSettingsUiTest {
    @Test fun customEndpointRequiresConfirmationAndSendsKeyOnlyOnSave() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val requests = CopyOnWriteArrayList<ModelSettingsRequest>()
        val snapshot = ModelSettingsSnapshot(providers = listOf(
            ModelProvider("openrouter", "OpenRouter", apiKey = true), ModelProvider("custom", "自定义端点", apiKey = true),
            ModelProvider("local", "本地模型")), provider = "openrouter", model = "fake-default")
        val manager = ModelSettingsManager(scope, "fixture") { _, body ->
            requests += Json.decodeFromString(ModelSettingsRequest.serializer(), body)
            "HMMODELS" + Json.encodeToString(ModelSettingsResponse.serializer(), ModelSettingsResponse(snapshot = snapshot))
        }
        var saved = false
        try {
            setContent { HermesTheme { ModelSettingsContent(manager, "手机测试连接", {}, onModelSaved = { saved = true }) } }
            waitUntil(timeoutMillis = 5000) { manager.state.value.snapshot != null }
            onNodeWithText("OpenRouter").performClick()
            onNodeWithText("自定义端点").performClick()
            onNodeWithText("模型服务 API 密钥").performTextInput("fixture-credential")
            onNodeWithText("模型接口地址").performScrollTo().performTextInput("https://example.invalid/v1")
            onNodeWithText("模型名称").performScrollTo().performTextInput("fixture-model")
            onNodeWithText("保存模型配置").performScrollTo().performClick()
            assertFalse(requests.any { it.action == "save" })
            onNodeWithText("保存").performClick()
            waitUntil(timeoutMillis = 5000) { saved }
            val request = requests.single { it.action == "save" }
            assertEquals("custom", request.provider)
            assertEquals("fixture-model", request.model)
            assertEquals("fixture-credential", request.apiKey)
            assertFalse(manager.state.value.toString().contains("fixture-credential"))
            onNodeWithText("fixture-credential").assertDoesNotExist()
        } finally { scope.cancel() }
    }

    @Test fun enablingInteractiveToolsIsExplicitAndDoesNotSubmitModelCredentials() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val requests = CopyOnWriteArrayList<ModelSettingsRequest>()
        val manager = ModelSettingsManager(scope, "fixture") { _, body ->
            requests += Json.decodeFromString(ModelSettingsRequest.serializer(), body)
            "HMMODELS" + Json.encodeToString(ModelSettingsResponse.serializer(), ModelSettingsResponse(snapshot = ModelSettingsSnapshot()))
        }
        try {
            setContent { HermesTheme { ModelSettingsContent(manager, "测试连接", {}) } }
            waitUntil(timeoutMillis = 5000) { manager.state.value.snapshot != null }
            assertFalse(requests.any { it.action == "enable_clarify" })
            onNodeWithText("启用手机互动选项").performScrollTo().performClick()
            waitUntil(timeoutMillis = 5000) { requests.any { it.action == "enable_clarify" } }
            val request = requests.single { it.action == "enable_clarify" }
            assertNull(request.apiKey)
            assertEquals("", request.model)
        } finally { scope.cancel() }
    }

    @Test fun directConnectionExplainsHowToEnableRemoteManagement() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = ModelSettingsManager(scope, "fixture") { _, _ -> error("没有 SSH 不应执行") }
        var edited = false
        try {
            setContent { HermesTheme { ModelSettingsContent(manager, "远程测试连接", {}, ready = false, onEditConnection = { edited = true }) } }
            onNodeWithText("配置当前连接").performClick()
            assertTrue(edited)
            assertNull(manager.state.value.snapshot)
        } finally { scope.cancel() }
    }

    @Test fun localModelSaveContinuesToChatWithOneClick() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val requests = CopyOnWriteArrayList<ModelSettingsRequest>()
        val snapshot = ModelSettingsSnapshot(providers = listOf(ModelProvider("openrouter", "OpenRouter", apiKey = true)),
            provider = "openrouter", model = "fixture-model")
        val manager = ModelSettingsManager(scope, "fixture") { _, body ->
            requests += Json.decodeFromString(ModelSettingsRequest.serializer(), body)
            "HMMODELS" + Json.encodeToString(ModelSettingsResponse.serializer(), ModelSettingsResponse(snapshot = snapshot))
        }
        var connected = false
        try {
            setContent { HermesTheme { ModelSettingsContent(manager, "这台手机", {},
                saveAndConnect = true, onModelSaved = { connected = true }) } }
            waitUntil(timeoutMillis = 5000) { manager.state.value.snapshot != null }
            onNodeWithText("模型服务 API 密钥").performTextInput("fixture-credential")
            assertFalse(requests.any { it.action == "save" })
            onNodeWithText("保存并开始聊天").performScrollTo().performClick()
            waitUntil(timeoutMillis = 5000) { connected }
            assertEquals(1, requests.count { it.action == "save" })
            onNodeWithText("保存").assertDoesNotExist()
        } finally { scope.cancel() }
    }
}

package com.hermes.mobile.data.providers

import com.hermes.mobile.data.remote.HermesAdmin
import com.hermes.mobile.data.ssh.RemoteShell
import com.hermes.mobile.data.ssh.RemoteStream
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.*

class ModelSettingsTest {
    @Test fun apiKeyTravelsInStdinAndNeverInSshCommand() = runBlocking {
        val credential = "fixture-" + java.util.UUID.randomUUID()
        var command = ""
        val input = ByteArrayOutputStream()
        val shell = object : RemoteShell {
            override fun exec(command: String, timeoutSeconds: Long) = 0 to "/fixture/hermes/config.yaml"
            override fun readFile(path: String): ByteArray? = null
            override fun writeFile(path: String, bytes: ByteArray) = Unit
            override fun openStream(value: String): RemoteStream {
                command = value
                return object : RemoteStream {
                    override val input = input
                    override val output = ByteArrayInputStream("HMMODELS{\"ok\":true}".toByteArray())
                    override val exitCode = 0
                    override fun errorText() = ""
                    override fun close() = Unit
                }
            }
        }
        val request = ModelSettingsRequest("save", "openai", "fake-model", credential)
        HermesAdmin(shell, null).modelSettings("print('fixture')", Json.encodeToString(ModelSettingsRequest.serializer(), request))
        assertFalse(command.contains(credential))
        assertTrue(input.toString().contains(credential))
        assertFalse(request.toString().contains(credential))
    }

    @Test fun unsafeEndpointsAndInvalidSecretsNeverExecute() = runBlocking {
        var calls = 0
        val manager = ModelSettingsManager(this, "fixture") { _, _ -> calls++; error("不应执行") }
        manager.submit(ModelSettingsRequest("save", "custom", "fake", "secret", "http://remote.invalid/v1")).join()
        assertEquals(0, calls)
        assertTrue(manager.state.value.failed)
        manager.submit(ModelSettingsRequest("save", "custom", "fake", "secret\nINJECTED", "https://example.invalid/v1")).join()
        assertEquals(0, calls)
        ModelSettingsRequest("save", "local", "fake", null, "http://127.0.0.1:11434/v1").validate()
    }

    @Test fun missingModuleNameIsShownButNothingElse() = runBlocking {
        val known = "当前 Hermes 缺少模型配置接口或其依赖，请检查 Hermes 安装与版本；这不是浏览器授权错误。"
        for ((error, shown) in listOf(known + "（缺少：hermes_cli）" to true, known + "（缺少：/home/u/x）" to false,
                                       known + "（缺少：hermes_cli）sk-123" to false, "（缺少：hermes_cli）" to false)) {
            val manager = ModelSettingsManager(this, "fixture") { _, _ ->
                "HMMODELS" + Json.encodeToString(ModelSettingsResponse.serializer(), ModelSettingsResponse(ok = false, error = error))
            }
            manager.submit(ModelSettingsRequest("inspect")).join()
            assertTrue(manager.state.value.failed)
            assertEquals(shown, manager.state.value.message == error, error)
        }
    }

    @Test fun malformedAndFailedResponsesNeverLeakPrivateContent() = runBlocking {
        val secret = "private-" + java.util.UUID.randomUUID()
        val manager = ModelSettingsManager(this, "fixture") { _, _ -> "HMMODELS$secret" }
        manager.submit(ModelSettingsRequest("inspect")).join()
        assertTrue(manager.state.value.failed)
        assertFalse(manager.state.value.toString().contains(secret))
        val rejected = ModelSettingsManager(this, "fixture") { _, _ -> "HMMODELS{\"ok\":false,\"error\":\"$secret\"}" }
        rejected.submit(ModelSettingsRequest("inspect")).join()
        assertFalse(rejected.state.value.toString().contains(secret))
    }

    @Test fun mismatchedLoginSessionCannotReplaceCurrentAuthorization() = runBlocking {
        val manager = ModelSettingsManager(this, "fixture") { _, _ ->
            "HMMODELS{\"login\":{\"id\":\"${"b".repeat(32)}\",\"provider\":\"openai-codex\",\"status\":\"approved\"}}"
        }
        manager.submit(ModelSettingsRequest("poll", sessionId = "a".repeat(32))).join()
        assertTrue(manager.state.value.failed)
        assertNull(manager.state.value.login)
    }

    @Test fun connectionManagersKeepModelsAndLoginIsolated() = runBlocking {
        fun manager(model: String) = ModelSettingsManager(this, "fixture") { _, _ ->
            "HMMODELS{\"snapshot\":{\"provider\":\"openai\",\"model\":\"$model\"}}"
        }
        val phone = manager("phone-model")
        val cloud = manager("cloud-model")
        phone.submit(ModelSettingsRequest("inspect")).join()
        cloud.submit(ModelSettingsRequest("inspect")).join()
        assertEquals("phone-model", phone.state.value.snapshot?.model)
        assertEquals("cloud-model", cloud.state.value.snapshot?.model)
    }

    @Test fun lanHttpEndpointsAreAllowedButPublicHttpIsNot() {
        for (url in listOf("http://192.168.1.10:11434/v1", "http://10.0.0.2/v1", "http://172.20.1.1:8000/v1", "http://100.100.1.1/v1",
                           "http://desktop.local:1234/v1", "http://[fd00::1]:8000/v1", "http://127.0.0.1:11434/v1", "https://api.example.invalid/v1"))
            assertTrue(ModelSettingsRequest.endpointAllowed(url), url)
        for (url in listOf("http://8.8.8.8/v1", "http://172.32.0.1/v1", "http://192.169.0.1/v1", "http://example.invalid/v1",
                           "https://user:pw@example.invalid/v1", "https://example.invalid/v1?key=x", "ftp://192.168.1.10/v1"))
            assertFalse(ModelSettingsRequest.endpointAllowed(url), url)
    }

    @Test fun endpointModelListSendsTheTypedAddressAndKeyThroughStdinOnly() = runBlocking {
        var body = ""
        val manager = ModelSettingsManager(this, "fixture") { _, input -> body = input
            "HMMODELS{\"snapshot\":{\"providers\":[{\"id\":\"custom\",\"name\":\"c\",\"models\":[\"m-1\",\"m-2\"]}]}," +
                "\"probe\":{\"status\":\"ok\",\"models\":[\"m-1\",\"m-2\"],\"baseUrl\":\"http://192.168.1.10:8000/v1\"}}"
        }
        manager.submit(ModelSettingsRequest("models", "custom", apiKey = "fixture-key", baseUrl = "http://192.168.1.10:8000")).join()
        val sent = Json.decodeFromString(ModelSettingsRequest.serializer(), body)
        assertEquals("http://192.168.1.10:8000", sent.baseUrl)
        assertEquals("fixture-key", sent.apiKey)
        assertFalse(manager.state.value.failed)
        assertEquals("http://192.168.1.10:8000/v1", manager.state.value.probe?.baseUrl)
        assertTrue(manager.state.value.message.startsWith("读到 2 个模型"), manager.state.value.message)
        assertFalse(manager.state.value.toString().contains("fixture-key"))
    }

    @Test fun endpointProbeFailuresExplainWhatToCheck() = runBlocking {
        for ((status, expected) in listOf("unreachable" to "连不上这个地址", "rejected" to "端点拒绝了这个 API 密钥", "http" to "端点返回 HTTP 404",
                                         "empty" to "没有返回模型清单", "transport" to "不支持所选的接口格式")) {
            val models = if (status == "transport") "[\"m\"]" else "[]"
            val manager = ModelSettingsManager(this, "fixture") { _, _ ->
                "HMMODELS{\"snapshot\":{},\"probe\":{\"status\":\"$status\",\"models\":$models,\"code\":404}}"
            }
            manager.submit(ModelSettingsRequest("models", "local", apiKey = "k", baseUrl = "http://127.0.0.1:11434/v1")).join()
            assertTrue(manager.state.value.message.contains(expected), status + ": " + manager.state.value.message)
            assertEquals(status != "transport", manager.state.value.failed, status)
        }
        // 回包里不合规则的地址不采用，退回用户填的。
        val manager = ModelSettingsManager(this, "fixture") { _, _ ->
            "HMMODELS{\"snapshot\":{},\"probe\":{\"status\":\"ok\",\"models\":[\"m\"],\"baseUrl\":\"http://8.8.8.8/v1\"}}"
        }
        manager.submit(ModelSettingsRequest("models", "custom", baseUrl = "https://x.example.invalid/v1")).join()
        assertEquals("https://x.example.invalid/v1", manager.state.value.probe?.baseUrl)
    }

    @Test fun publicHttpEndpointIsRejectedBeforeProbing() = runBlocking {
        var calls = 0
        val manager = ModelSettingsManager(this, "fixture") { _, _ -> calls++; "" }
        manager.submit(ModelSettingsRequest("models", "custom", baseUrl = "http://8.8.8.8/v1")).join()
        assertEquals(0, calls)
        assertEquals(ModelSettingsRequest.EndpointRule, manager.state.value.message)
    }
}

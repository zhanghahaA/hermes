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
}

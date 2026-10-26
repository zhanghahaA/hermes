package com.hermes.mobile.data.setup

import com.hermes.mobile.data.model.ConnectionProfile
import com.hermes.mobile.data.model.ConnectionType
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.util.Base64
import kotlin.test.*

class ConnectionSetupTest {
    private val profile = ConnectionProfile("test", "test", ConnectionType.Direct, "127.0.0.1")
    @Test fun generatedKeysHaveEnoughEntropyAndDoNotRepeat() {
        val keys = List(30) { ConnectionSetup.generateApiKey() }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { Regex("[a-f0-9]{64}").matches(it) })
    }

    @Test fun resultLookupDoesNotDiscloseThePairingSecretToAnUnverifiedHost() {
        val nonce = java.util.UUID.randomUUID().toString()
        val command = ConnectionSetup.resultReadCommand(profile.copy(type = ConnectionType.Ssh, sshUser = "test-user"), nonce)
        assertFalse(command.contains(nonce))
        assertTrue(command.contains(".mobile-setup-results/"))
    }

    @Test fun receiptIsBoundToCurrentFormAndErrorsNeverEchoCredentials() {
        val key = ConnectionSetup.generateApiKey()
        val parameters = ConnectionSetup.Parameters("test-nonce", "Direct", "127.0.0.1", 8642, "", "", 22, key, "", 8765)
        val result = ConnectionSetup.ReceiptPrefix + Base64.getUrlEncoder().encodeToString(Json.encodeToString(ConnectionSetup.Parameters.serializer(), parameters).toByteArray())
        val parsed = ConnectionSetup.readResult(result, profile, "test-nonce")
        assertEquals(key, parsed.apiKey)
        assertEquals(8765, parsed.port)
        assertFalse(parsed.toString().contains(key))
        for (text in listOf(result, "bad-$key")) {
            val error = assertFailsWith<IllegalStateException> { ConnectionSetup.readResult(text, profile.copy(host = "example.invalid"), "other-form") }
            assertFalse(error.message.orEmpty().contains(key))
        }
    }

    @Test fun copiedCommandActuallyConfiguresAndRestartsFakeHermesInTemporaryHome() {
        val directory = Files.createTempDirectory("hermes-copy-setup").toFile()
        try {
            val bin = java.io.File(directory, "bin").apply { mkdirs() }
            java.io.File(bin, "hermes").apply {
                writeText("#!/bin/sh\nprintf '%s\\n' \"\$*\" > \"\$HERMES_HOME/restarted-args\"\n")
                setExecutable(true)
            }
            val program = requireNotNull(javaClass.getResourceAsStream("/setup/configure.py")).bufferedReader().use { it.readText() }
            val key = ConnectionSetup.generateApiKey()
            val command = ConnectionSetup.command(program, profile, key, "", "test-nonce")
            val output = java.io.File(directory, "command-output")
            val builder = ProcessBuilder("bash", "-c", command).redirectErrorStream(true).redirectOutput(output)
            builder.environment()["HERMES_HOME"] = java.io.File(directory, "hermes-data").absolutePath
            builder.environment()["PATH"] = bin.absolutePath + ":" + builder.environment()["PATH"]
            val process = builder.start()
            assertTrue(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0, process.exitValue(), "复制的完整命令应可在假环境执行")
            val parsed = ConnectionSetup.readResult(output.readText(), profile, "test-nonce")
            assertEquals(key, parsed.apiKey)
            val readBack = java.io.File(directory, "read-back")
            val reader = ProcessBuilder("bash", "-c", ConnectionSetup.resultReadCommand(profile, "test-nonce"))
                .redirectErrorStream(true).redirectOutput(readBack)
            reader.environment()["HERMES_HOME"] = java.io.File(directory, "hermes-data").absolutePath
            val reading = reader.start()
            assertTrue(reading.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0, reading.exitValue(), "读取命令应能找到 Python 写入的私密配置结果")
            assertEquals(key, ConnectionSetup.readResult(readBack.readText(), profile, "test-nonce").apiKey)
            assertTrue(java.io.File(directory, "hermes-data/.env").readText().contains("API_SERVER_ENABLED=true"))
            val restarted = java.io.File(directory, "hermes-data/restarted-args")
            repeat(50) { if (!restarted.exists()) Thread.sleep(20) }
            assertEquals("gateway restart", restarted.readText().trim())
        } finally { directory.deleteRecursively() }
    }
}

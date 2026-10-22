package com.hermes.mobile.data.remote

import com.hermes.mobile.data.store.CrashLog
import com.hermes.mobile.data.store.DiagnosticRedactor
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticRedactorTest {
    @Test fun removesAddressesAndKeepsSourceLocations() {
        val text = "连接 private-demo.example.invalid / 192.0.2.5 / [2001:db8::5] 失败\n" +
            "\tat com.hermes.mobile.Worker.run(Worker.kt:42)"
        val safe = DiagnosticRedactor.redact(text)
        listOf("private-demo.example.invalid", "192.0.2.5", "2001:db8::5").forEach { assertFalse(it in safe) }
        assertTrue("com.hermes.mobile.Worker.run(Worker.kt:42)" in safe)
    }

    @Test fun removesAuthenticationAndQuotedSecrets() {
        val token = "fixture-" + "t".repeat(24)
        val password = "fixture value with spaces"
        val safe = DiagnosticRedactor.redact("Authorization: Bearer $token\npassword=\"$password\"\nhttps://user:pass@private-demo.example.invalid/path")
        listOf(token, password, "user:pass", "private-demo.example.invalid").forEach { assertFalse(it in safe) }
    }

    @Test fun removesKnownSingleLabelHostsAndUnlabelledSecrets() {
        val token = "fixture-" + "s".repeat(40)
        val safe = DiagnosticRedactor.redact("连接 WORKSTATION 失败，异常值 $token", listOf("workstation", token))
        assertFalse("WORKSTATION" in safe)
        assertFalse(token in safe)
    }

    @Test fun removesPrivateKeyBlocks() {
        val marker = "fixture-not-a-private-key"
        val safe = DiagnosticRedactor.redact("before\n-----BEGIN PRIVATE KEY-----\n$marker\n-----END PRIVATE KEY-----\nafter")
        assertFalse(marker in safe)
        assertTrue("before" in safe && "after" in safe)
    }

    @Test fun keepsTimesAndVersionNumbers() {
        val safe = DiagnosticRedactor.redact("2026-10-20T12:34:56Z 12:34:56 版本 0.11.1")
        assertTrue("12:34:56" in safe)
        assertTrue("0.11.1" in safe)
    }

    @Test fun redactsNewCrashesAndPreviouslyStoredCrashes() {
        val previous = CrashLog.file
        val file = Files.createTempFile("hermes-crash-privacy", ".txt").toFile()
        try {
            CrashLog.file = file
            file.writeText("旧记录：连接 private-demo.example.invalid 失败")
            assertFalse("private-demo.example.invalid" in CrashLog.recent().orEmpty())
            CrashLog.record(IllegalStateException("连接 private-demo.example.invalid 失败"), fatal = true)
            assertFalse("private-demo.example.invalid" in file.readText())
            assertFalse("private-demo.example.invalid" in CrashLog.takeUnseenCrash().orEmpty())
            assertTrue("IllegalStateException" in file.readText())
        } finally {
            CrashLog.file = previous
            file.delete()
        }
    }

    @Test fun redactsKnownValuesBeforeCrashTextIsTruncated() {
        val previous = CrashLog.file
        val file = Files.createTempFile("hermes-crash-known-values", ".txt").toFile()
        val host = "fixture-workstation"
        val token = "fixture-" + "t".repeat(14000)
        try {
            CrashLog.file = file
            CrashLog.record(IllegalStateException("$host $token"), fatal = true,
                redact = { DiagnosticRedactor.redact(it, listOf(host, token)) })
            val recorded = file.readText()
            assertFalse(host in recorded)
            assertFalse(token.take(100) in recorded)
            assertTrue("IllegalStateException" in recorded)
        } finally {
            CrashLog.file = previous
            file.delete()
        }
    }
}

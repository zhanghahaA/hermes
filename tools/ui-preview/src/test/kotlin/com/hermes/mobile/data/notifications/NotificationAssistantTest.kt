package com.hermes.mobile.data.notifications

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

class NotificationAssistantTest {
    private class Store : NotificationStore {
        var encoded: String? = null
        override fun load() = encoded?.let { Json.decodeFromString(NotificationState.serializer(), it) }
        override fun save(state: NotificationState) { encoded = Json.encodeToString(NotificationState.serializer(), state) }
    }
    private val target = NotificationTarget("connection-a", "helper", "fixed-session", "测试")
    private val config = NotificationConfig(true, setOf("example.delivery"), target = target)
    private fun incoming(key: String = "one", text: String = "包裹已送达") =
        IncomingNotification(key, "example.delivery", "快递", "取件提醒", text)

    @Test fun defaultOffAndAllowlistAreRequired() {
        assertNull(NotificationFilter.apply(incoming(), NotificationConfig(), "self", 1))
        assertNull(NotificationFilter.apply(incoming(), config.copy(packages = emptySet()), "self", 1))
        assertNull(NotificationFilter.apply(incoming(), config, "example.delivery", 1))
        assertNotNull(NotificationFilter.apply(incoming(), config, "self", 1))
    }

    @Test fun sensitiveContentAndNoiseNeverEnterStore() {
        val values = listOf(incoming(text = "验证码 123456"), incoming(text = "Your verification code is 123456"),
            incoming().copy(appName = "银行"), incoming().copy(ongoing = true),
            incoming().copy(groupSummary = true), incoming().copy(category = "transport"))
        values.forEach { assertNull(NotificationFilter.apply(it, config, "self", 1)) }
        assertNull(NotificationFilter.apply(incoming(text = "推广活动"), config.copy(blockedWords = listOf("推广")), "self", 1))
    }

    @Test fun redactionPrecedesPersistence() {
        val row = assertNotNull(NotificationFilter.apply(incoming(text = "编号 987654 api_key=test-secret-long"), config, "self", 1))
        assertFalse(row.text.contains("987654"))
        assertFalse(row.text.contains("test-secret-long"))
        assertFalse(row.key.contains("one"))
    }

    @Test fun duplicatesMergeAndUpdatedNotificationIsReplaced() {
        val assistant = NotificationAssistant(Store(), sender = { _, _ -> "" })
        assistant.configure(config)
        assistant.capture(incoming())
        assistant.capture(incoming())
        assertEquals(1, assistant.state.value.records.size)
        assistant.capture(incoming(text = "配送信息更新"))
        assertEquals(1, assistant.state.value.records.size)
        assertEquals("配送信息更新", assistant.state.value.records.single().text)
    }

    @Test fun retryAfterRestartUsesSameFrozenBatchAndRun() = runBlocking {
        val store = Store()
        var captured: NotificationBatch? = null
        val first = NotificationAssistant(store, sender = { batch, checkpoint ->
            captured = batch
            checkpoint("run-accepted")
            error("网络断开，不能丢队列")
        })
        first.configure(config)
        first.capture(incoming())
        assertFalse(first.summarize(true, true))
        val frozen = assertNotNull(captured)
        val restored = NotificationAssistant(store, sender = { batch, _ ->
            assertEquals(frozen.id, batch.id)
            assertEquals(target, batch.target)
            assertEquals("run-accepted", batch.runId)
            "需要处理：取件"
        })
        restored.capture(incoming(text = "更新后的配送信息"))
        assertTrue(restored.summarize(true, true))
        assertNull(restored.state.value.batch)
        assertEquals(1, restored.state.value.records.count { it.sent })
        assertEquals(1, restored.state.value.records.count { !it.sent })
        assertEquals("需要处理：取件", restored.state.value.lastSummary)
    }

    @Test fun ambiguousSubmissionKeepsIdempotencyKey() = runBlocking {
        val ids = mutableListOf<String>()
        val assistant = NotificationAssistant(Store(), sender = { batch, _ ->
            ids += batch.id
            if (ids.size == 1) error("已接收但响应丢失")
            "摘要"
        })
        assistant.configure(config); assistant.capture(incoming())
        assertFalse(assistant.summarize(true, true))
        assertTrue(assistant.summarize(true, true))
        assertEquals(ids[0], ids[1])
    }

    @Test fun missingPermissionOrDisabledStateDoesNotSend() = runBlocking {
        var sent = 0
        val assistant = NotificationAssistant(Store(), sender = { _, _ -> sent++; "" })
        assistant.configure(config); assistant.capture(incoming())
        assertFalse(assistant.summarize(false, true))
        assistant.configure(config.copy(enabled = false))
        assertFalse(assistant.summarize(true, true))
        assistant.capture(incoming("two"))
        assertEquals(1, assistant.state.value.records.size)
        assertEquals(0, sent)
    }

    @Test fun pendingBatchCannotBeMovedToAnotherBotOrSilentlyChangeAllowlist() = runBlocking {
        val assistant = NotificationAssistant(Store(), sender = { _, _ -> error("离线") })
        assistant.configure(config); assistant.capture(incoming()); assistant.summarize(true, true)
        assertFailsWith<IllegalArgumentException> { assistant.configure(config.copy(target = target.copy(bot = "another"))) }
        assertFailsWith<IllegalArgumentException> { assistant.configure(config.copy(packages = emptySet())) }
        assistant.clear()
        assistant.configure(config.copy(target = target.copy(bot = "another")))
        assertEquals("another", assistant.state.value.config.target?.bot)
    }

    @Test fun concurrentRequestsDoNotSendTwiceAndClearCannotRaceInFlight() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val assistant = NotificationAssistant(Store(), sender = { _, _ ->
            calls++; started.complete(Unit); release.await(); "摘要"
        })
        assistant.configure(config); assistant.capture(incoming())
        val first = async { assistant.summarize(true, true) }
        started.await()
        val second = async { assistant.summarize(true, true) }
        assertFailsWith<IllegalStateException> { assistant.clear() }
        release.complete(Unit)
        first.await(); second.await()
        assertEquals(1, calls)
    }

    @Test fun retentionCapacityAndBatchSizeAreBounded() = runBlocking {
        var now = 1L
        val assistant = NotificationAssistant(Store(), clock = { now }, sender = { batch, _ ->
            assertEquals(30, batch.records.size); "摘要"
        })
        assistant.configure(config); assistant.capture(incoming("old"))
        now += NotificationAssistant.RetentionMillis + 1
        repeat(510) { assistant.capture(incoming("new-" + it)) }
        assertEquals(500, assistant.state.value.records.size)
        assertTrue(assistant.state.value.records.none { it.time == 1L })
        assistant.summarize(true, true)
        assertEquals(470, assistant.state.value.records.count { !it.sent })
    }

    @Test fun failedDiskWritePreventsNetworkSubmission() = runBlocking {
        var calls = 0
        var fail = false
        val store = object : NotificationStore {
            override fun load(): NotificationState? = null
            override fun save(state: NotificationState) { if (fail) error("磁盘不可写") }
        }
        val assistant = NotificationAssistant(store, sender = { _, _ -> calls++; "" })
        assistant.configure(config); assistant.capture(incoming()); fail = true
        assertFails { assistant.summarize(true, true) }
        assertEquals(0, calls)
    }
}

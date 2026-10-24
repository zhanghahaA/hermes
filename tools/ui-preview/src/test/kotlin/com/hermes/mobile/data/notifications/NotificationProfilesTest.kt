package com.hermes.mobile.data.notifications

import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class NotificationProfilesTest {
    private class Store(var value: NotificationState? = null) : NotificationStore {
        override fun load() = value
        override fun save(state: NotificationState) { value = Json.decodeFromString(NotificationState.serializer(), Json.encodeToString(NotificationState.serializer(), state)) }
    }
    private fun config(id: String) = NotificationConfig(true, setOf("example.delivery"), target = NotificationTarget(id, "default", "session-$id", id))
    private fun incoming() = IncomingNotification("one", "example.delivery", "快递", "提醒", "包裹已送达")

    @Test fun newConnectionStartsDisabledAndRestoresIndependentQueue() {
        val store = Store()
        val assistant = NotificationAssistant(store, sender = { _, _ -> "" })
        assistant.selectConnection("a"); assistant.configure(config("a")); assistant.capture(incoming())
        assistant.selectConnection("b")
        assertFalse(assistant.state.value.config.enabled)
        assertTrue(assistant.state.value.records.isEmpty())
        assistant.configure(config("b").copy(packages = emptySet()))
        assistant.selectConnection("a")
        assertEquals(config("a"), assistant.state.value.config)
        assertEquals(1, assistant.state.value.records.size)
        val restored = NotificationAssistant(store, sender = { _, _ -> "" })
        restored.selectConnection("b")
        assertTrue(restored.state.value.config.packages.isEmpty())
        assertTrue(restored.state.value.records.isEmpty())
    }

    @Test fun legacyFixedTargetMigratesOnlyToItsOwner() {
        val assistant = NotificationAssistant(Store(NotificationState(config = config("a"))), sender = { _, _ -> "" })
        assistant.selectConnection("b")
        assertFalse(assistant.state.value.config.enabled)
        assertNull(assistant.state.value.config.target)
        assistant.selectConnection("a")
        assertEquals(config("a"), assistant.state.value.config)
        assertFailsWith<IllegalArgumentException> { assistant.configure(config("b")) }
    }

    @Test fun queuedCaptureFromOldConnectionIsRejected() {
        val assistant = NotificationAssistant(Store(), sender = { _, _ -> "" })
        assistant.selectConnection("a"); assistant.configure(config("a"))
        val token = assistant.captureToken
        assistant.selectConnection("b"); assistant.configure(config("b"))
        assistant.capture(incoming(), token)
        assertTrue(assistant.state.value.records.isEmpty())
        assistant.capture(incoming(), assistant.captureToken)
        assertEquals(1, assistant.state.value.records.size)
    }

    @Test fun switchingCancelsSendAndLateCompletionCannotOverwriteNewProfile() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val assistant = NotificationAssistant(Store(), sender = { _, _ -> started.complete(Unit); awaitCancellation() })
        assistant.selectConnection("a"); assistant.configure(config("a")); assistant.capture(incoming())
        val sending = launch { assistant.summarize(true, true) }
        started.await()
        assistant.selectConnection("b")
        sending.join()
        assertFalse(assistant.state.value.config.enabled)
        assertEquals("尚未开启", assistant.state.value.status)
        assertNull(assistant.state.value.batch)
        assistant.selectConnection("a")
        assertNotNull(assistant.state.value.batch)
        assertEquals("a", assistant.state.value.batch!!.target.connectionId)
    }

    @Test fun deletingConnectionRemovesItsArchive() {
        val assistant = NotificationAssistant(Store(), sender = { _, _ -> "" })
        assistant.selectConnection("a"); assistant.configure(config("a")); assistant.capture(incoming())
        assistant.forgetConnection("a")
        assertFalse("a" in assistant.state.value.profiles)
        assistant.selectConnection("a")
        assertFalse(assistant.state.value.config.enabled)
        assertTrue(assistant.state.value.records.isEmpty())
    }

    @Test fun clearingRestoredProfileLeavesNoArchivedCopyOnDisk() {
        val store = Store()
        val assistant = NotificationAssistant(store, sender = { _, _ -> "" })
        assistant.selectConnection("a"); assistant.configure(config("a")); assistant.capture(incoming())
        assistant.selectConnection("b"); assistant.selectConnection("a")
        assistant.clear()
        assertTrue(store.value!!.records.isEmpty())
        assertFalse("a" in store.value!!.profiles)
        val restored = NotificationAssistant(store, sender = { _, _ -> "" })
        restored.selectConnection("b"); restored.selectConnection("a")
        assertTrue(restored.state.value.records.isEmpty())
    }
}

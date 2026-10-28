package com.hermes.mobile.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.test.*

class SessionControlFeedTest {
    private fun obj(value: String) = HermesJson.parseToJsonElement(value).jsonObject

    @Test fun desktopPromptIsRestoredAndRemovedWhenAnsweredElsewhere() = runBlocking {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val feed = SessionControlFeed { method, params ->
            calls += method to params
            when (method) {
                "session.resume" -> obj("""{"session_id":"s1","subscription_id":"sub1","last_sequence":7,"replay_epoch":"e1","execution_generation":3,"prompts":[{"kind":"clarify","prompt_id":"p1","question":"选一个","choices":["甲","乙"]}]}""")
                "session.events.since" -> obj("""{"last_sequence":8,"replay_epoch":"e1","events":[{"type":"clarify.settled","payload":{"prompt_id":"p1"}}]}""")
                else -> obj("{}")
            }
        }
        val snapshots = withTimeout(5000) { feed.watch("s1", "work").take(2).toList() }
        assertEquals(listOf("甲", "乙"), snapshots.first().prompts.single().choices)
        assertEquals(JsonPrimitive(3), snapshots.first().prompts.single().generation)
        assertTrue(snapshots.last().prompts.isEmpty())
        assertTrue(calls.all { it.second["profile"] == JsonPrimitive("work") })
        assertEquals("session.detach", calls.last().first)
        assertEquals(JsonPrimitive(7), calls.first { it.first == "session.events.since" }.second["last_sequence"])
    }

    @Test fun answerUsesExactPromptGenerationAndProfile() = runBlocking {
        var method = ""
        var params = obj("{}")
        val feed = SessionControlFeed { m, p -> method = m; params = p; obj("{}") }
        val prompt = assertNotNull(ControlRequest.from(obj("""{"kind":"clarify","prompt_id":"p2","execution_generation":9,"question":"继续？"}""")))
        feed.respond("s2", "work", prompt, "继续")
        assertEquals("clarify.respond", method)
        assertEquals(obj("""{"session_id":"s2","profile":"work","prompt_id":"p2","execution_generation":9,"answer":"继续"}"""), params)
    }

    @Test fun unsupportedSnapshotIsNotTreatedAsAnEmptySupportedFeed() = runBlocking {
        val feed = SessionControlFeed { _, _ -> obj("{}") }
        assertFailsWith<HermesError.NotSupported> { feed.watch("s1", "default").toList() }
        Unit
    }
}

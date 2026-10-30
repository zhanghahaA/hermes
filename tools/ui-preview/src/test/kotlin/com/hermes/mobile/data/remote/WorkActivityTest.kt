package com.hermes.mobile.data.remote

import com.hermes.mobile.data.model.ChatItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkActivityTest {
    private fun event(name: String, fields: String) =
        parseRunEvent(SseEvent(id = null, event = null, data = """{"event":"$name","run_id":"r",$fields}"""))

    /** 开始事件的 preview 是参数摘要，完成事件的 preview 是结果摘要，耗时按秒给。 */
    @Test fun toolStartAndCompletionAreKeptApartWithDuration() {
        var activity = ChatItem.Activity("a", 0)
        activity = activity.tool(event("tool.started", "\"tool\":\"terminal\",\"tool_call_id\":\"c1\",\"preview\":\"ls -la\"") as RunEvent.Step)
        assertTrue(!activity.steps.single().finished)
        activity = activity.tool(event("tool.completed", "\"tool\":\"terminal\",\"tool_call_id\":\"c1\",\"duration\":1.234,\"error\":false,\"preview\":\"total 42\"") as RunEvent.Step)
        val step = activity.steps.single()
        assertEquals("ls -la", step.text)
        assertEquals("total 42", step.output)
        assertEquals(1234L, step.durationMs)
        assertTrue(step.finished && !step.failed)
    }
}

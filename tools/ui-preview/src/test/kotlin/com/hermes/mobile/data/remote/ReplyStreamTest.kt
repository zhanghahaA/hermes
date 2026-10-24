package com.hermes.mobile.data.remote

import kotlin.test.*

class ReplyStreamTest {
    @Test fun hundredSentencesStayInOrderAcrossToolsAndDuplicateInterims() {
        var stream = ReplyStream()
        val sentences = (1..100).map { "第${it}句。" }
        sentences.forEachIndexed { index, text ->
            text.forEach { stream = stream.delta(it.toString()) }
            stream = stream.interim(text, alreadyStreamed = true).step().step()
            assertEquals(sentences.take(index + 1).joinToString("\n\n"), stream.text)
        }
        val (before, final) = stream.finish(sentences.last())
        assertEquals(sentences.dropLast(1).joinToString("\n\n"), before)
        assertEquals(sentences.last(), final)
    }

    @Test fun finalAnswerReplacesPartialLastSegmentWithoutRepeatingIt() {
        val stream = ReplyStream().delta("我先查一下。").step().delta("答案是")
        assertEquals("我先查一下。" to "答案是 42。", stream.finish("答案是 42。"))
        assertEquals("我先查一下。" to "答案是", stream.finish(""))
    }

    @Test fun nonStreamedInterimIsVisibleOnceAndEmptyEventsDoNotEraseText() {
        val stream = ReplyStream().interim("第一句", false).interim("第一句", true)
            .step().delta("").delta("第二句").interim("第二句", false)
        assertEquals("第一句\n\n第二句", stream.text)
    }

    @Test fun parserUsesHermesPayloadsAndThinkingIsNotATextBoundary() {
        fun parse(body: String) = parseRunEvent(SseEvent(id = 7, event = null, data = body))
        assertEquals(RunEvent.Interim(7, "你好", true), parse("""{"event":"message.interim","text":"你好","already_streamed":true}"""))
        assertEquals(RunEvent.Reasoning(7, "研究一下", false), parse("""{"event":"reasoning.available","text":"研究一下"}"""))
        assertEquals(RunEvent.Reasoning(7, "继续思考", true), parse("""{"event":"tool.progress","tool_name":"_thinking","delta":"继续思考"}"""))
        val tool = parse("""{"event":"tool.completed","tool":"terminal","tool_call_id":"a","preview":"已完成","error":true}""") as RunEvent.Step
        assertTrue(tool.failed && tool.finished && tool.boundary)
        assertEquals("a", tool.callId)
    }

    @Test fun oldSettingsDefaultToNoProcessCollection() {
        val settings = HermesJson.decodeFromString<com.hermes.mobile.data.model.AppSettings>("""{"streamReplies":true}""")
        assertFalse(settings.showWorkProcess)
    }
}

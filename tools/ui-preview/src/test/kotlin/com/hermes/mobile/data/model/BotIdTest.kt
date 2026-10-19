package com.hermes.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals

class BotIdTest {
    @Test
    fun asciiNamesSlugLikeTheDesktop() {
        assertEquals("my-bot", botIdFor("My Bot!"))
        assertEquals("coder_2", botIdFor("  coder_2 "))
        assertEquals("resume", botIdFor("Résumé"))
    }

    @Test
    fun otherScriptsBecomeCodePoints() {
        assertEquals("u5c0f-u52a9-u624b", botIdFor("小助手"))
        assertEquals("ai-u52a9-u624b", botIdFor("AI助手"))
        assertEquals("", botIdFor("!!!"))
    }

    @Test
    fun longNamesAreCutAtATokenBoundary() {
        val id = botIdFor("小".repeat(20))
        assertEquals(true, id.length <= 64)
        assertEquals(true, id.split('-').all { it == "u5c0f" })
    }

    @Test
    fun labelFollowsTheDesktopOrder() {
        fun bot(name: String, display: String = "", title: String = "", default: Boolean = false) =
            Bot(name, default, display, title, "", null, true, null, null, null, null)
        assertEquals("Hermes", bot("default", default = true).label)
        assertEquals("Coder Two", bot("coder-two").label)
        assertEquals("Shown", bot("x", display = "Shown").label)
        assertEquals("Title", bot("x", display = "Shown", title = "Title").label)
    }
}

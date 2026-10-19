package com.hermes.mobile.ui.components

import org.commonmark.node.Node
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownTest {
    private fun Node.all(): Sequence<Node> = sequence {
        yield(this@all)
        var c = firstChild
        while (c != null) {
            yieldAll(c.all())
            c = c.next
        }
    }

    private fun bold(md: String) = parseMarkdown(md).all().filterIsInstance<StrongEmphasis>()
        .map { s -> s.all().filterIsInstance<Text>().joinToString("") { it.literal } }.toList()

    private fun literal(md: String) = parseMarkdown(md).all().filterIsInstance<Text>().joinToString("") { it.literal }

    @Test
    fun chineseBoldNextToPunctuationIsBold() {
        assertEquals(listOf("注意：", "重点"), bold("**注意：**这里有**重点**。"))
        assertEquals(listOf("“引号”"), bold("前面**“引号”**后面"))
        assertEquals(listOf("第一步（必做）"), bold("- **第一步（必做）**先备份"))
        assertFalse('*' in literal("**注意：**这里有**重点**。"), "星号不该露出来")
    }

    @Test
    fun ordinaryMarkdownIsUntouched() {
        assertEquals(listOf("bold"), bold("some **bold** text"))
        assertEquals("a * b * c", literal("a * b * c"))
        assertEquals("2*3=6", literal("2*3=6"))
        // 代码里的星号不动
        assertEquals("**注意：**x", cjkFriendly("`**注意：**x`").trim('`'))
        val fenced = "```\n**注意：**这里\n```"
        assertEquals(fenced, cjkFriendly(fenced))
    }

    @Test
    fun tablesListsAndStrikethroughParse() {
        val doc = parseMarkdown("| 名称 | 状态 |\n|---|---|\n| **NAS** | ~~离线~~ 在线 |\n\n1. 一\n   - 子项\n2. 二\n\n- [x] 完成\n- [ ] 未完成")
        val names = doc.all().map { it::class.simpleName }.toSet()
        assertTrue("TableBlock" in names && "Strikethrough" in names && "OrderedList" in names && "BulletList" in names && "TaskListItemMarker" in names, names.toString())
    }
}

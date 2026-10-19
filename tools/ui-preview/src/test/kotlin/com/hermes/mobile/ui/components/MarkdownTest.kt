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

    /** 截图里的回复：全角竖线的“表格”、没有分隔行，温度用 ~ 写范围。 */
    private val weather = """
        这是结合官方预报和两个模型对过之后的结果：
        日期 ｜ 天气 ｜ 气温 ｜ 风 ｜ 空气
        10/19 周一 ｜ 阴转多云 ｜ 13~22°C ｜ 南风2级 ｜ AQI 54 良
        10/20 周二 ｜ 小雨 ｜ 13~21°C ｜ 东南风2级 ｜ AQI 58 良
        10/21 周三 ｜ 晴 ｜ 8~18°C ｜ 东风2级 ｜ AQI 45 优

        这几处不太一致的地方，我得如实说一下。
    """.trimIndent()

    @Test
    fun singleTildeIsNotStrikethrough() {
        val names = parseMarkdown("13~22°C ｜ 南风 13~21°C").all().map { it::class.simpleName }.toList()
        assertFalse("Strikethrough" in names, names.toString())
        assertTrue("13~22°C" in literal("温度 13~22°C，明天 13~21°C"))
        assertTrue("Strikethrough" in parseMarkdown("~~划掉~~").all().map { it::class.simpleName }.toList())
    }

    @Test
    fun looseTablesBecomeTables() {
        val doc = parseMarkdown(weather)
        val cells = doc.all().filterIsInstance<org.commonmark.ext.gfm.tables.TableCell>()
            .map { c -> c.all().filterIsInstance<Text>().joinToString("") { it.literal } }.toList()
        assertEquals(20, cells.size, cells.toString())
        assertEquals(listOf("日期", "天气", "气温", "风", "空气"), cells.take(5))
        assertEquals("13~22°C", cells[7])
        assertTrue("这是结合官方预报" in literal(weather) && "如实说一下" in literal(weather), "表格前后的话还在")
    }

    @Test
    fun ordinaryTextWithPipesIsNotATable() {
        assertEquals("a | b", looseTables("a | b"))
        assertEquals("用 grep | wc 数一下\n然后看结果", looseTables("用 grep | wc 数一下\n然后看结果"))
        // 代码块里的竖线不动
        val code = "```\na | b | c\nd | e | f\n```"
        assertEquals(code, looseTables(code))
        // 规范的表格照常
        val gfm = "| a | b |\n|---|---|\n| 1 | 2 |"
        assertEquals(1, parseMarkdown(gfm).all().filterIsInstance<org.commonmark.ext.gfm.tables.TableBlock>().count())
    }

    private fun names(md: String) = parseMarkdown(md).all().map { it::class.simpleName }.toList()

    @Test
    fun mathBecomesReadable() {
        assertEquals("a² + b² = c²", latexToText("a^2 + b^2 = c^2"))
        assertEquals("c = √(a²+b²)", latexToText("c = \\sqrt{a^2+b^2}"))
        assertEquals("E = mc² × 1/2", latexToText("E = mc^2 \\times \\frac{1}{2}"))
        assertEquals("x ≤ 3, α → ∞", latexToText("x \\le 3, \\alpha \\to \\infty"))
        assertEquals("(n+1)/2", latexToText("\\frac{n+1}{2}"))
        assertEquals("30°", latexToText("30^\\circ"))
        // 行内公式：没有反斜杠、$ 也不见了
        val inline = literal("勾股定理：\$a^2 + b^2 = c^2\$，所以 \\(c = \\sqrt{a^2+b^2}\\)。")
        assertTrue("a² + b² = c²" in inline && "√(a²+b²)" in inline && '\\' !in inline && '$' !in inline, inline)
        // 价钱不是公式
        assertEquals("价格是 \$5 到 \$10 之间。", literal("价格是 \$5 到 \$10 之间。"))
        // 整块公式变成“公式”代码块
        val block = parseMarkdown("看：\n\n\$\$\nE = mc^2\n\$\$\n\n完")
        val code = block.all().filterIsInstance<org.commonmark.node.FencedCodeBlock>().single()
        assertEquals("公式", code.info)
        assertEquals("E = mc²", code.literal.trim())
        // 代码里的 $ 不动
        assertEquals("`\$x^2\$`", chatFixes("`\$x^2\$`"))
    }

    @Test
    fun htmlTagsAreNotShownRaw() {
        assertFalse("<" in literal("第一行<br>第二行 <b>加粗</b> <sup>1</sup>"))
        assertEquals("展开看详情\n里面的内容", htmlBlockText("<details><summary>展开看详情</summary>里面的内容</details>"))
    }

    @Test
    fun commonModelQuirks() {
        // 标题少空格
        assertTrue("Heading" in names("###三步搞定"))
        assertTrue("Heading" !in names("#话题"), "单个 # 可能是话题标签")
        // 有序列表下缩进两格的子项算嵌套
        val nested = parseMarkdown("1. 准备\n  - 子项一\n  - 子项二\n2. 下一步")
        val ordered = nested.all().filterIsInstance<org.commonmark.node.OrderedList>().single()
        assertEquals(2, ordered.all().filterIsInstance<org.commonmark.node.ListItem>().count { it.parent === ordered })
        assertTrue(ordered.all().any { it is org.commonmark.node.BulletList })
        // 单星号斜体挨着中文标点
        assertTrue("Emphasis" in names("*注意：*别删错"))
        assertFalse('*' in literal("*注意：*别删错"))
        // 乘号不当斜体
        assertEquals("2 * 3 * 4", literal("2 * 3 * 4"))
        // 脚注内容不丢
        val foot = literal("脚注示例[^1]。\n\n[^1]: 这是脚注。")
        assertTrue("这是脚注" in foot && "[1]" in foot, foot)
    }
}

package com.hermes.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.hermes.mobile.ui.theme.CodeTextStyle
import com.hermes.mobile.ui.theme.LocalHermesColors
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as TextNode
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/**
 * Markdown 渲染（CommonMark + GitHub 的表格、删除线、任务列表）：标题、段落、多级列表、引用、代码块、表格、分隔线，
 * 行内的粗体、斜体、删除线、`代码`、链接（含裸网址）。中文里常见的 `**注意：**内容` 也能正确加粗（见 [cjkFriendly]）。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    onCopyCode: ((String) -> Unit)? = null,
) {
    val document = remember(text) { parseMarkdown(text) }
    val ctx = MdContext(
        color = color,
        codeBackground = LocalHermesColors.current.codeBackground,
        link = MaterialTheme.colorScheme.primary,
        body = MaterialTheme.typography.bodyLarge,
        onCopyCode = onCopyCode,
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Blocks(document, ctx, depth = 0)
    }
}

private class MdContext(
    val color: Color,
    val codeBackground: Color,
    val link: Color,
    val body: TextStyle,
    val onCopyCode: ((String) -> Unit)?,
)

private val parser: Parser = Parser.builder()
    .extensions(
        listOf(
            TablesExtension.create(),
            // 只认 ~~删除线~~：单个 ~ 在中文里常用来写范围（13~22°C），不能当删除线
            StrikethroughExtension.builder().requireTwoTildes(true).build(),
            TaskListItemsExtension.create(),
        ),
    )
    .build()

internal fun parseMarkdown(text: String): Node = parser.parse(cjkFriendly(looseTables(text)))

private fun Node.children(): Sequence<Node> = generateSequence(firstChild) { it.next }

@Composable
private fun Blocks(parent: Node, ctx: MdContext, depth: Int) {
    parent.children().forEach { Block(it, ctx, depth) }
}

@Composable
private fun Block(node: Node, ctx: MdContext, depth: Int) {
    when (node) {
        is Paragraph -> Text(inlineText(node, ctx), color = ctx.color, style = ctx.body)
        is Heading -> Text(
            inlineText(node, ctx),
            color = ctx.color,
            style = when (node.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                else -> MaterialTheme.typography.titleMedium
            },
        )
        is BulletList -> ListBlock(node, ctx, depth) { _ -> listOf("•", "◦", "▪")[depth.coerceAtMost(2)] }
        is OrderedList -> {
            val start = node.markerStartNumber ?: 1
            ListBlock(node, ctx, depth) { index -> "${start + index}." }
        }
        is BlockQuote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(ctx.color.copy(alpha = 0.35f), RoundedCornerShape(2.dp)),
            )
            Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Blocks(node, MdContext(ctx.color.copy(alpha = 0.8f), ctx.codeBackground, ctx.link, ctx.body, ctx.onCopyCode), depth)
            }
        }
        is FencedCodeBlock -> CodeBlock(node.literal.trimEnd('\n'), node.info?.trim()?.substringBefore(' ')?.ifEmpty { null }, ctx.color, ctx.onCopyCode)
        is IndentedCodeBlock -> CodeBlock(node.literal.trimEnd('\n'), null, ctx.color, ctx.onCopyCode)
        is ThematicBreak -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = ctx.color.copy(alpha = 0.2f))
        is HtmlBlock -> Text(node.literal.trimEnd('\n'), color = ctx.color, style = ctx.body)
        is TableBlock -> Table(node, ctx)
        else -> Blocks(node, ctx, depth)
    }
}

@Composable
private fun ListBlock(list: Node, ctx: MdContext, depth: Int, marker: (Int) -> String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        list.children().filterIsInstance<ListItem>().forEachIndexed { index, item ->
            val task = (item.firstChild as? Paragraph)?.firstChild as? TaskListItemMarker
                ?: item.firstChild as? TaskListItemMarker
            Row {
                if (task != null) {
                    Icon(
                        if (task.isChecked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                        contentDescription = if (task.isChecked) "已完成" else "未完成",
                        tint = ctx.color.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 2.dp, end = 6.dp).size(18.dp),
                    )
                } else {
                    val text = marker(index)
                    Text(
                        text,
                        color = ctx.color.copy(alpha = 0.7f),
                        style = ctx.body,
                        modifier = Modifier.width(if (text.length > 2) 28.dp else 18.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Blocks(item, ctx, depth + 1)
                }
            }
        }
    }
}

/** 表格：每列按最宽的格子定宽（单格最宽 240dp，太长就换行），整体太宽时左右滑。 */
@Composable
private fun Table(table: TableBlock, ctx: MdContext) {
    val rows = table.children().flatMap { section ->
        when (section) {
            is TableHead, is TableBody -> section.children().filterIsInstance<TableRow>().map { it to (section is TableHead) }
            else -> emptySequence()
        }
    }.toList()
    val columns = rows.maxOfOrNull { (row, _) -> row.children().count() } ?: 0
    if (columns == 0) return
    val border = ctx.color.copy(alpha = 0.18f)
    val headerBg = ctx.color.copy(alpha = 0.06f)
    Box(
        Modifier
            .horizontalScroll(rememberScrollState())
            .clip(MaterialTheme.shapes.small)
            .border(1.dp, border, MaterialTheme.shapes.small),
    ) {
        Layout(
            content = {
                rows.forEach { (row, header) ->
                    row.children().filterIsInstance<TableCell>().forEach { cell ->
                        val text = inlineText(cell, ctx)
                        Text(
                            text,
                            color = ctx.color,
                            fontWeight = if (header) FontWeight.Bold else null,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .then(if (header) Modifier.background(headerBg) else Modifier)
                                .border(0.5.dp, border)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                    repeat(columns - row.children().count()) { Box(Modifier.border(0.5.dp, border)) }
                }
            },
        ) { measurables, _ ->
            val maxCell = 240.dp.roundToPx()
            val probes = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(maxCell) }
            val widths = IntArray(columns) { c -> probes.filterIndexed { i, _ -> i % columns == c }.maxOrNull() ?: 0 }
            val rowCount = measurables.size / columns
            // 同一行的格子一样高（背景和边框才整齐）
            val heights = IntArray(rowCount) { r -> (0 until columns).maxOf { c -> measurables[r * columns + c].minIntrinsicHeight(widths[c]) } }
            val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i % columns], heights[i / columns])) }
            layout(widths.sum(), heights.sum()) {
                var y = 0
                for (r in 0 until rowCount) {
                    var x = 0
                    for (c in 0 until columns) {
                        placeables[r * columns + c].place(x, y)
                        x += widths[c]
                    }
                    y += heights[r]
                }
            }
        }
    }
}

private val bareUrl = Regex("""https?://[^\s<>()\[\]{}"'，。；：！？、）】」』]+""")

private fun inlineText(parent: Node, ctx: MdContext): AnnotatedString = buildAnnotatedString {
    fun walk(node: Node) {
        when (node) {
            is TextNode -> {
                val literal = node.literal.replace(CjkSpacer.toString(), "")
                // 正文里的裸网址也能点
                var cursor = 0
                for (match in bareUrl.findAll(literal)) {
                    append(literal.substring(cursor, match.range.first))
                    val url = match.value.trimEnd('.', ',', ';', ':', '!', '?')
                    withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = ctx.link, textDecoration = TextDecoration.Underline)))) { append(url) }
                    append(match.value.removePrefix(url))
                    cursor = match.range.last + 1
                }
                append(literal.substring(cursor))
            }
            is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = ctx.codeBackground)) { append(" ${node.literal} ") }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { node.children().forEach(::walk) }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { node.children().forEach(::walk) }
            is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { node.children().forEach(::walk) }
            is Link -> withLink(LinkAnnotation.Url(node.destination, TextLinkStyles(SpanStyle(color = ctx.link, textDecoration = TextDecoration.Underline)))) {
                node.children().forEach(::walk)
                if (node.firstChild == null) append(node.destination)
            }
            is Image -> withLink(LinkAnnotation.Url(node.destination, TextLinkStyles(SpanStyle(color = ctx.link, textDecoration = TextDecoration.Underline)))) {
                append("🖼 ")
                node.children().forEach(::walk)
                if (node.firstChild == null) append(node.destination)
            }
            is SoftLineBreak, is HardLineBreak -> append('\n')
            is HtmlInline -> append(node.literal)
            is TaskListItemMarker -> Unit
            else -> node.children().forEach(::walk)
        }
    }
    parent.children().forEach(::walk)
}

@Composable
private fun CodeBlock(code: String, language: String?, color: Color, onCopy: ((String) -> Unit)?) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(LocalHermesColors.current.codeBackground, MaterialTheme.shapes.small),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                language ?: "代码",
                style = MaterialTheme.typography.labelMedium,
                color = color.copy(alpha = 0.6f),
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(code))
                    onCopy?.invoke(code)
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "复制代码", modifier = Modifier.size(16.dp), tint = color.copy(alpha = 0.7f))
            }
        }
        Text(
            code,
            style = CodeTextStyle,
            color = color,
            softWrap = false,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        )
    }
}

// ---- 不规范的表格 ----

private val tableSeparatorCell = Regex("""^\s*:?-{1,}:?\s*$""")

private fun tableCells(line: String): List<String>? {
    val normalized = line.replace('｜', '|').trim()
    if ('|' !in normalized) return null
    val cells = normalized.removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
    return cells.takeIf { it.size >= 2 }
}

private fun isSeparatorRow(cells: List<String>) = cells.all { tableSeparatorCell.matches(it) }

/**
 * 模型常把表格写成“用竖线（常是全角的 ｜）隔开的几行字”，没有 `|---|` 分隔行，Markdown 不认，竖线原样露出来。
 * 连续几行列数相同（至少 3 列两行，或 2 列三行）就当表格：竖线换成半角，补上分隔行，前后空一行。
 * 代码块里的不动。
 */
internal fun looseTables(source: String): String {
    if ('|' !in source && '｜' !in source) return source
    val lines = source.lines()
    val out = ArrayList<String>(lines.size + 8)
    var inFence = false
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            inFence = !inFence
            out += line
            i++
            continue
        }
        val first = if (inFence) null else tableCells(line)
        if (first == null) {
            out += line
            i++
            continue
        }
        // 往下找列数相同的连续行
        var end = i + 1
        while (end < lines.size) {
            val cells = tableCells(lines[end]) ?: break
            if (cells.size != first.size) break
            end++
        }
        val rows = (i until end).map { tableCells(lines[it])!! }
        val hasSeparator = rows.size >= 2 && isSeparatorRow(rows[1])
        val bodyRows = rows.size - 1 - (if (hasSeparator) 1 else 0)
        val isTable = !isSeparatorRow(rows[0]) && bodyRows >= 1 && (first.size >= 3 || bodyRows >= 2)
        if (!isTable) {
            out += line
            i++
            continue
        }
        if (out.isNotEmpty() && out.last().isNotBlank()) out += ""
        fun row(cells: List<String>) = "| " + cells.joinToString(" | ") { it.ifEmpty { " " } } + " |"
        out += row(rows[0])
        out += row(List(first.size) { "---" })
        rows.drop(if (hasSeparator) 2 else 1).forEach { out += row(it) }
        if (end < lines.size && lines[end].isNotBlank()) out += ""
        i = end
    }
    return out.joinToString("\n")
}

// ---- 中文友好的粗体 ----

/** 临时插入的“空白”（U+200A，CommonMark 认它是空白），渲染时去掉。 */
private const val CjkSpacer = ' '

/**
 * CommonMark 规定：收尾的 `**` 前面是标点、后面紧跟文字时不算收尾（开头的 `**` 反过来也一样）。
 * 英文里这很少见，中文里却到处都是：`**注意：**这里`、`前面**“引号”**后面`，结果星号原样露出来。
 * 这里按出现顺序把一段里的 `**` 两两配对，在会出问题的那一侧临时插一个空白，让解析器认得出来。
 * 代码块和行内代码里的内容不动。
 */
internal fun cjkFriendly(source: String): String {
    if (!source.contains("**")) return source
    val out = StringBuilder(source.length + 16)
    var inFence = false
    val paragraph = mutableListOf<String>()
    fun flush() {
        if (paragraph.isNotEmpty()) out.append(fixParagraph(paragraph.joinToString("\n"))).append('\n')
        paragraph.clear()
    }
    for (line in source.lines()) {
        val fence = line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")
        when {
            fence -> {
                flush()
                inFence = !inFence
                out.append(line).append('\n')
            }
            inFence -> out.append(line).append('\n')
            line.isBlank() -> {
                flush()
                out.append(line).append('\n')
            }
            else -> paragraph += line
        }
    }
    flush()
    if (!source.endsWith("\n")) out.setLength(out.length - 1)
    return out.toString()
}

private fun isPunct(c: Char): Boolean = when (Character.getType(c).toByte()) {
    Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
    Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
    Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
    -> true
    else -> false
}

private fun fixParagraph(text: String): String {
    // 找出行内代码以外的 `**`（正好两个星号）
    val runs = mutableListOf<Int>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '`') {
            val ticks = text.drop(i).takeWhile { it == '`' }.length
            val close = text.indexOf("`".repeat(ticks), i + ticks)
            i = if (close < 0) i + ticks else close + ticks
            continue
        }
        if (c == '*') {
            val len = text.drop(i).takeWhile { it == '*' }.length
            if (len == 2) runs += i
            i += len
            continue
        }
        i++
    }
    if (runs.size < 2) return text
    val before = mutableSetOf<Int>()
    val after = mutableSetOf<Int>()
    for (k in 0 until runs.size - runs.size % 2) {
        val at = runs[k]
        val prev = text.getOrNull(at - 1)
        val next = text.getOrNull(at + 2)
        if (k % 2 == 0) {
            // 开头：后面是标点、前面是文字 → 在前面垫一个空白
            if (next != null && isPunct(next) && prev != null && !prev.isWhitespace() && !isPunct(prev)) before += at
        } else {
            // 收尾：前面是标点、后面是文字 → 在后面垫一个空白
            if (prev != null && isPunct(prev) && next != null && !next.isWhitespace() && !isPunct(next)) after += at
        }
    }
    if (before.isEmpty() && after.isEmpty()) return text
    val sb = StringBuilder()
    for ((index, ch) in text.withIndex()) {
        if (index in before) sb.append(CjkSpacer)
        sb.append(ch)
        if (index - 1 in after) sb.append(CjkSpacer)
    }
    return sb.toString()
}

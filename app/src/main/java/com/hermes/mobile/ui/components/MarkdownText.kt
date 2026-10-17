package com.hermes.mobile.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hermes.mobile.ui.theme.CodeTextStyle
import com.hermes.mobile.ui.theme.LocalHermesColors

/**
 * 轻量 Markdown 渲染：段落、标题、列表、引用、代码块，以及行内的粗体、斜体、`代码`、链接。
 * 覆盖 Hermes 回复里最常见的格式，表格等复杂格式之后再补。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    onCopyCode: ((String) -> Unit)? = null,
) {
    val blocks = remember(text) { parseBlocks(text) }
    val codeBg = LocalHermesColors.current.codeBackground
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is Block.Paragraph -> Text(inline(block.text, codeBg), color = color, style = MaterialTheme.typography.bodyLarge)
                is Block.Heading -> Text(
                    inline(block.text, codeBg),
                    color = color,
                    style = if (block.level <= 2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                )
                is Block.ListItem -> Row {
                    Text(
                        block.marker,
                        color = color.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.width(if (block.marker.length > 2) 28.dp else 18.dp),
                    )
                    Text(inline(block.text, codeBg), color = color, style = MaterialTheme.typography.bodyLarge)
                }
                is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(color.copy(alpha = 0.35f), RoundedCornerShape(2.dp)),
                    )
                    Text(
                        inline(block.text, codeBg),
                        color = color.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                is Block.Code -> CodeBlock(block.code, block.language, color, onCopyCode)
            }
        }
    }
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

private sealed interface Block {
    data class Paragraph(val text: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class ListItem(val marker: String, val text: String) : Block
    data class Quote(val text: String) : Block
    data class Code(val code: String, val language: String?) : Block
}

private val orderedItem = Regex("""^(\d+)[.)]\s+(.*)""")

private fun parseBlocks(source: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val lines = source.lines()
    val paragraph = StringBuilder()
    fun flush() {
        if (paragraph.isNotBlank()) blocks += Block.Paragraph(paragraph.toString().trim())
        paragraph.clear()
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                flush()
                val language = trimmed.removePrefix("```").trim().ifEmpty { null }
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                blocks += Block.Code(code.toString(), language)
            }
            trimmed.startsWith("#") -> {
                flush()
                val level = trimmed.takeWhile { it == '#' }.length
                blocks += Block.Heading(level, trimmed.drop(level).trim())
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flush()
                blocks += Block.ListItem("•", trimmed.drop(2))
            }
            orderedItem.matches(trimmed) -> {
                flush()
                val match = orderedItem.find(trimmed)!!
                blocks += Block.ListItem("${match.groupValues[1]}.", match.groupValues[2])
            }
            trimmed.startsWith(">") -> {
                flush()
                blocks += Block.Quote(trimmed.removePrefix(">").trim())
            }
            trimmed.isEmpty() -> flush()
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
            }
        }
        i++
    }
    flush()
    return blocks
}

private val inlinePattern = Regex("""`([^`]+)`|\*\*([^*]+)\*\*|\*([^*]+)\*|\[([^]]+)]\(([^)]+)\)""")

private fun inline(text: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in inlinePattern.findAll(text)) {
        append(text.substring(cursor, match.range.first))
        val (code, bold, italic, linkText) = match.destructured
        when {
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(" $code ") }
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
            italic.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
            linkText.isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(linkText) }
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}

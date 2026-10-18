package com.hermes.mobile.data.remote

import com.hermes.mobile.data.model.FileRef

/**
 * 文件在消息正文里的写法。Hermes 的 API 只收图片，所以文件都变成正文里的一段：
 *
 * ```
 * 📎 附件：notes.txt
 * ```text
 * 文件内容……
 * ```
 * ```
 *
 * 模型能直接读；App 读聊天记录时再按 [parse] 拆回文件卡片，气泡里不显示大段内容。
 */
internal object AttachmentText {
    const val Marker = "📎 附件："

    /** 文本文件：内容放进代码块。围栏比内容里最长的一串反引号还长，内容里有 ``` 也不会断。 */
    fun textFile(name: String, text: String): String {
        val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(maxOf(3, longest + 1))
        return "$Marker$name\n$fence${languageOf(name)}\n${text.trimEnd()}\n$fence"
    }

    fun pdf(name: String, totalPages: Int, shownPages: Int): String =
        if (shownPages >= totalPages) "$Marker$name（共 $totalPages 页，已转成图片附在后面）"
        else "$Marker$name（共 $totalPages 页，前 $shownPages 页已转成图片附在后面）"

    fun uploaded(name: String, path: String): String = "$Marker$name（已保存到 $path，需要时请用工具读取）"

    /** 从一条用户消息里拆出文件：返回去掉文件段之后的正文和文件列表。 */
    fun parse(text: String): Pair<String, List<FileRef>> {
        if (!text.contains(Marker)) return text to emptyList()
        val lines = text.lines()
        val kept = mutableListOf<String>()
        val files = mutableListOf<FileRef>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (!line.startsWith(Marker)) {
                kept += line
                i++
                continue
            }
            val head = line.removePrefix(Marker)
            val name = head.substringBefore('（').trim()
            val note = head.substringAfter('（', "").removeSuffix("）").takeIf { it.isNotEmpty() }
            val next = lines.getOrNull(i + 1)
            val fence = next?.let { Regex("^(`{3,})").find(it)?.value }
            if (fence != null) {
                // 跳过整个代码块
                var end = i + 2
                while (end < lines.size && lines[end] != fence) end++
                val size = lines.subList(i + 2, minOf(end, lines.size)).joinToString("\n").toByteArray().size.toLong()
                files += FileRef(name, size, note)
                i = end + 1
            } else {
                files += FileRef(name, null, note?.let(::friendlyNote))
                i++
            }
        }
        return kept.joinToString("\n").trim() to files
    }

    /** 给模型看的说明换成给人看的。 */
    private fun friendlyNote(note: String): String = when {
        note.startsWith("已保存到 ") -> "已上传：" + note.removePrefix("已保存到 ").substringBefore('，')
        note.contains("已转成图片") -> note.substringBefore('，')
        else -> note
    }

    private fun languageOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "kt", "kts" -> "kotlin"
        "py" -> "python"
        "js", "mjs", "cjs" -> "javascript"
        "ts" -> "typescript"
        "sh", "bash", "zsh" -> "bash"
        "json" -> "json"
        "yml", "yaml" -> "yaml"
        "md" -> "markdown"
        "csv" -> "csv"
        "html", "htm" -> "html"
        "xml" -> "xml"
        "toml" -> "toml"
        "sql" -> "sql"
        "c", "h" -> "c"
        "cpp", "cc", "hpp" -> "cpp"
        "java" -> "java"
        "go" -> "go"
        "rs" -> "rust"
        else -> "text"
    }
}

/**
 * Hermes 把收到的图片存到服务器上，聊天记录里只留一行 `[Image attached at: <路径>]`（后面跟一行 `[screenshot]`）。
 * 路径里的目录名就是图片内容的 SHA-256；App 发图时用同样的规则缓存在本机，所以能找回原图。
 */
internal object ImageMarkers {
    private val attachedAt = Regex("""^\[Image attached at: (.+)]$""")
    private val attachedUrl = Regex("""^\[Image attached: (.+)]$""")
    private val screenshot = Regex("""^\[screenshot( detail=\w+)?]$""")
    private val sha = Regex("""[0-9a-f]{64}""")

    /**
     * 一张图：服务器上的路径 [path] 和它的 [sha256]（能找回本机缓存时用），或远端 [url]。
     * 都为 null 表示 Hermes 只记了一个 `[screenshot]`，图在哪不知道。
     */
    data class Found(val sha256: String?, val url: String?, val path: String? = null)

    fun parse(text: String): Pair<String, List<Found>> {
        if (!text.contains("[Image attached") && !text.contains("[screenshot")) return text to emptyList()
        val found = mutableListOf<Found>()
        var screenshots = 0
        val kept = text.lines().filter { raw ->
            val line = raw.trim()
            attachedAt.find(line)?.let { m ->
                val path = m.groupValues[1].trim()
                found += Found(sha.findAll(path).lastOrNull()?.value, null, path)
                return@filter false
            }
            attachedUrl.find(line)?.let { m ->
                found += Found(null, m.groupValues[1])
                return@filter false
            }
            if (screenshot.matches(line)) {
                screenshots++
                return@filter false
            }
            true
        }
        // 有的模式下 Hermes 只记 [screenshot]、不记路径：每个多出来的 [screenshot] 也算一张图
        repeat((screenshots - found.size).coerceAtLeast(0)) { found += Found(null, null) }
        return kept.joinToString("\n").trim() to found
    }
}

/**
 * Hermes 回复里的 `MEDIA:<路径>`：它生成或要发给你的文件（图片、音频……）。
 * 发给聊天软件时 Hermes 会把它变成附件，API 这边原样保留，App 自己处理：图片显示出来，其它文件显示成卡片。
 */
internal object MediaTags {
    private val tag = Regex("""MEDIA:\s*(?:`|"|')?((?:[A-Za-z]:[/\\]|/|~/)[^\s`"']+)(?:`|"|')?""")
    private val imageExt = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    data class Found(val path: String, val isImage: Boolean)

    fun parse(text: String): Pair<String, List<Found>> {
        if (!text.contains("MEDIA:")) return text to emptyList()
        val found = mutableListOf<Found>()
        val cleaned = tag.replace(text) { m ->
            val path = m.groupValues[1].trimEnd('.', ',', ')', '）', '。')
            found += Found(path, path.substringAfterLast('.', "").lowercase() in imageExt)
            ""
        }
        // 去掉标签后留下的空行
        val tidy = cleaned.lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim()
        return tidy to found
    }
}

/** 服务器上的文件：界面通过仓库下载（SSH）后再显示。 */
internal const val RemoteMediaPrefix = "hermes-media:"

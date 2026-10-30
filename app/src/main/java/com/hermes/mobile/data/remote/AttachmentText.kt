package com.hermes.mobile.data.remote

import com.hermes.mobile.data.model.FileRef

/**
 * 文件在消息正文里的写法。Hermes 的 API 只收文字和图片，文件原样经 SSH 传到 Hermes 那台电脑上，
 * 消息里只写一行告诉它文件在哪：
 *
 * ```
 * 📎 附件：notes.txt（已保存到 /home/me/hermes-uploads/notes.txt，……）
 * ```
 *
 * App 读聊天记录时再按 [parse] 拆回文件卡片。以前的版本把文本文件内容放进代码块、PDF 转成图片，
 * 那样的旧消息 [parse] 也还认得。
 */
internal object AttachmentText {
    const val Marker = "📎 附件："

    fun uploaded(name: String, path: String): String = "$Marker$name（已保存到 $path，这是用户发来的文件，请用工具打开读取）"

    /** 图片原文件在 Hermes 那台机器上的位置（给模型用，界面上不显示这一行）。 */
    const val ImageFileMarker = "📎 图片文件："

    fun imageFile(path: String, index: Int, total: Int): String =
        "$ImageFileMarker$path（" + (if (total > 1) "第 $index 张图片" else "这张图片") + "的原文件，需要上传、保存或用工具处理图片时用这个路径）"

    /** 从一条用户消息里拆出文件：返回去掉文件段之后的正文和文件列表。 */
    fun parse(raw: String): Pair<String, List<FileRef>> {
        val text = if (raw.contains(ImageFileMarker)) raw.lines().filterNot { it.startsWith(ImageFileMarker) }.joinToString("\n").trim() else raw
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
                val content = lines.subList(i + 2, minOf(end, lines.size)).joinToString("\n")
                files += FileRef(name, content.toByteArray().size.toLong(), note, inlineText = content)
                i = end + 1
            } else {
                val uploaded = note?.takeIf { it.startsWith("已保存到 ") }?.removePrefix("已保存到 ")?.substringBefore('，')
                files += FileRef(name, null, note?.let(::friendlyNote), remotePath = uploaded)
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
}

/**
 * Hermes 把收到的图片存到服务器上，聊天记录里只留一行 `[Image attached at: <路径>]`（后面跟一行 `[screenshot]`）。
 * 路径里的目录名就是图片内容的 SHA-256；App 发图时用同样的规则缓存在本机，所以能找回原图。
 */
internal object ImageMarkers {
    private val attachedAt = Regex("""^\[Image attached at: (.+)]$""")
    private val attachedUrl = Regex("""^\[Image attached: (.+)]$""")
    private val screenshot = Regex("""^\[screenshot( detail=\w+)?]$""")
    /** 经 gateway 发的图：Hermes 先让看图模型写一段描述放在消息最前面，再附一行原图路径。 */
    private val visionHint = Regex("""^\[If you need a closer look, use vision_analyze with image_url: (\S+)\s*~?]$""")
    private val sha = Regex("""[0-9a-f]{64}""")

    /**
     * 一张图：服务器上的路径 [path] 和它的 [sha256]（能找回本机缓存时用），或远端 [url]。
     * 都为 null 表示 Hermes 只记了一个 `[screenshot]`，图在哪不知道。
     */
    data class Found(val sha256: String?, val url: String?, val path: String? = null)

    fun parse(text: String): Pair<String, List<Found>> {
        if (!text.contains("[Image attached") && !text.contains("[screenshot") && !text.contains("vision_analyze") && !text.contains("[The user sent")) {
            return text to emptyList()
        }
        val found = mutableListOf<Found>()
        var screenshots = 0
        var inDescription = false
        val kept = text.lines().filter { raw ->
            val line = raw.trim()
            // “[The user sent an image~ Here's what I can see: …]” 可能有好几行，整段去掉
            if (inDescription || line.startsWith("[The user sent")) {
                inDescription = !line.endsWith("]")
                return@filter false
            }
            visionHint.find(line)?.let { m ->
                val path = m.groupValues[1].trim()
                found += Found(sha.findAll(path).lastOrNull()?.value, null, path)
                return@filter false
            }
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

    fun isImagePath(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in imageExt

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

/**
 * 回复里直接写出来的文件路径（模型没用 MEDIA: 标签时）。只认绝对路径（或 ~/ 开头）且带扩展名的，
 * 正文不改，额外做成文件卡片 / 图片，点开时经 SSH 下载。
 */
internal object PlainPaths {
    private val path = Regex("""(?<![\w/.~:])((?:~|/[A-Za-z0-9_.-]+)(?:/[^\s`'"<>()（）\[\]，。；：！？、]+)+\.[A-Za-z0-9]{1,8})(?![\w/])""")

    fun find(text: String): List<String> =
        path.findAll(text).map { it.groupValues[1].trimEnd('.', ',') }
            .filter { it.count { c -> c == '/' } >= 2 && !it.startsWith("//") }
            .distinct().take(6).toList()
}

/** 服务器上的文件：界面通过仓库下载（SSH）后再显示。 */
internal const val RemoteMediaPrefix = "hermes-media:"

/**
 * Hermes 有时会在用户消息后面追加给模型看的系统提示，例如换了客户端时的
 * `[System: This conversation is now being answered on a different interface: …]`。
 * 这些不是用户说的话，显示前去掉（按方括号配对，提示里可以有嵌套的方括号和换行）。
 */
/**
 * 插话（steer）存进记录时被 Hermes 包上一层说明：
 * `[OUT-OF-BAND USER MESSAGE — …]` 正文 `[/OUT-OF-BAND USER MESSAGE]`。显示时只留正文。
 */
internal object SteerMarker {
    private val open = Regex("""\[OUT-OF-BAND USER MESSAGE[^\]]*\]\s*""")
    private val close = Regex("""\s*\[/OUT-OF-BAND USER MESSAGE\]""")

    fun strip(text: String): String =
        if ("OUT-OF-BAND USER MESSAGE" !in text) text else text.replace(open, "").replace(close, "").trim()
}

internal object SystemNotes {
    // [IMPORTANT: You are running as a scheduled cron job …] 是定时任务给模型的说明
    private val start = Regex("""\[(System( note)?|IMPORTANT):""")

    fun strip(text: String): String {
        if (!text.contains("[System") && !text.contains("[IMPORTANT")) return text
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val m = start.find(text, i)
            if (m == null) {
                out.append(text, i, text.length)
                break
            }
            out.append(text, i, m.range.first)
            var depth = 0
            var j = m.range.first
            while (j < text.length) {
                when (text[j]) {
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                j++
            }
            // 没有配对的右括号：保留原文，避免误删
            if (j >= text.length) {
                out.append(text, m.range.first, text.length)
                break
            }
            i = j + 1
        }
        return out.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }
}

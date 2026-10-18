package com.hermes.mobile.data.remote

import com.hermes.mobile.data.model.GatewayStatus

import com.hermes.mobile.data.model.Skill

import com.hermes.mobile.data.model.ChatItem
import com.hermes.mobile.data.model.CronJob
import com.hermes.mobile.data.model.FileRef
import com.hermes.mobile.data.model.JobRunResult
import com.hermes.mobile.data.model.JobSchedule
import com.hermes.mobile.data.model.parseInstant
import com.hermes.mobile.data.model.friendlyTime
import com.hermes.mobile.data.model.ImageRef
import com.hermes.mobile.data.model.Quote
import com.hermes.mobile.data.model.Role
import com.hermes.mobile.data.model.Session
import com.hermes.mobile.data.model.SessionSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant

/** 服务器时间 → 毫秒。秒级浮点数、毫秒整数、数字字符串、ISO 时间都认。 */
internal fun JsonElement?.epochMillis(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    primitive.doubleOrNull?.let { n ->
        return if (n > 1e12) n.toLong() else (n * 1000).toLong()
    }
    val text = primitive.contentOrNull?.trim().orEmpty()
    if (text.isEmpty()) return null
    text.toDoubleOrNull()?.let { n -> return if (n > 1e12) n.toLong() else (n * 1000).toLong() }
    return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
}

internal fun SessionDto.toSession(now: Long): Session = Session(
    id = id,
    title = title?.takeIf { it.isNotBlank() } ?: preview?.lineSequence()?.firstOrNull()?.take(24)?.takeIf { it.isNotBlank() } ?: "未命名会话",
    preview = cleanPreview(preview).ifEmpty { if (source == "cron") "定时任务运行记录" else "" },
    updatedAt = last_active.epochMillis() ?: started_at.epochMillis() ?: now,
    source = SessionSource(source ?: "api_server"),
    pinned = pinned == true,
    model = model,
)

/** 列表里不该出现的会话：子代理内部会话、被隐藏或归档的会话。 */
internal fun SessionDto.visibleInList(): Boolean =
    is_internal_child != true && hidden != true && archived != true

/**
 * 把一条服务器消息变成聊天气泡；不该显示的返回 null。
 *
 * 只保留：用户消息（有文字或图片），以及 Hermes 有正文的回复。
 * 丢弃：工具消息、系统消息、隐藏消息、只有工具调用没有文字的中间步骤。
 * 思考内容（reasoning / reasoning_content）在 [MessageDto] 里根本没有声明，到不了这里。
 */
internal fun MessageDto.toChatItem(index: Int, fallbackTime: Long, imageSource: (sha256: String) -> String? = { null }): ChatItem.Message? {
    if (display_kind == "hidden") return null
    val role = when (role) {
        "user" -> Role.User
        "assistant" -> Role.Assistant
        else -> return null
    }
    // 调用工具的中间步骤：就算带了几句话（“我先查一下”），也属于过程，不显示
    if (role == Role.Assistant && finish_reason == "tool_calls") return null
    val (rawText, partImages) = content.parseContent(idSeed = index, imageSource = imageSource)
    val text: String
    val images: List<ImageRef>
    val files: List<FileRef>
    if (role == Role.User) {
        // 用户消息：拆出 Hermes 记下的图片路径和 App 写进去的文件段
        val (withoutImages, marked) = ImageMarkers.parse(SystemNotes.strip(SteerMarker.strip(rawText)))
        val (body, attached) = AttachmentText.parse(withoutImages)
        text = body
        files = attached
        images = partImages + marked.mapIndexed { i, found ->
            ImageRef(
                id = "img-$index-m$i",
                hue = ((index * 47 + i * 89) % 360).toFloat(),
                aspect = 1.3f,
                // 本机缓存 → 服务器上的原图（能经 SSH 下载时）→ 远端地址
                source = found.sha256?.let(imageSource) ?: found.path?.let { RemoteMediaPrefix + it } ?: found.url,
            )
        }
    } else {
        val (body, media) = replyMedia(rawText)
        text = body
        images = partImages + media.images.mapIndexed { i, path ->
            ImageRef("img-$index-r$i", hue = ((index * 31 + i * 67) % 360).toFloat(), aspect = 1f, source = RemoteMediaPrefix + path)
        }
        files = media.files
    }
    if (role == Role.Assistant && text.isBlank() && images.isEmpty() && files.isEmpty()) return null
    if (role == Role.User && text.isBlank() && images.isEmpty() && files.isEmpty()) return null
    val messageId = when (val raw = id) {
        is JsonPrimitive -> raw.contentOrNull
        else -> null
    } ?: "idx-$index"
    val (quote, body) = if (role == Role.User) splitQuote(text.trim()) else null to text.trim()
    return ChatItem.Message(
        id = messageId,
        time = timestamp.epochMillis() ?: fallbackTime,
        role = role,
        text = body,
        images = images,
        quote = quote,
        files = files,
    )
}

/** App 发引用时，把引用内容写成 Markdown 引用块放在正文前面。 */
internal fun quotedInput(quote: Quote?, text: String): String {
    if (quote == null) return text
    val block = quote.text.trim().lines().joinToString("\n") { "> $it" }
    return "$block\n\n$text"
}

/**
 * 反过来：用户消息以引用块开头时拆出引用，聊天记录里照样显示成引用样式。
 * 引用指向哪条回复先留空，由 [linkQuotes] 补上。
 */
private fun splitQuote(text: String): Pair<Quote?, String> {
    if (!text.startsWith(">")) return null to text
    val lines = text.lines()
    val quoteLines = lines.takeWhile { it.startsWith(">") }
    val rest = lines.drop(quoteLines.size).joinToString("\n").trim()
    if (rest.isEmpty()) return null to text
    val quoted = quoteLines.joinToString("\n") { it.removePrefix(">").removePrefix(" ") }.trim()
    return Quote(messageId = "", text = quoted, partial = true) to rest
}

/** 给拆出来的引用找到被引用的那条回复（往前找第一条包含这段文字的回复）。 */
internal fun List<ChatItem.Message>.linkQuotes(): List<ChatItem.Message> = mapIndexed { i, m ->
    val quote = m.quote ?: return@mapIndexed m
    if (quote.messageId.isNotEmpty()) return@mapIndexed m
    val target = subList(0, i).lastOrNull { it.role == Role.Assistant && it.text.contains(quote.text) }
    m.copy(quote = quote.copy(messageId = target?.id.orEmpty(), partial = target?.text?.trim() != quote.text))
}

/** content 可能是字符串，也可能是 `[{"type":"text","text":...},{"type":"image_url",...}]`。 */
private fun JsonElement?.parseContent(idSeed: Int, imageSource: (String) -> String?): Pair<String, List<ImageRef>> = when (this) {
    null, is JsonNull -> "" to emptyList()
    is JsonPrimitive -> contentOrNull.orEmpty() to emptyList()
    is JsonArray -> {
        val text = StringBuilder()
        val images = mutableListOf<ImageRef>()
        forEachIndexed { i, part ->
            val obj = part as? JsonObject ?: return@forEachIndexed
            when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
                "text", "input_text", "output_text" -> {
                    val t = (obj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    if (t.isNotBlank()) {
                        if (text.isNotEmpty()) text.append("\n\n")
                        text.append(t)
                    }
                }
                "image_url", "input_image", "image" -> images += ImageRef(
                    "img-$idSeed-$i",
                    hue = ((idSeed * 47 + i * 89) % 360).toFloat(),
                    aspect = 1.3f,
                    source = obj.imageUrl()?.let { url ->
                        // 新版 Hermes 存档时把 data: 图片换成了 hermes-retained:<sha256>
                        if (url.startsWith("hermes-retained:")) imageSource(url.removePrefix("hermes-retained:")) else url
                    },
                )
            }
        }
        text.toString() to images
    }
    is JsonObject -> ((this["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()) to emptyList()
}

/** `{"image_url": {"url": ...}}` 或 `{"image_url": "..."}`（Responses 格式）。 */
private fun JsonObject.imageUrl(): String? = when (val v = this["image_url"]) {
    is JsonPrimitive -> v.contentOrNull
    is JsonObject -> (v["url"] as? JsonPrimitive)?.contentOrNull
    else -> null
}?.takeIf { it.isNotBlank() }

/** 回复里要给用户的文件：MEDIA: 标签（去掉）+ 正文里直接写出的路径（保留）。 */
internal data class ReplyMedia(val images: List<String>, val files: List<FileRef>)

internal fun replyMedia(raw: String): Pair<String, ReplyMedia> {
    val (body, tagged) = MediaTags.parse(raw)
    val plain = PlainPaths.find(body).filter { p -> tagged.none { it.path == p } }
    val all = tagged.map { it.path to it.isImage } + plain.map { it to MediaTags.isImagePath(it) }
    val images = all.filter { it.second }.map { it.first }
    val files = all.filterNot { it.second }.map { (path, _) -> FileRef(path.substringAfterLast('/'), note = "在 Hermes 那台机器上", remotePath = path) }
    return body to ReplyMedia(images, files)
}

/** Hermes 的定时任务 → App 里的任务卡片。 */
internal fun JobDto.toCronJob(now: Long): CronJob {
    val raw = schedule?.expr ?: schedule?.display ?: schedule_display.orEmpty()
    val parsed = JobSchedule.parse(schedule?.kind, schedule?.expr ?: schedule_display, schedule?.minutes, schedule?.run_at)
    val deliverValue = when (val d = deliver) {
        is JsonPrimitive -> d.contentOrNull
        is JsonArray -> d.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(",")
        else -> null
    }?.takeIf { it.isNotBlank() } ?: "local"
    val lastRun = parseInstant(last_run_at)
    val nowInstant = java.time.Instant.ofEpochMilli(now)
    return CronJob(
        id = id,
        name = name?.takeIf { it.isNotBlank() } ?: "未命名任务",
        prompt = prompt.orEmpty(),
        scheduleLabel = if (parsed is JobSchedule.Custom) (schedule_display ?: raw).ifBlank { "未设置" } else parsed.label(),
        cron = if (parsed is JobSchedule.Custom) raw else parsed.toHermes(),
        nextRunLabel = parseInstant(next_run_at)?.let { friendlyTime(it, nowInstant) } ?: "—",
        lastResult = when {
            lastRun == null -> JobRunResult.Never
            last_status == null || last_status == "ok" || last_status == "success" -> JobRunResult.Success
            else -> JobRunResult.Failed
        },
        lastRunLabel = lastRun?.let { friendlyTime(it, nowInstant) },
        enabled = enabled != false && state != "paused",
        deliverTo = deliverLabel(deliverValue),
        deliver = deliverValue,
        lastError = last_error,
        schedule = parsed,
    )
}

/** deliver 值 → 给人看的说法。 */
internal fun deliverLabel(deliver: String): String = deliver.split(',').joinToString("、") { d ->
    when (d.trim().substringBefore(':').lowercase()) {
        "local", "origin", "" -> "在 App 里查看"
        "telegram" -> "Telegram"
        "discord" -> "Discord"
        "slack" -> "Slack"
        "whatsapp" -> "WhatsApp"
        "signal" -> "Signal"
        "email" -> "邮件"
        "sms" -> "短信"
        else -> d.trim()
    }
}

/** 列表预览去掉 Hermes 给模型的提示（定时任务的 [IMPORTANT: …] 很长，预览里常常只有半截）。 */
internal fun cleanPreview(preview: String?): String {
    val text = SystemNotes.strip(preview.orEmpty()).replace('\n', ' ').trim()
    return if (text.startsWith("[IMPORTANT") || text.startsWith("[System")) "" else text
}

/**
 * 解析经 SSH 列出的技能：每行是 `./分类/名字/SKILL.md<TAB>name:值<TAB>description:值<TAB>`。
 */
internal fun parseSkillListing(output: String): List<Skill> = output.lines().mapNotNull { line ->
    val parts = line.split('\t')
    val path = parts.firstOrNull()?.removePrefix("./")?.takeIf { it.endsWith("SKILL.md") } ?: return@mapNotNull null
    val dirs = path.removeSuffix("SKILL.md").trimEnd('/').split('/').filter { it.isNotEmpty() }
    if (dirs.isEmpty()) return@mapNotNull null
    fun field(key: String) = parts.firstOrNull { it.startsWith("$key:") }?.removePrefix("$key:")?.trim()?.trim('"', '\'')
    Skill(
        name = field("name")?.takeIf { it.isNotEmpty() } ?: dirs.last(),
        description = field("description").orEmpty().takeUnless { it == ">" || it == "|" }.orEmpty(),
        category = if (dirs.size >= 2) dirs[dirs.size - 2] else "",
    )
}.sortedWith(compareBy({ it.category }, { it.name }))

/** `/health/detailed` → 状态页要显示的东西。各字段都可能缺。 */
internal fun JsonObject.toGatewayStatus(): GatewayStatus {
    fun JsonObject.obj(key: String) = this[key] as? JsonObject
    fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
    val checks = obj("readiness")?.obj("checks").orEmpty().map { (name, value) ->
        val c = value as? JsonObject
        val status = c?.str("status")
        val detail = when (name) {
            "disk" -> c?.num("used_percent")?.let { "已用 ${"%.0f".format(it)}%" }
            "gateway" -> c?.str("state")
            else -> c?.str("error") ?: c?.str("message")
        }
        GatewayStatus.Check(name, status == "ok", detail)
    }
    val platforms = obj("platforms").orEmpty().map { (name, value) ->
        val p = value as? JsonObject
        GatewayStatus.Platform(name, p?.str("state") ?: "unknown", p?.str("error_message") ?: p?.str("error_code"))
    }
    val today = obj("metrics_today") ?: obj("api_server")?.obj("metrics_today")
    return GatewayStatus(
        ok = str("status") == "ok",
        state = str("gateway_state"),
        checks = checks,
        platforms = platforms,
        activeAgents = num("active_agents")?.toInt(),
        requestsToday = today?.num("requests")?.toInt(),
        messagesToday = today?.num("messages")?.toInt(),
        tokensToday = today?.num("tokens")?.toLong(),
        diskUsedPercent = obj("readiness")?.obj("checks")?.obj("disk")?.num("used_percent"),
    )
}

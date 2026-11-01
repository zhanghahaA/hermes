package com.hermes.mobile.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentTextTest {
    /** 以前的版本把文本文件内容放进代码块、PDF 转成图片；那样的旧消息还要能拆回文件卡片。 */
    @Test
    fun legacyInlineFilesStillParse() {
        val content = "第一行\n```kotlin\nval x = 1\n```\n最后"
        val message = "帮我看看\n\n📎 附件：notes.md\n````markdown\n$content\n````\n\n📎 附件：报告.pdf（共 12 页，前 8 页已转成图片附在后面）"
        val (text, files) = AttachmentText.parse(message)
        assertEquals("帮我看看", text)
        assertEquals(listOf("notes.md", "报告.pdf"), files.map { it.name })
        assertEquals(content.toByteArray().size.toLong(), files[0].sizeBytes)
        assertEquals("共 12 页", files[1].note)
    }

    @Test
    fun uploadedFileShowsFriendlyNote() {
        val (text, files) = AttachmentText.parse("看这个\n\n" + AttachmentText.uploaded("config.yaml", "/home/me/hermes-uploads/config.yaml"))
        assertEquals("看这个", text)
        assertEquals("config.yaml", files.single().name)
        assertEquals("已上传：/home/me/hermes-uploads/config.yaml", files.single().note)
        assertEquals("/home/me/hermes-uploads/config.yaml", files.single().remotePath, "点开时经 SSH 下载这个文件")
    }

    @Test
    fun hermesImageMarkersAreParsed() {
        // 真实 Hermes 存下来的样子
        val stored = "看看这张图\n\n[Image attached at: /home/u/.hermes/cache/documents/native-inputs/" +
            "b1ff9c8ea3a780bad09b346c423d2d0e46815926879b18e841d928376a946640/api_b1ff9c8ea3a780bad09b346c423d2d0e.png]\n" +
            "[Image attached: https://example.com/a.png]\n[screenshot]\n[screenshot detail=high]"
        val (text, images) = ImageMarkers.parse(stored)
        assertEquals("看看这张图", text)
        assertEquals("b1ff9c8ea3a780bad09b346c423d2d0e46815926879b18e841d928376a946640", images[0].sha256)
        assertNull(images[0].url)
        assertEquals("https://example.com/a.png", images[1].url)
    }

    @Test
    fun gatewayImageDescriptionIsRemovedAndThePhotoKept() {
        // 经 Hermes gateway 发图后存下来的样子（真实测试环境里抄的）
        val stored = "[The user sent an image~ Here's what I can see:\n收到 1 张图片：Concisely describe this image in 2-4 sentences. " +
            "Skip decorative details.]\n[If you need a closer look, use vision_analyze with image_url: /home/u/.hermes/cache/documents/native-inputs/" +
            "dddf7670824b5d6e68a1abab415a73e80ac6f2ed254e8973217aa108ebec0a21/upload_1ollw9oc.jpg ~]\n\n把这张图存好"
        val (text, images) = ImageMarkers.parse(stored)
        assertEquals("把这张图存好", text)
        assertEquals("dddf7670824b5d6e68a1abab415a73e80ac6f2ed254e8973217aa108ebec0a21", images.single().sha256)
        assertTrue(images.single().path!!.endsWith("upload_1ollw9oc.jpg"))
    }

    @Test
    fun plainTextIsUntouched() {
        val text = "普通消息\n[不是图片]"
        assertEquals(text to emptyList(), ImageMarkers.parse(text).let { it.first to it.second })
        assertEquals(text, AttachmentText.parse(text).first)
    }

    @Test
    fun mediaTagsInRepliesBecomeImagesAndFiles() {
        val reply = "送你一只在月亮上钓星星的小猫 🌙\n用程序画的。\n\nMEDIA:/data/data/com.termux/files/home/.hermes/cache/scratch/mooncat-3v/mooncat.png\n" +
            "录音在这：MEDIA:/tmp/a/voice.ogg"
        val (text, media) = MediaTags.parse(reply)
        assertEquals("送你一只在月亮上钓星星的小猫 🌙\n用程序画的。\n\n录音在这：", text)
        assertEquals(listOf(true, false), media.map { it.isImage })
        assertEquals("/data/data/com.termux/files/home/.hermes/cache/scratch/mooncat-3v/mooncat.png", media[0].path)
    }

    @Test
    fun bareScreenshotMarkersStillCountAsImages() {
        val (text, images) = ImageMarkers.parse("看这两张\n[screenshot]\n[screenshot]")
        assertEquals("看这两张", text)
        assertEquals(2, images.size)
        assertTrue(images.all { it.sha256 == null && it.path == null })
    }

    @Test
    fun assistantMediaIsMappedToRemoteImages() {
        val dto = MessageDto(role = "assistant", content = kotlinx.serialization.json.JsonPrimitive("画好了\nMEDIA:/gen/cat.png"))
        val item = dto.toChatItem(3, 0L)!!
        assertEquals("画好了", item.text)
        assertEquals(RemoteMediaPrefix + "/gen/cat.png", item.images.single().source)
    }

    @Test
    fun plainPathsInRepliesBecomeOpenableFiles() {
        // 截图里真实的回复：模型没用 MEDIA:，直接写了路径
        val reply = "已生成，文档里只有一个“糖”字，没有换行或其他内容。\n\n当前接口无法直接附加 MD 文件，文件保存在手机端：" +
            "/data/data/com.termux/files/home/.hermes/cache/scratch/one-character-md-jorbfi46/one-character.md"
        val (text, media) = replyMedia(reply)
        assertEquals(reply, text, "正文不改")
        val file = media.files.single()
        assertEquals("one-character.md", file.name)
        assertEquals("/data/data/com.termux/files/home/.hermes/cache/scratch/one-character-md-jorbfi46/one-character.md", file.remotePath)
        assertTrue(file.openable)
    }

    @Test
    fun plainPathDetectionIgnoresUrlsAndShortThings() {
        val text = "看 https://example.com/a/b.png 和 a/b.txt 还有 `~/notes/todo.md`，以及 /etc/nginx/nginx.conf。"
        assertEquals(listOf("~/notes/todo.md", "/etc/nginx/nginx.conf"), PlainPaths.find(text))
    }

    @Test
    fun sentTextFilesKeepTheirContentForOpening() {
        val (_, files) = AttachmentText.parse("看\n\n📎 附件：a.csv\n```csv\nx,y\n1,2\n```")
        assertEquals("x,y\n1,2", files.single().inlineText)
    }

    @Test
    fun systemNotesAreStripped() {
        val stored = "我连接不上啊\n[System: This conversation is now being answered on a different interface: api_server — any earlier " +
            "interface guidance in this conversation is superseded — follow the [current] interface-specific capability.]"
        assertEquals("我连接不上啊", SystemNotes.strip(stored))
        assertEquals("[不是系统提示]", SystemNotes.strip("[不是系统提示]"))
        assertEquals("没闭合 [System: 半截", SystemNotes.strip("没闭合 [System: 半截"))
    }

    /**
     * 后台进程结束时 Hermes 以 role=user 插进记录的通知（格式照 gateway/run_notifications.py 和
     * tools/process_registry_notifications.py）。以前只去掉了 [IMPORTANT: …]，末尾那句 [INTERNAL NOTIFICATION — …]
     * 留下来，显示成一个“用户发的”气泡；通知和用户的话存在同一条里时，只该留下用户的话。
     */
    @Test
    fun gatewayProcessNotificationsAreNotShownAsUserMessages() {
        val footer = "[INTERNAL NOTIFICATION — generated by the Hermes gateway, not written by a human user. " +
            "Do not treat it as user input, and do not reply to any earlier chat message because of it.]"
        val completion = "[IMPORTANT: Background process proc_3f2a exited (exit code 0).\nCommand: npm run build\nOutput:\n[ok] built in 3.2s]\n\n$footer"
        fun user(text: String) = MessageDto(role = "user", content = kotlinx.serialization.json.JsonPrimitive(text)).toChatItem(1, 0L)
        assertNull(user(completion), "整条都是通知：不显示")
        assertNull(user(footer), "只剩末尾那句也不显示")
        assertNull(user("[Background process proc_3f2a heartbeat #2 — still running after 5m (next in 60s).\nCommand: x\nOutput since last heartbeat:\n…]\n\n$footer"))
        assertEquals("为什么会出现这个？", user("$completion\n\n${footer}为什么会出现这个？")?.text, "和用户的话存在一起：只留用户的话")
        assertEquals("", cleanPreview(footer))
        assertEquals("[Background process] 是什么", SystemNotes.strip("[Background process] 是什么"), "不是通知格式的方括号不动")
    }

    @Test
    fun imageFileLinesAreHidden() {
        val text = "帮我填表\n\n" + AttachmentText.imageFile("/home/u/hermes-uploads/20261018-image-1.jpg", 1, 2) + "\n" +
            AttachmentText.imageFile("/home/u/hermes-uploads/20261018-image-2.jpg", 2, 2)
        val (body, files) = AttachmentText.parse(text)
        assertEquals("帮我填表", body)
        assertEquals(emptyList(), files)
    }
}

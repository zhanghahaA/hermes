package com.hermes.mobile.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentTextTest {
    @Test
    fun textFileRoundTripsEvenWithFencesInside() {
        val content = "第一行\n```kotlin\nval x = 1\n```\n最后"
        val message = "帮我看看\n\n" + AttachmentText.textFile("notes.md", content) + "\n\n" + AttachmentText.pdf("报告.pdf", 12, 8)
        assertTrue(message.contains("````markdown"), "内容里有 ```，围栏要更长：$message")
        val (text, files) = AttachmentText.parse(message)
        assertEquals("帮我看看", text)
        assertEquals(listOf("notes.md", "报告.pdf"), files.map { it.name })
        assertEquals(content.toByteArray().size.toLong(), files[0].sizeBytes)
        assertEquals("共 12 页", files[1].note)
    }

    @Test
    fun uploadedFileShowsFriendlyNote() {
        val (text, files) = AttachmentText.parse("看这个\n\n" + AttachmentText.uploaded("a.zip", "/home/me/hermes-uploads/20261018-a.zip"))
        assertEquals("看这个", text)
        assertEquals("已上传：/home/me/hermes-uploads/20261018-a.zip", files.single().note)
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
    fun plainTextIsUntouched() {
        val text = "普通消息\n[不是图片]"
        assertEquals(text to emptyList(), ImageMarkers.parse(text).let { it.first to it.second })
        assertEquals(text, AttachmentText.parse(text).first)
    }
}

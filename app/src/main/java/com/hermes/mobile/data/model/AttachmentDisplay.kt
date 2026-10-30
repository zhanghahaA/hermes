package com.hermes.mobile.data.model

/** 待发送的附件在自己的气泡里显示成什么：图片和文件卡片。 */
fun List<Attachment>.displayImages(): List<ImageRef> = filterIsInstance<Attachment.Image>().map { it.toImageRef() }

fun List<Attachment>.displayFiles(): List<FileRef> = mapNotNull { attachment ->
    when (attachment) {
        is Attachment.BinaryFile -> FileRef(attachment.name, attachment.sizeBytes, localPath = attachment.path)
        is Attachment.Image -> null
    }
}

/** 一条消息里图片的总数（Hermes 一次最多收 [MaxImagesPerMessage] 张）。 */
fun List<Attachment>.imageCount(): Int = count { it is Attachment.Image }

const val MaxImagesPerMessage = 10

fun Attachment.Image.toImageRef() = ImageRef(id, hue = (id.hashCode() and 0xff).toFloat(), aspect = aspect, source = path)

/** 文件大小：1.2 MB / 340 KB / 12 B。 */
fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

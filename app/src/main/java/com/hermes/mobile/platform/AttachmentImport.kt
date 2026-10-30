package com.hermes.mobile.platform

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.hermes.mobile.data.model.Attachment
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** 发过的图片缓存目录：文件名是内容的 SHA-256，读聊天记录时据此找回原图。 */
fun mediaDirectory(context: Context): File = File(context.filesDir, "media").apply { mkdirs() }

/**
 * 把用户选的图片、拍的照片、文件变成可以发送的附件。都在后台线程调用。
 * 失败时抛 [IllegalArgumentException]，消息可以直接给用户看。
 */
class AttachmentImporter(private val context: Context) {
    private val resolver = context.contentResolver

    /** 图片：按 EXIF 摆正，长边缩到 [MaxEdge]，有透明通道存 PNG，否则 JPEG。 */
    fun image(uri: Uri): Attachment.Image {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("打不开这张图片")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MaxEdge) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("打不开这张图片")
        val rotation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees() } ?: 0
        }.getOrDefault(0)
        return save(scale(rotate(decoded, rotation)))
    }

    /** 头像：从中间裁成正方形、缩到 [AvatarPx]，压成 JPEG，返回 data URL（Hermes 的头像最大 2 MB）。 */
    fun avatar(uri: Uri): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("打不开这张图片")
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= AvatarPx) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("打不开这张图片")
        val rotation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees() } ?: 0
        }.getOrDefault(0)
        val upright = rotate(decoded, rotation)
        val side = minOf(upright.width, upright.height)
        val square = Bitmap.createBitmap(upright, (upright.width - side) / 2, (upright.height - side) / 2, side, side)
        val sized = if (side > AvatarPx) Bitmap.createScaledBitmap(square, AvatarPx, AvatarPx, true) else square
        val out = ByteArrayOutputStream()
        sized.compress(Bitmap.CompressFormat.JPEG, 88, out)
        return "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    }

    /**
     * 文件：图片按照片处理（模型直接看），其它一律原样复制一份，发送时作为真实文件交给 Hermes
     * （不再把文本内容写进消息、也不再把 PDF 转成图片）。
     */
    fun file(uri: Uri): Attachment {
        val (name, size) = nameAndSize(uri)
        val mime = resolver.getType(uri) ?: ""
        return if (mime.startsWith("image/") && mime != "image/svg+xml") image(uri) else binary(uri, name, mime, size)
    }

    /** 文件：复制一份到缓存，发送时再上传。 */
    private fun binary(uri: Uri, name: String, mime: String, size: Long): Attachment.BinaryFile {
        if (size > MaxBinaryBytes) throw IllegalArgumentException("文件太大（超过 ${MaxBinaryBytes / 1024 / 1024} MB）")
        val dir = File(context.cacheDir, "outgoing").apply { mkdirs() }
        val target = File(dir, UUID.randomUUID().toString())
        resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            ?: throw IllegalArgumentException("读不到这个文件")
        return Attachment.BinaryFile(UUID.randomUUID().toString(), name, mime.ifEmpty { "application/octet-stream" }, target.path, target.length())
    }

    private fun nameAndSize(uri: Uri): Pair<String, Long> {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0)?.takeIf { it.isNotBlank() } ?: "文件"
                val size = if (c.isNull(1)) -1L else c.getLong(1)
                return name to size
            }
        }
        return (uri.lastPathSegment ?: "文件") to -1L
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap =
        if (degrees == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

    private fun scale(bitmap: Bitmap): Bitmap {
        val edge = maxOf(bitmap.width, bitmap.height)
        if (edge <= MaxEdge) return bitmap
        val f = MaxEdge.toFloat() / edge
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).toInt().coerceAtLeast(1), (bitmap.height * f).toInt().coerceAtLeast(1), true)
    }

    /**
     * 压缩后按内容的 SHA-256 存进缓存目录（和 Hermes 服务器上的目录名一致）。
     * 只有真有透明像素才存 PNG（Android 几乎把所有 PNG 都标成“有透明通道”，截图存成 PNG 动辄好几 MB，
     * 有的看图模型直接报“图片无法识别”）；太大就降质量、再缩小，控制在 [MaxImageBytes] 以内。
     */
    private fun save(original: Bitmap): Attachment.Image {
        var bitmap = original
        val png = bitmap.hasAlpha() && hasTransparentPixels(bitmap)
        var bytes = encode(bitmap, png, 85)
        var quality = 85
        while (bytes.size > MaxImageBytes) {
            if (!png && quality > 60) {
                quality -= 10
            } else {
                bitmap = Bitmap.createScaledBitmap(bitmap, (bitmap.width * 0.8f).toInt().coerceAtLeast(1), (bitmap.height * 0.8f).toInt().coerceAtLeast(1), true)
            }
            bytes = encode(bitmap, png, quality)
            if (bitmap.width < 320) break
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val file = File(mediaDirectory(context), "$sha.${if (png) "png" else "jpg"}")
        if (!file.exists()) file.writeBytes(bytes)
        return Attachment.Image(UUID.randomUUID().toString(), file.path, bitmap.width.toFloat() / bitmap.height)
    }

    /** JPEG 没有透明：透明的地方垫白色，不然会变黑。 */
    private fun encode(bitmap: Bitmap, png: Boolean, quality: Int): ByteArray = ByteArrayOutputStream().use { out ->
        if (png) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } else {
            val opaque = if (bitmap.hasAlpha()) {
                Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also { canvasBitmap ->
                    android.graphics.Canvas(canvasBitmap).apply {
                        drawColor(android.graphics.Color.WHITE)
                        drawBitmap(bitmap, 0f, 0f, null)
                    }
                }
            } else {
                bitmap
            }
            opaque.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        out.toByteArray()
    }

    /** 抽样看有没有不透明度不是 100% 的像素（整张扫太慢）。 */
    private fun hasTransparentPixels(bitmap: Bitmap): Boolean {
        val step = maxOf(1, maxOf(bitmap.width, bitmap.height) / 200)
        val row = IntArray(bitmap.width)
        var y = 0
        while (y < bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            var x = 0
            while (x < row.size) {
                if ((row[x] ushr 24) != 0xFF) return true
                x += step
            }
            y += step
        }
        return false
    }

    private fun ExifInterface.rotationDegrees(): Int = when (getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

    companion object {
        const val MaxEdge = 2048
        const val AvatarPx = 256
        /** 单张图片编码后的上限：多数看图模型限制 5 MB（base64 之后还要大三分之一）。 */
        const val MaxImageBytes = 3_500_000
        const val MaxBinaryBytes = 100L * 1024 * 1024

        /**
         * 本机 Termux 连接：把文件存到“下载/Hermes”，返回 Termux 里能读到的路径
         * （Termux 需要先运行一次 termux-setup-storage 才能读共享存储）。
         */
        fun exportForTermux(context: Context, path: String, name: String, mime: String): String? = runCatching {
            if (android.os.Build.VERSION.SDK_INT < 29) return null
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Hermes")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out -> File(path).inputStream().use { it.copyTo(out) } }
            // 重名时系统会改名，取实际保存的名字
            val saved = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: name
            "/storage/emulated/0/Download/Hermes/$saved"
        }.getOrNull()
    }
}

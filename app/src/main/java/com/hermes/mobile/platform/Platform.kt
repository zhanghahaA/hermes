package com.hermes.mobile.platform

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.launch

// 少量依赖 Android 的能力集中放在这里，界面代码只调用这些函数。
// tools/ui-preview 里有同名的桌面版实现，用来在没有 Android SDK 的环境里渲染界面截图。

val supportsDynamicColor: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun dynamicColorScheme(dark: Boolean): ColorScheme? {
    if (!supportsDynamicColor) return null
    val context = LocalContext.current
    return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}

/**
 * 系统返回（手势或返回键）。[onBack] 收到的 Flow 是手势进度 0..1：
 * 支持预测性返回的系统会边拖边发进度；不支持的系统在松手时直接结束 Flow。
 * Flow 被取消表示用户放弃了返回。
 */
@Composable
fun SystemBackHandler(enabled: Boolean, onBack: suspend (progress: Flow<Float>) -> Unit) {
    PredictiveBackHandler(enabled) { events -> onBack(events.map { it.progress }) }
}

/**
 * 选一个文本文件（例如 SSH 私钥）并读出内容。返回的函数调用后弹出系统文件选择器。
 * 文件太大（超过 64 KB）或读不出来时 [onText] 收到 null。
 */
@Composable
fun rememberTextFilePicker(onText: (String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readNBytesCompat(64 * 1024 + 1)
                if (bytes.size > 64 * 1024) null else String(bytes, Charsets.UTF_8)
            }
        }.getOrNull()
        onText(text)
    }
    return { launcher.launch(arrayOf("*/*")) }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** “+”面板里三个入口对应的动作。 */
class AttachmentPickers(val pickPhotos: () -> Unit, val takePhoto: () -> Unit, val pickFiles: () -> Unit)

/**
 * 相册（系统照片选择器，最多 10 张）、拍照、选文件。选好的东西在后台处理成附件后交给 [onAdd]，
 * 出错时把能看懂的原因交给 [onError]。
 */
@Composable
fun rememberAttachmentPickers(onAdd: (List<com.hermes.mobile.data.model.Attachment>) -> Unit, onError: (String) -> Unit): AttachmentPickers {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val importer = androidx.compose.runtime.remember(context) { AttachmentImporter(context.applicationContext) }
    val currentAdd = androidx.compose.runtime.rememberUpdatedState(onAdd)
    val currentError = androidx.compose.runtime.rememberUpdatedState(onError)

    fun process(items: List<android.net.Uri>, convert: (android.net.Uri) -> com.hermes.mobile.data.model.Attachment) {
        if (items.isEmpty()) return
        scope.launch {
            val results = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                items.map { uri -> runCatching { convert(uri) } }
            }
            results.mapNotNull { it.exceptionOrNull() }.firstOrNull()?.let { currentError.value(it.message ?: "处理失败") }
            results.mapNotNull { it.getOrNull() }.takeIf { it.isNotEmpty() }?.let { currentAdd.value(it) }
        }
    }

    val photos = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(com.hermes.mobile.data.model.MaxImagesPerMessage),
    ) { uris -> process(uris, importer::image) }

    var pendingPhoto by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val camera = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture(),
    ) { ok ->
        val path = pendingPhoto
        pendingPhoto = null
        if (ok && path != null) {
            process(listOf(android.net.Uri.fromFile(java.io.File(path)))) { uri ->
                importer.image(uri).also { java.io.File(path).delete() }
            }
        }
    }

    val files = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> process(uris, importer::file) }

    return androidx.compose.runtime.remember {
        AttachmentPickers(
            pickPhotos = {
                photos.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                    ),
                )
            },
            takePhoto = {
                val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
                val file = java.io.File(dir, "photo-${System.currentTimeMillis()}.jpg")
                pendingPhoto = file.path
                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                try {
                    camera.launch(uri)
                } catch (e: android.content.ActivityNotFoundException) {
                    currentError.value("没有找到相机应用")
                }
            },
            pickFiles = { files.launch(arrayOf("*/*")) },
        )
    }
}

private val bitmapCache = object : android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(48 * 1024 * 1024) {
    override fun sizeOf(key: String, value: androidx.compose.ui.graphics.ImageBitmap) = value.width * value.height * 4
}

/**
 * 读图片并缩到长边不超过 [maxPx]：本机文件路径或 `data:` URL。读不到返回 null（界面显示占位块）。
 */
@Composable
fun rememberImageBitmap(source: String?, maxPx: Int): androidx.compose.ui.graphics.ImageBitmap? {
    val key = source?.let { "$maxPx@$it" }
    val state = androidx.compose.runtime.produceState(initialValue = key?.let { bitmapCache.get(it) }, key) {
        if (key == null || source == null || value != null) return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { decodeImage(source, maxPx) }.getOrNull()
        }?.also { bitmapCache.put(key, it) }
    }
    return state.value
}

private fun decodeImage(source: String, maxPx: Int): androidx.compose.ui.graphics.ImageBitmap? {
    val bytes = when {
        source.startsWith("data:") -> java.util.Base64.getDecoder().decode(source.substringAfter(','))
        source.startsWith("/") -> java.io.File(source).takeIf { it.isFile }?.readBytes()
        else -> null
    } ?: return null
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    return bitmap?.asImageBitmap()
}

/** 消息里的文件：用别的 App 打开、分享、存到“下载/Hermes”。传入的都是本机文件路径。 */
class FileActions(
    val open: (path: String, name: String) -> Unit,
    val share: (path: String, name: String) -> Unit,
    val save: (path: String, name: String) -> String?,
    /** 图片存进相册（Pictures/Hermes）。[source] 是本机文件路径或 data: URL。返回给用户的提示，失败为 null。 */
    val saveImage: (source: String, name: String) -> String?,
)

@Composable
fun rememberFileActions(onError: (String) -> Unit): FileActions {
    val context = LocalContext.current
    val currentError = androidx.compose.runtime.rememberUpdatedState(onError)
    return androidx.compose.runtime.remember(context) {
        /** 按原文件名复制一份到缓存，再给出 content:// 地址（别的 App 看到的是正确的文件名和类型）。 */
        fun contentUri(path: String, name: String): Pair<android.net.Uri, String> {
            val dir = java.io.File(context.cacheDir, "share/" + java.util.UUID.randomUUID()).apply { mkdirs() }
            val copy = java.io.File(dir, name.replace('/', '_').ifBlank { "file" })
            java.io.File(path).copyTo(copy, overwrite = true)
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", copy)
            return uri to mimeOf(name)
        }
        FileActions(
            open = { path, name ->
                try {
                    val (uri, mime) = contentUri(path, name)
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                        .setDataAndType(uri, mime)
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(android.content.Intent.createChooser(intent, "打开 $name").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    currentError.value("没有能打开这个文件的应用")
                }
            },
            share = { path, name ->
                runCatching {
                    val (uri, mime) = contentUri(path, name)
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType(mime)
                        .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(android.content.Intent.createChooser(intent, "分享 $name").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { currentError.value("分享失败：${it.message}") }
            },
            save = { path, name -> AttachmentImporter.exportForTermux(context, path, name, mimeOf(name))?.let { "已保存到 下载/Hermes/${it.substringAfterLast('/')}" } },
            saveImage = { source, name -> saveImageToGallery(context, source, name) },
        )
    }
}

private fun mimeOf(name: String): String =
    android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        ?: if (name.substringAfterLast('.', "").lowercase() in setOf("md", "log", "kt", "py", "sh", "yaml", "yml", "toml", "ini", "conf")) "text/plain"
        else "application/octet-stream"

/** 字节 → 相册（Pictures/Hermes），在相册 App 里能直接看到。 */
private fun saveImageToGallery(context: android.content.Context, source: String, name: String): String? = runCatching {
    val bytes = if (source.startsWith("data:")) java.util.Base64.getDecoder().decode(source.substringAfter(','))
    else java.io.File(source).readBytes()
    val mime = mimeOf(name).takeIf { it.startsWith("image/") } ?: sniffImageMime(bytes)
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
        put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Hermes")
        }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching null
    resolver.openOutputStream(uri)?.use { it.write(bytes) }
    "已保存到相册（Pictures/Hermes）"
}.getOrNull()

private fun sniffImageMime(bytes: ByteArray): String = when {
    bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
    bytes.size > 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "image/gif"
    bytes.size > 11 && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() -> "image/webp"
    else -> "image/jpeg"
}

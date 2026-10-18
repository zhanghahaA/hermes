package com.hermes.mobile.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow
import androidx.compose.ui.graphics.toComposeImageBitmap

// app/src/main/java/com/hermes/mobile/platform/Platform.kt 的桌面替身

val supportsDynamicColor: Boolean get() = true

@Suppress("UNUSED_PARAMETER")
@Composable
fun dynamicColorScheme(dark: Boolean): ColorScheme? = null

@Suppress("UNUSED_PARAMETER")
@Composable
fun SystemBackHandler(enabled: Boolean, onBack: suspend (progress: Flow<Float>) -> Unit) = Unit

@Suppress("UNUSED_PARAMETER")
@Composable
fun rememberTextFilePicker(onText: (String?) -> Unit): () -> Unit = {}

class AttachmentPickers(val pickPhotos: () -> Unit, val takePhoto: () -> Unit, val pickFiles: () -> Unit)

@Suppress("UNUSED_PARAMETER")
@Composable
fun rememberAttachmentPickers(onAdd: (List<com.hermes.mobile.data.model.Attachment>) -> Unit, onError: (String) -> Unit): AttachmentPickers =
    AttachmentPickers({}, {}, {})

/** 桌面版：用 Skia 解码本机文件或 data: URL。 */
@Composable
fun rememberImageBitmap(source: String?, maxPx: Int): androidx.compose.ui.graphics.ImageBitmap? =
    androidx.compose.runtime.remember(source, maxPx) {
        runCatching {
            val bytes = when {
                source == null -> null
                source.startsWith("data:") -> java.util.Base64.getDecoder().decode(source.substringAfter(','))
                source.startsWith("/") -> java.io.File(source).takeIf { it.isFile }?.readBytes()
                else -> null
            } ?: return@runCatching null
            org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
        }.getOrNull()
    }

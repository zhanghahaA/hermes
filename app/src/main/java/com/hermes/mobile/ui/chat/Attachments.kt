package com.hermes.mobile.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hermes.mobile.data.model.Attachment
import com.hermes.mobile.data.model.FileRef
import com.hermes.mobile.data.model.ImageRef
import com.hermes.mobile.data.model.formatSize
import com.hermes.mobile.data.model.toImageRef
import com.hermes.mobile.data.remote.RemoteMediaPrefix
import com.hermes.mobile.platform.rememberImageBitmap

/** 下载服务器上的文件（Hermes 生成的图片等），返回本机路径。由 App 根部提供。 */
val LocalMediaFetcher = staticCompositionLocalOf<suspend (String) -> String?> { { null } }

/** 服务器上的图（`hermes-media:<路径>`）先下载到本机；其它来源原样返回。下载中、拿不到时返回 null。 */
@Composable
fun rememberLocalSource(source: String?): String? {
    if (source == null || !source.startsWith(RemoteMediaPrefix)) return source
    val fetch = LocalMediaFetcher.current
    val state = produceState<String?>(null, source) {
        value = runCatching { fetch(source.removePrefix(RemoteMediaPrefix)) }.getOrNull()
    }
    return state.value
}

/** 点消息里的图片时打开大图。由聊天页提供。 */
val LocalOpenImage = staticCompositionLocalOf<(ImageRef) -> Unit> { {} }

/** 一张图：读得到就显示真图，读不到（服务器上的图、本机没缓存）显示渐变占位块。 */
@Composable
fun ImageTile(image: ImageRef, modifier: Modifier = Modifier, shape: RoundedCornerShape = RoundedCornerShape(16.dp), maxPx: Int = 720) {
    val bitmap = rememberImageBitmap(rememberLocalSource(image.source), maxPx)
    Box(modifier.clip(shape), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = "图片", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(Color.hsv(image.hue % 360f, 0.35f, 0.92f), Color.hsv((image.hue + 50f) % 360f, 0.55f, 0.70f)),
                        ),
                    ),
            )
            Icon(Icons.Rounded.Image, contentDescription = "图片", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(32.dp))
        }
    }
}

/** 消息里的图片：一张时按原比例显示，多张时两列方格。点开看大图。 */
@Composable
fun ImageGrid(images: List<ImageRef>, modifier: Modifier = Modifier) {
    val open = LocalOpenImage.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (images.size == 1) {
            val image = images.single()
            val bitmap = rememberImageBitmap(rememberLocalSource(image.source), 720)
            val aspect = (bitmap?.let { it.width.toFloat() / it.height } ?: image.aspect).coerceIn(0.5f, 2.2f)
            ImageTile(image, Modifier.width(240.dp).aspectRatio(aspect).clickable { open(image) })
        } else {
            images.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { image ->
                        ImageTile(image, Modifier.width(118.dp).aspectRatio(1f).clickable { open(image) })
                    }
                }
            }
        }
    }
}

/** 消息里的文件卡片。 */
@Composable
fun FileChips(files: List<FileRef>, tint: Color, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        files.forEach { file ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(tint.copy(alpha = 0.10f))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .widthIn(max = 260.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(fileIcon(file.name), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(file.name, style = MaterialTheme.typography.bodyMedium, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val detail = listOfNotNull(file.sizeBytes?.takeIf { it >= 0 }?.let(::formatSize), file.note).joinToString(" · ")
                    if (detail.isNotEmpty()) {
                        Text(detail, style = MaterialTheme.typography.labelSmall, color = tint.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun fileIcon(name: String): ImageVector = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> Icons.Rounded.PictureAsPdf
    "txt", "md", "csv", "json", "log", "yaml", "yml", "xml", "html", "py", "kt", "js", "ts", "sh" -> Icons.Rounded.Description
    else -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

/** 输入框上方：待发送的附件，每个右上角可以移除。 */
@Composable
fun AttachmentRow(attachments: List<Attachment>, onRemove: (Attachment) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp),
    ) {
        items(attachments, key = { it.id }) { attachment ->
            Box {
                when (attachment) {
                    is Attachment.Image -> ImageTile(attachment.toImageRef(), Modifier.size(64.dp), maxPx = 256)
                    is Attachment.Pdf -> Box(Modifier.size(64.dp)) {
                        attachment.pages.firstOrNull()?.let { ImageTile(it.toImageRef(), Modifier.fillMaxSize(), maxPx = 256) }
                        Text(
                            "PDF · ${attachment.pages.size}页",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                                .background(Color.Black.copy(alpha = 0.55f))
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                    is Attachment.TextFile -> PendingFile(attachment.name, attachment.sizeBytes)
                    is Attachment.BinaryFile -> PendingFile(attachment.name, attachment.sizeBytes)
                }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.75f)),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(onClick = { onRemove(attachment) }, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "移除", tint = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingFile(name: String, size: Long, height: Dp = 64.dp) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(16.dp)) {
        Row(
            Modifier
                .padding(start = 10.dp, end = 26.dp)
                .widthIn(max = 180.dp)
                .size(width = Dp.Unspecified, height = height),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(fileIcon(name), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (size >= 0) Text(formatSize(size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 全屏看图：双指缩放、拖动，双击放大/还原，单击关闭。 */
@Composable
fun ImageViewer(image: ImageRef, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val bitmap = rememberImageBitmap(rememberLocalSource(image.source), 4096)
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onDismiss() },
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap,
                    contentDescription = "图片",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            } else {
                Text(
                    if (image.source?.startsWith(RemoteMediaPrefix) == true) {
                        "这张图在 Hermes 那台机器上。\n给连接配上 SSH（本机 Termux 也可以用 Termux 自己的 SSH）后就能显示。"
                    } else {
                        "这张图没有留在这台手机上"
                    },
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = Color.White)
            }
        }
    }
}

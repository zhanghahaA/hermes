package com.hermes.mobile.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.dp
import com.hermes.mobile.ui.glass.GlassSurface
import com.kyant.backdrop.Backdrop

/**
 * 选中文字后浮在输入框上方的玻璃操作栏。
 * 「追问」只对 Hermes 的回复出现：把选中的片段作为引用放进输入框，直接在本会话里接着问。
 */
@Composable
fun SelectionBar(
    backdrop: Backdrop,
    selection: MessageSelection?,
    onAsk: (MessageSelection) -> Unit,
    onCopy: (MessageSelection) -> Unit,
    onMore: (MessageSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 收起动画期间 selection 已经是 null，继续显示上一次的按钮
    val last = remember { arrayOfNulls<MessageSelection>(1) }
    if (selection != null) last[0] = selection
    AnimatedVisibility(
        visible = selection != null,
        modifier = modifier,
        enter = slideInVertically(spring(dampingRatio = 0.75f, stiffness = 500f)) { it / 2 } + scaleIn(initialScale = 0.9f) + fadeIn(),
        exit = slideOutVertically { it / 2 } + scaleOut(targetScale = 0.9f) + fadeOut(),
    ) {
        val sel = last[0] ?: return@AnimatedVisibility
        GlassSurface(backdrop = backdrop, shape = CircleShape, modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (sel.message.quotable) {
                    Button(onClick = { onAsk(sel) }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                        Icon(Icons.AutoMirrored.Rounded.Reply, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("追问")
                    }
                }
                IconButton(onClick = { onCopy(sel) }) { Icon(Icons.Rounded.ContentCopy, contentDescription = "复制") }
                IconButton(onClick = { sel.selectAll() }) { Icon(Icons.Rounded.SelectAll, contentDescription = "全选") }
                IconButton(onClick = { onMore(sel) }) { Icon(Icons.Rounded.MoreHoriz, contentDescription = "更多") }
                IconButton(onClick = { sel.clear() }) { Icon(Icons.Rounded.Close, contentDescription = "取消选择") }
            }
        }
    }
}

/** 不显示任何东西的系统文字工具条；选中操作统一走 [SelectionBar]。 */
object NoTextToolbar : TextToolbar {
    override val status: TextToolbarStatus get() = TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) = Unit

    override fun hide() = Unit
}

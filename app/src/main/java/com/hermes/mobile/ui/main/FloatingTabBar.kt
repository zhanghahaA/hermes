package com.hermes.mobile.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.mobile.ui.glass.GlassCircleButton
import com.hermes.mobile.ui.glass.liquidGlass
import com.hermes.mobile.ui.glass.pressScale
import com.hermes.mobile.ui.glass.rememberGlassStyle
import com.kyant.backdrop.Backdrop

/** 悬浮栏本身的高度和离屏幕底边的距离；内容区需要据此留出底部空间。 */
val FloatingBarHeight = 64.dp
val FloatingBarMargin = 12.dp

/** 悬浮栏右侧的圆形操作按钮（按当前 Tab 变化，比如“新对话”）。 */
data class FloatingAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/**
 * 悬浮的液态玻璃底栏：左边是 Tab 胶囊，右边是当前页面的主要操作。
 * 选中指示器在切换时会像液体一样被拉长再回弹。
 */
@Composable
fun FloatingTabBar(
    backdrop: Backdrop,
    tabs: List<MainTab>,
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    badges: Map<MainTab, Int>,
    action: FloatingAction?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = FloatingBarMargin),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TabPill(backdrop, tabs, selected, onSelect, badges, Modifier.weight(1f))
        AnimatedVisibility(
            visible = action != null,
            enter = scaleIn(spring(dampingRatio = 0.6f)) + fadeIn(),
            exit = scaleOut() + fadeOut(),
        ) {
            // 退出动画期间 action 已经是 null，继续显示上一次的图标
            val shown = remember { arrayOfNulls<FloatingAction>(1) }
            if (action != null) shown[0] = action
            AnimatedContent(
                targetState = shown[0],
                transitionSpec = { (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.6f) + fadeOut()) },
                contentKey = { it?.label },
                label = "floating-action",
            ) { current ->
                if (current != null) {
                    GlassCircleButton(
                        backdrop = backdrop,
                        icon = current.icon,
                        contentDescription = current.label,
                        onClick = current.onClick,
                        size = FloatingBarHeight,
                    )
                } else {
                    Spacer(Modifier.size(FloatingBarHeight))
                }
            }
        }
    }
}

@Composable
private fun TabPill(
    backdrop: Backdrop,
    tabs: List<MainTab>,
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    badges: Map<MainTab, Int>,
    modifier: Modifier,
) {
    val style = rememberGlassStyle()
    val interaction = remember { MutableInteractionSource() }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val index = tabs.indexOf(selected)

    // 记录移动方向：前沿用硬弹簧冲过去，后沿用软弹簧跟上，形成“拉长再回弹”
    val previous = remember { intArrayOf(index) }
    val movingRight = remember(index) { (index >= previous[0]).also { previous[0] = index } }
    val fast = spring<androidx.compose.ui.unit.Dp>(dampingRatio = 0.72f, stiffness = 900f)
    val slow = spring<androidx.compose.ui.unit.Dp>(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)

    BoxWithConstraints(
        modifier
            .height(FloatingBarHeight)
            .liquidGlass(backdrop, CircleShape, style, scale = pressScale(interaction, pressed = 0.97f)),
    ) {
        val inset = 5.dp
        val itemWidth = (maxWidth - inset * 2) / tabs.size
        val left by animateDpAsState(inset + itemWidth * index, if (movingRight) slow else fast, label = "indicator-left")
        val right by animateDpAsState(inset + itemWidth * (index + 1), if (movingRight) fast else slow, label = "indicator-right")

        Box(
            Modifier
                .offset(x = left, y = inset)
                .width(right - left)
                .height(FloatingBarHeight - inset * 2)
                .background(
                    if (dark) Color.White.copy(alpha = 0.14f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    CircleShape,
                ),
        )
        Row(Modifier.fillMaxHeight().padding(horizontal = inset)) {
            tabs.forEach { tab ->
                TabItem(
                    tab = tab,
                    selected = tab == selected,
                    badge = badges[tab] ?: 0,
                    onClick = { onSelect(tab) },
                    interaction = interaction,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: MainTab,
    selected: Boolean,
    badge: Int,
    onClick: () -> Unit,
    interaction: MutableInteractionSource,
    modifier: Modifier,
) {
    val color by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
        label = "tab-color",
    )
    Column(
        modifier
            .fillMaxHeight()
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BadgedBox(badge = { if (badge > 0) Badge { Text("$badge") } }) {
            Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        }
        Text(tab.label, color = color, fontSize = 11.sp, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
    }
}

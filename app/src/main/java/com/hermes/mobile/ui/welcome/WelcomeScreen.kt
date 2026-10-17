package com.hermes.mobile.ui.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.mobile.data.model.ConnectionType
import com.hermes.mobile.ui.components.IconBadge
import com.hermes.mobile.ui.components.icon

private data class Option(val type: ConnectionType, val title: String, val subtitle: String)

private val options = listOf(
    Option(ConnectionType.Direct, "家里的电脑 / NAS", "通过 Tailscale、ZeroTier 或局域网直连，推荐"),
    Option(ConnectionType.Ssh, "通过 SSH 连接", "服务器只开放了 SSH 端口时使用"),
    Option(ConnectionType.Https, "HTTPS 网址", "已经用反向代理把 Hermes 发布出去"),
    Option(ConnectionType.Local, "这台手机（Termux）", "Hermes 就跑在手机上"),
)

/** 首次启动：选择 Hermes 在哪里。 */
@Composable
fun WelcomeScreen(onPickType: (ConnectionType) -> Unit, onSkip: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.primaryContainer.copy(alpha = 0.55f), colors.surface, colors.surface)))
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(56.dp))
        Box(
            Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(colors.primary, colors.tertiary))),
            contentAlignment = Alignment.Center,
        ) {
            Text("H", color = colors.onPrimary, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
        Text("连接你的 Hermes", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "在手机上像发消息一样使用 Hermes。先告诉我它在哪里运行。",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            options.forEach { option ->
                OutlinedCard(onClick = { onPickType(option.type) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(option.type.icon, size = 44.dp, container = colors.primaryContainer, content = colors.onPrimaryContainer)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(option.title, style = MaterialTheme.typography.titleMedium)
                            Text(option.subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = colors.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("稍后设置") }
        Spacer(Modifier.height(16.dp))
        Text(
            "需要在 Hermes 那边开启 API Server（API_SERVER_ENABLED=true）并设置 API Key。",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        )
    }
}

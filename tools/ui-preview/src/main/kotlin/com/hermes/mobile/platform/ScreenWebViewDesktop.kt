package com.hermes.mobile.platform

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.hermes.mobile.data.screen.ScreenPageHost

/** 桌面预览没有 WebView：画一个示意的桌面（截图用），并假装页面已经连上。 */
@Composable
fun ScreenWebView(host: ScreenPageHost, modifier: Modifier = Modifier) {
    androidx.compose.runtime.DisposableEffect(host) {
        host.evaluate = { js -> if (js.startsWith("hm.start")) host.onEvent("connect", "") }
        host.onEvent("ready", "")
        onDispose { host.evaluate = null }
    }
    Canvas(modifier) {
        drawRect(Color(0xFF1B1B1F))
        val w = size.width
        val h = w * 900f / 1440f
        val top = (size.height - h) / 2f
        drawRect(Brush.linearGradient(listOf(Color(0xFF203A43), Color(0xFF2C5364)), Offset(0f, top), Offset(w, top + h)), Offset(0f, top), Size(w, h))
        drawRect(Color(0xFF2B2B2B), Offset(0f, top), Size(w, h * 0.035f))
        drawRect(Color(0xFFF5F5F5), Offset(w * 0.12f, top + h * 0.12f), Size(w * 0.62f, h * 0.62f))
        drawRect(Color(0xFF3D6EB4), Offset(w * 0.12f, top + h * 0.12f), Size(w * 0.62f, h * 0.05f))
        drawRect(Color(0xFFDDDDDD), Offset(w * 0.2f, top + h * 0.3f), Size(w * 0.46f, h * 0.06f))
        drawRect(Color(0xFFDDDDDD), Offset(w * 0.2f, top + h * 0.4f), Size(w * 0.46f, h * 0.06f))
        drawRect(Color(0xFF3D6EB4), Offset(w * 0.2f, top + h * 0.52f), Size(w * 0.16f, h * 0.06f))
    }
}

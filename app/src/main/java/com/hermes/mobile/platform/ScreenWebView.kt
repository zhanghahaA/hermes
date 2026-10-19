package com.hermes.mobile.platform

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.hermes.mobile.data.screen.ScreenPageHost

/**
 * Bot 屏幕的查看器：WebView 里跑打包进 App 的 noVNC（assets/screen/screen.html）。
 * 页面不联网：画面字节经 [ScreenPageHost] 和 App 互传，App 再经 SSH 接到 Hermes。
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun ScreenWebView(host: ScreenPageHost, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val html = remember { context.assets.open("screen/screen.html").bufferedReader().use { it.readText() } }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(0xFF1B1B1F.toInt())
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.domStorageEnabled = false
                settings.setSupportZoom(false)
                // 只显示自己的页面，不跳转到别处
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                }
                val main = Handler(Looper.getMainLooper())
                addJavascriptInterface(
                    object {
                        // 在 WebView 的后台线程上调：直接写 SSH 通道
                        @JavascriptInterface fun send(b64: String) = host.onSend(b64)
                        @JavascriptInterface fun close() {
                            main.post { host.onClose() }
                        }
                        @JavascriptInterface fun event(type: String, data: String) {
                            main.post { host.onEvent(type, data) }
                        }
                    },
                    "HermesScreen",
                )
                host.evaluate = { js -> main.post { evaluateJavascript(js, null) } }
                // 内联的 ES 模块需要一个正常的来源（file:// 不行），用一个不存在的 https 地址当基址
                loadDataWithBaseURL("https://hermes-screen.invalid/", html, "text/html", "utf-8", null)
            }
        },
        onRelease = { view ->
            host.evaluate = null
            view.removeJavascriptInterface("HermesScreen")
            view.destroy()
        },
    )
}

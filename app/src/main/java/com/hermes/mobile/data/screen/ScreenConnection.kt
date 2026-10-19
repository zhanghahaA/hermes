package com.hermes.mobile.data.screen

import com.hermes.mobile.data.ssh.RemoteStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Base64

/**
 * 查看器页面（WebView 里的 noVNC）和 App 之间的接口。平台那边负责 [evaluate]（在页面里执行 JS，切到主线程），
 * 并把页面调过来的 `HermesScreen.send/close/event` 转给 [onSend] / [onClose] / [onEvent]。
 */
class ScreenPageHost {
    @Volatile var evaluate: ((String) -> Unit)? = null
    @Volatile var onSend: (String) -> Unit = {}
    @Volatile var onClose: () -> Unit = {}
    @Volatile var onEvent: (type: String, data: String) -> Unit = { _, _ -> }

    fun call(js: String) {
        evaluate?.invoke(js)
    }
}

/**
 * 一次看屏幕的连接：把 SSH 通道里的 RFB 字节转给页面（base64），把页面发的字节写回通道。
 * 画面数据不经过任何网络端口，只走已经建好的 SSH。
 */
class ScreenConnection(
    private val stream: RemoteStream,
    private val host: ScreenPageHost,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Connecting : State
        data object Live : State
        /** 断开了；[reason] 给人看。 */
        data class Closed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Connecting)
    val state: StateFlow<State> = _state.asStateFlow()

    private var pump: Job? = null
    @Volatile private var closedByUs = false
    private val writeLock = Any()

    /** 页面已经 ready：开始转发，并让页面建 RFB 客户端。 */
    fun start(fit: Boolean, viewOnly: Boolean) {
        host.onSend = ::fromPage
        host.onClose = { close() }
        host.onEvent = { type, data ->
            when (type) {
                "connect" -> _state.value = State.Live
                "disconnect" -> if (_state.value !is State.Closed) _state.value = State.Closed(if (data == "clean") "已断开" else "画面断了")
                "error" -> _state.value = State.Closed("连不上屏幕：$data")
            }
            extraEvents(type, data)
        }
        host.call("hm.start({fit:$fit,viewOnly:$viewOnly})")
        pump = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(ChunkBytes)
            val reason = try {
                while (true) {
                    val n = stream.output.read(buf)
                    if (n < 0) break
                    if (n > 0) host.call("hm.recv('" + Base64.getEncoder().encodeToString(buf.copyOf(n)) + "')")
                }
                if (closedByUs) "已断开" else explain(stream)
            } catch (e: IOException) {
                if (closedByUs) "已断开" else "画面断了：${e.message}"
            }
            host.call("hm.closed(" + jsString(reason) + ")")
            if (_state.value !is State.Closed) _state.value = State.Closed(reason)
        }
    }

    /** 页面上的其它事件（屏幕名字、修饰键松开……），给界面用。 */
    var extraEvents: (String, String) -> Unit = { _, _ -> }

    private fun fromPage(b64: String) {
        val bytes = try {
            Base64.getDecoder().decode(b64)
        } catch (e: IllegalArgumentException) {
            return
        }
        try {
            synchronized(writeLock) {
                stream.input.write(bytes)
                stream.input.flush()
            }
        } catch (e: IOException) {
            if (!closedByUs) _state.value = State.Closed("画面断了：${e.message}")
        }
    }

    fun close() {
        if (closedByUs) return
        closedByUs = true
        host.call("hm.stop()")
        runCatching { stream.close() }
        pump?.cancel()
        if (_state.value !is State.Closed) _state.value = State.Closed("已断开")
    }

    companion object {
        /** 一次最多转这么多给页面（base64 后约 340 KB 的 JS 字符串）。 */
        const val ChunkBytes = 256 * 1024

        /** 通道自己结束了：看桥接脚本说了什么。 */
        internal fun explain(stream: RemoteStream): String {
            val err = stream.errorText()
            return when {
                "SCREEN_DOWN" in err || stream.exitCode == 3 -> "Bot 屏幕没开"
                "ModuleNotFoundError" in err || "No module named" in err -> "找不到 Hermes 的 Bot 屏幕代码（Hermes 版本太旧？）"
                err.isNotBlank() -> "画面断了：" + err.lines().last { it.isNotBlank() }.take(200)
                else -> "画面断了"
            }
        }

        fun jsString(text: String): String = buildString {
            append('"')
            for (c in text) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c == '\n' -> append("\\n")
                    c < ' ' || c == ' ' || c == ' ' || c == '<' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }
    }
}

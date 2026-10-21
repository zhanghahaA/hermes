package com.hermes.mobile.data

import java.net.Proxy
import java.util.concurrent.atomic.AtomicLong

/** 有界、仅内存的连接诊断。只接受阶段、数字和枚举，不接受 URL、正文或凭据文字。 */
class ConnectionDiagnostics(private val enabled: Boolean = true, private val limit: Int = 4) {
    enum class Use { Test, Connection }
    private val traces = ArrayDeque<ConnectionTrace>()

    @Synchronized fun begin(use: Use): ConnectionTrace = ConnectionTrace(use, enabled).also {
        traces.addLast(it)
        while (traces.size > limit.coerceAtLeast(1)) traces.removeFirst()
    }

    @Synchronized fun report(): String = buildString {
        appendLine("SSH / HTTP 连接诊断（仅本次启动）")
        if (!enabled) appendLine("诊断已关闭")
        else if (traces.isEmpty()) appendLine("（没有 SSH 连接记录）")
        else traces.forEach { appendLine(it.report()) }
    }
}

class ConnectionTrace internal constructor(
    private val use: ConnectionDiagnostics.Use,
    private val enabled: Boolean,
    private val limit: Int = 180,
) {
    enum class Event {
        ListenerBound, SshConnectStart, SshAuthenticated, SshFailed, SessionReset,
        ProbeStart, ProbePassed, ProbeFailed,
        LocalAccepted, ChannelOpening, ChannelOpened, ChannelFailed,
        UpFirstBytes, UpFinished, DownFirstBytes, DownFinished,
        UpHalfClosed, DownHalfClosed, HalfCloseFailed, LocalClosed, TunnelClosed,
        HealthStart, HealthConnectStart, HealthConnectFailed, HealthConnected,
        HealthHeadersStart, HealthHeadersSent, HealthResponseStart, HealthResponse,
        HealthBodyFinished, HealthCallFinished, HealthFailed, HealthParsed, TestCancelled,
    }
    enum class End { Eof, Error, Closed }
    enum class ForwardFailure { Denied, RemoteConnect, SessionLost, Other }

    private data class Entry(
        val elapsedMs: Long,
        val event: Event,
        val connection: Long?,
        val bytes: Long?,
        val localPort: Int?,
        val remotePort: Int?,
        val status: Int?,
        val exception: String?,
        val end: End?,
        val proxy: Proxy.Type?,
        val failure: ForwardFailure?,
    )

    val id: String = "ssh-${serial.incrementAndGet().toString(36)}"
    private val startedNanos = System.nanoTime()
    private val entries = ArrayDeque<Entry>()
    @Volatile var httpPhase: Event = Event.HealthStart
        private set
    @Volatile var forwardFailure: ForwardFailure? = null
        private set

    @Synchronized fun record(
        event: Event,
        connection: Long? = null,
        bytes: Long? = null,
        localPort: Int? = null,
        remotePort: Int? = null,
        status: Int? = null,
        error: Throwable? = null,
        end: End? = null,
        proxy: Proxy.Type? = null,
        failure: ForwardFailure? = null,
    ) {
        if (event in healthPhases) httpPhase = event
        if (failure != null) forwardFailure = failure
        if (event == Event.ChannelOpened) forwardFailure = null
        if (!enabled) return
        entries.addLast(Entry(
            (System.nanoTime() - startedNanos) / 1_000_000, event, connection, bytes,
            localPort, remotePort, status,
            error?.javaClass?.simpleName?.filter { it.isLetterOrDigit() || it == '_' }?.take(80),
            end, proxy, failure,
        ))
        while (entries.size > limit.coerceAtLeast(1)) entries.removeFirst()
    }

    @Synchronized fun report(): String = buildString {
        appendLine("[$id] ${if (use == ConnectionDiagnostics.Use.Test) "连接测试" else "正式连接"}")
        entries.forEach { entry ->
            append("+${entry.elapsedMs}ms ${entry.event}")
            entry.connection?.let { append(" conn=$it") }
            entry.localPort?.let { append(" local=127.0.0.1:$it IPv4") }
            entry.remotePort?.let { append(" remote=127.0.0.1:$it") }
            if (entry.event == Event.ProbeStart) append(" TCP-only（不发送 HTTP）")
            if (entry.event == Event.HealthStart) append(" GET /health")
            entry.bytes?.let { append(" bytes=$it") }
            entry.status?.let { append(" HTTP=$it") }
            entry.exception?.let { append(" exception=$it") }
            entry.end?.let { append(" end=$it") }
            entry.proxy?.let { append(" proxy=$it") }
            entry.failure?.let { append(" failure=$it") }
            appendLine()
        }
    }

    companion object {
        private val serial = AtomicLong()
        private val healthPhases = setOf(
            Event.HealthStart, Event.HealthConnectStart, Event.HealthConnected,
            Event.HealthHeadersStart, Event.HealthHeadersSent, Event.HealthResponseStart,
            Event.HealthResponse, Event.HealthBodyFinished, Event.HealthParsed,
        )
    }
}

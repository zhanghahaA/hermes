package com.hermes.mobile.data.ssh

import com.hermes.mobile.data.ConnectionDiagnostics
import com.hermes.mobile.data.ConnectionTrace
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionDiagnosticsTest {
    @Test fun retainsOnlyBoundedAnonymousStagesAndNeverExceptionMessages() {
        val diagnostics = ConnectionDiagnostics(limit = 2)
        val first = diagnostics.begin(ConnectionDiagnostics.Use.Test)
        diagnostics.begin(ConnectionDiagnostics.Use.Connection)
        val last = diagnostics.begin(ConnectionDiagnostics.Use.Test)
        repeat(220) { last.record(ConnectionTrace.Event.UpFinished, bytes = it.toLong()) }
        last.record(ConnectionTrace.Event.HealthFailed, error = IOException("private-host.invalid secret-password Authorization: private-key"))
        val report = diagnostics.report()
        assertFalse(report.contains("[${first.id}]"))
        assertEquals(180, last.report().lineSequence().count { it.startsWith("+") })
        assertTrue(report.contains("IOException"))
        for (privateText in listOf("private-host", "secret-password", "Authorization", "private-key")) assertFalse(report.contains(privateText))
    }

    @Test fun disablingDiagnosticsKeepsErrorClassificationWithoutRetainingEvents() {
        val diagnostics = ConnectionDiagnostics(enabled = false)
        val trace = diagnostics.begin(ConnectionDiagnostics.Use.Test)
        trace.record(ConnectionTrace.Event.HealthHeadersSent)
        trace.record(ConnectionTrace.Event.ChannelFailed, failure = ConnectionTrace.ForwardFailure.Denied)
        assertEquals(ConnectionTrace.Event.HealthHeadersSent, trace.httpPhase)
        assertEquals(ConnectionTrace.ForwardFailure.Denied, trace.forwardFailure)
        assertFalse(trace.report().contains("HealthHeadersSent"))
        assertTrue(diagnostics.report().contains("诊断已关闭"))
    }
}

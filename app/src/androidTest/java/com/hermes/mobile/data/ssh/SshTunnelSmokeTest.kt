package com.hermes.mobile.data.ssh

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.mobile.data.ConnectionDiagnostics
import com.hermes.mobile.data.remote.HermesClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address

/** 只连 adb reverse 暴露的本机假服务，不能填用户服务器或凭据。 */
@RunWith(AndroidJUnit4::class)
class SshTunnelSmokeTest {
    @Test fun realAndroidHttpGoesThroughIpv4SshTunnel() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val sshPort = args.getString("sshTestPort")?.toIntOrNull()
        val apiPort = args.getString("hermesTestPort")?.toIntOrNull()
        assumeTrue("先启动 androidSshFixture 并按 docs/ssh-tests.md 配置 adb reverse", sshPort != null && apiPort != null)
        assertTrue(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1"))
        val trace = ConnectionDiagnostics().begin(ConnectionDiagnostics.Use.Test)
        SshTunnel("127.0.0.1", sshPort!!, "hermes", SshAuth.Password("correct-horse"), apiPort!!, null, diagnostics = trace).use { tunnel ->
            tunnel.connect()
            assertTrue(tunnel.localBindAddress is Inet4Address)
            assertEquals(null, tunnel.probeForward())
            val client = HermesClient(tunnel.localBaseUrl, "test-key-0123456789abcdef").throughSshTunnel(trace)
            withTimeout(15_000) { assertEquals("hermes-agent", client.health().platform) }
            val report = trace.report()
            for (event in listOf("UpFirstBytes", "DownFirstBytes", "HTTP=200", "HealthParsed", "proxy=DIRECT")) {
                assertTrue(report, report.contains(event))
            }
        }
    }
}

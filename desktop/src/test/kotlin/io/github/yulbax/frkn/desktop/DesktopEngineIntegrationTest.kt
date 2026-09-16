package io.github.yulbax.frkn.desktop

import io.github.yulbax.frkn.util.AppLog
import io.github.yulbax.frkn.vpn.HealthSnapshot
import io.github.yulbax.frkn.vpn.ProbeParams
import io.github.yulbax.frkn.vpn.ProbeUrls
import io.github.yulbax.frkn.vpn.SocksHealthProbe
import io.github.yulbax.frkn.vpn.core.EngineConfig
import io.github.yulbax.frkn.vpn.core.EngineProxy
import io.github.yulbax.frkn.vpn.core.NetworkOptions
import io.github.yulbax.frkn.vpn.core.VpnEngine
import io.github.yulbax.frkn.vpn.core.freeLoopbackPort
import io.github.yulbax.frkn.vpn.core.randomToken
import io.github.yulbax.frkn.vpn.singbox.AppRouting
import io.github.yulbax.frkn.vpn.singbox.ConfigBuilder
import io.github.yulbax.frkn.vpn.singbox.ControlApi
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class DesktopEngineIntegrationTest {
    private val binDir = System.getenv("FRKN_TEST_BIN_DIR")?.let(::File)
    private val workDir = Files.createTempDirectory("frkn-desktop-test").toFile()
    private val processes = mutableListOf<Process>()

    @Before
    fun requireBinaries() {
        assumeTrue("set FRKN_TEST_BIN_DIR to a directory with sing-box and ciadpi", binDir?.isDirectory == true)
    }

    @After
    fun cleanup() {
        processes.forEach { it.destroyForcibly().waitFor(5, TimeUnit.SECONDS) }
        workDir.deleteRecursively()
    }

    @Test
    fun windowsStyleConfigPassesSingBoxCheck() {
        val config = ConfigBuilder.build(
            proxies = listOf(EngineProxy("p1", """{"type":"vless","server":"a.example","server_port":443,"uuid":"b5e6f6c2-6f5e-4c57-9a1c-1a9a2e2c3d4e","tls":{"enabled":true,"server_name":"a.example"}}""")),
            activeProxyTag = "p1",
            byeDpiPackages = listOf("Discord.exe"),
            vpnPackages = listOf("Telegram.exe"),
            tunneledPackages = listOf("Discord.exe", "Telegram.exe"),
            byeDpiPort = 1081,
            probePort = 2080,
            probeUser = "user",
            probePass = "pass",
            options = NetworkOptions(),
            routing = AppRouting.DesktopProcesses(listOf("ciadpi.exe"), ControlApi(9090, "secret"))
        )
        val file = File(workDir, "config.json").apply { writeText(config) }
        val check = ProcessBuilder(File(binDir, "sing-box").absolutePath, "check", "-c", file.absolutePath)
            .redirectErrorStream(true).start()
        val output = check.inputStream.bufferedReader().readText()
        assertTrue("sing-box check failed: $output", check.waitFor(30, TimeUnit.SECONDS) && check.exitValue() == 0)
    }

    @Test
    fun clashApiControlsARealSingBoxProcess() {
        val apiPort = freeLoopbackPort()
        val socksPort = freeLoopbackPort()
        val secret = randomToken()
        val user = randomToken()
        val pass = randomToken()
        val config = File(workDir, "api.json").apply {
            writeText(
                """
                {
                  "log": {"level": "warn"},
                  "inbounds": [{"type": "socks", "tag": "probe-in", "listen": "127.0.0.1", "listen_port": $socksPort,
                                "users": [{"username": "$user", "password": "$pass"}]}],
                  "outbounds": [
                    {"type": "direct", "tag": "p1"},
                    {"type": "direct", "tag": "p2"},
                    {"type": "selector", "tag": "proxy", "outbounds": ["p1", "p2"], "default": "p1"}
                  ],
                  "route": {"final": "proxy"},
                  "experimental": {"clash_api": {"external_controller": "127.0.0.1:$apiPort", "secret": "$secret"}}
                }
                """.trimIndent()
            )
        }
        processes += ProcessBuilder(File(binDir, "sing-box").absolutePath, "run", "-c", config.absolutePath)
            .redirectErrorStream(true).redirectOutput(File(workDir, "out.log")).start()

        val api = ClashApi(apiPort, secret)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!api.isReady() && System.nanoTime() < deadline) Thread.sleep(100)
        assertTrue(api.isReady())
        assertFalse(ClashApi(apiPort, "wrong").isReady())

        assertTrue(api.select("proxy", "p2"))
        assertNotNull(api.delay("p1", ProbeUrls.VPN, 10_000))

        val sample = CountDownLatch(1)
        val traffic = api.streamTraffic { _, _ -> sample.countDown() }
        assertTrue(sample.await(10, TimeUnit.SECONDS))
        traffic.disconnect()

        val snapshot: HealthSnapshot = runBlocking {
            withTimeout(30.seconds) {
                SocksHealthProbe(ProbeUrls.VPN, ProbeUrls.BYEDPI)
                    .observe(ProbeParams(FakeEngine(socksPort, user, pass), byeDpiPort = null, vpnActive = true) { false })
                    .first()
            }
        }
        assertTrue("probe through authenticated SOCKS failed", snapshot.vpnUp)
    }

    @Test
    fun ciadpiProcessStartsStopsAndReportsUnexpectedExit() {
        val ciadpi = CiadpiProcess(File(binDir, "ciadpi"), SilentLog)
        val port = freeLoopbackPort()
        val exits = CountDownLatch(1)
        ciadpi.start(port, listOf("-d1")) { exits.countDown() }
        assertTrue(ciadpi.isRunning)
        assertTrue(waitForPort(port))
        ciadpi.stop()
        assertFalse(ciadpi.isRunning)
        assertFalse("intentional stop must not be reported as a crash", exits.await(2, TimeUnit.SECONDS))

        val crashing = CiadpiProcess(File(binDir, "ciadpi"), SilentLog)
        val codes = mutableListOf<Int>()
        val crashed = CountDownLatch(1)
        crashing.start(port, listOf("--definitely-invalid-option")) { code -> codes += code; crashed.countDown() }
        assertTrue(crashed.await(10, TimeUnit.SECONDS))
        assertEquals(1, codes.size)
        crashing.stop()
    }

    private fun waitForPort(port: Int): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (runCatching { java.net.Socket("127.0.0.1", port).close() }.isSuccess) return true
            Thread.sleep(100)
        }
        return false
    }

    private class FakeEngine(
        override val probeSocksPort: Int,
        override val probeUsername: String,
        override val probePassword: String
    ) : VpnEngine {
        override fun start(config: EngineConfig) = Unit
        override fun reloadRouting(config: EngineConfig) = Unit
        override fun selectProxy(tag: String): Boolean = true
        override fun testProxies() = Unit
        override fun hasFingerprintError(): Boolean = false
        override fun stop() = Unit
    }

    private object SilentLog : AppLog {
        override fun i(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, t: Throwable?) = Unit
        override fun e(tag: String, message: String, t: Throwable?) = Unit
    }

}

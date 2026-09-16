package io.github.yulbax.frkn.desktop

import io.github.yulbax.frkn.util.AppLog
import io.github.yulbax.frkn.vpn.core.EngineConfig
import io.github.yulbax.frkn.vpn.core.EngineListener
import io.github.yulbax.frkn.vpn.core.ProxyDelay
import io.github.yulbax.frkn.vpn.core.VpnEngine
import io.github.yulbax.frkn.vpn.core.freeLoopbackPort
import io.github.yulbax.frkn.vpn.core.randomToken
import io.github.yulbax.frkn.vpn.singbox.AppRouting
import io.github.yulbax.frkn.vpn.singbox.ConfigBuilder
import io.github.yulbax.frkn.vpn.singbox.ControlApi
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SingBoxProcessEngine(
    private val binary: File,
    private val workDir: File,
    private val directProcesses: List<String>,
    private val delayProbeUrl: String,
    private val listener: EngineListener,
    private val log: AppLog
) : VpnEngine {

    override val probeSocksPort: Int = freeLoopbackPort()
    override val probeUsername: String = randomToken()
    override val probePassword: String = randomToken()

    private val controlApi = ControlApi(freeLoopbackPort(), randomToken())
    private val api = ClashApi(controlApi.port, controlApi.secret)
    private val boxLog = File(workDir, "box.log")
    private val stdoutLog = File(workDir, "sing-box-output.log")

    @Volatile private var process: Process? = null
    @Volatile private var proxyTags: List<String> = emptyList()
    private var traffic: HttpURLConnection? = null

    @Synchronized
    override fun start(config: EngineConfig) {
        check(process == null) { "sing-box is already running" }
        launch(config)
    }

    @Synchronized
    override fun reloadRouting(config: EngineConfig) {
        terminate()
        launch(config)
    }

    override fun selectProxy(tag: String): Boolean = api.select(ConfigBuilder.PROXY_GROUP_TAG, tag)

    override fun testProxies() {
        val tags = proxyTags
        thread(isDaemon = true, name = "sing-box-delay") {
            val delays = tags.associateWith { tag ->
                api.delay(tag, delayProbeUrl, DELAY_TIMEOUT_MS)?.let(ProxyDelay::Measured) ?: ProxyDelay.Failed
            }
            listener.onProxyDelays(delays)
        }
    }

    override fun hasFingerprintError(): Boolean = runCatching {
        boxLog.takeIf { it.exists() }?.useLines { lines ->
            lines.any { it.contains("unsupported curve", ignoreCase = true) }
        } ?: false
    }.getOrDefault(false)

    @Synchronized
    override fun stop() {
        terminate()
    }

    private fun launch(config: EngineConfig) {
        check(binary.isFile) { "sing-box binary not found: ${binary.absolutePath}" }
        workDir.mkdirs()
        runCatching { boxLog.writeText("") }
        val configFile = File(workDir, "config.json")
        configFile.writeText(buildConfig(config))
        proxyTags = config.proxies.map { it.tag }

        val started = ProcessBuilder(binary.absolutePath, "run", "-c", configFile.absolutePath, "-D", workDir.absolutePath)
            .directory(workDir)
            .redirectErrorStream(true)
            .redirectOutput(stdoutLog)
            .start()
        process = started
        try {
            awaitReady(started)
        } catch (t: Throwable) {
            terminate()
            throw t
        } finally {
            configFile.delete()
        }
        log.i(TAG, "sing-box started pid=${started.pid()}")
        started.onExit().thenAccept { exited ->
            if (process === exited) {
                log.w(TAG, "sing-box exited unexpectedly code=${exited.exitValue()}")
                listener.onStopRequested()
            }
        }
        traffic = runCatching { api.streamTraffic(listener::onThroughput) }.getOrNull()
    }

    private fun awaitReady(started: Process) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STARTUP_TIMEOUT_MS)
        while (System.nanoTime() < deadline) {
            if (!started.isAlive) error("sing-box exited with code ${started.exitValue()}: ${outputTail()}")
            if (api.isReady()) return
            Thread.sleep(100)
        }
        error("sing-box did not become ready: ${outputTail()}")
    }

    private fun terminate() {
        val current = process ?: return
        process = null
        runCatching { traffic?.disconnect() }
        traffic = null
        current.destroy()
        if (!current.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) current.destroyForcibly()
    }

    private fun buildConfig(config: EngineConfig): String = ConfigBuilder.build(
        proxies = config.proxies,
        activeProxyTag = config.activeProxyTag,
        byeDpiPackages = config.byeDpiPackages,
        vpnPackages = config.vpnPackages,
        tunneledPackages = config.tunneledPackages,
        byeDpiPort = config.byeDpiSocksPort,
        probePort = probeSocksPort,
        probeUser = probeUsername,
        probePass = probePassword,
        options = config.network,
        routing = AppRouting.DesktopProcesses(directProcesses, controlApi)
    )

    private fun outputTail(): String =
        runCatching { stdoutLog.readLines().takeLast(5).joinToString(" | ") }.getOrDefault("")

    private companion object {
        const val TAG = "SingBoxProcess"
        const val STARTUP_TIMEOUT_MS = 15_000L
        const val STOP_TIMEOUT_MS = 5_000L
        const val DELAY_TIMEOUT_MS = 5_000
    }
}

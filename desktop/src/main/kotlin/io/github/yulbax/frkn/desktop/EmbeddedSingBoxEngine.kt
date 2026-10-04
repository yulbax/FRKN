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
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class EmbeddedSingBoxEngine(
    private val core: SingBoxCore,
    private val workDir: File,
    private val directProcesses: List<String>,
    private val delayProbeUrl: String,
    private val listener: EngineListener,
    private val log: AppLog
) : VpnEngine {

    override val probeSocksPort: Int = freeLoopbackPort()
    override val probeUsername: String = randomToken()
    override val probePassword: String = randomToken()

    private val boxLog = File(workDir, "box.log")

    @Volatile private var running = false
    @Volatile private var proxyTags: List<String> = emptyList()
    private var traffic: ScheduledExecutorService? = null

    @Synchronized
    override fun start(config: EngineConfig) {
        check(!running) { "sing-box is already running" }
        launch(config)
    }

    @Synchronized
    override fun reloadRouting(config: EngineConfig) {
        terminate()
        launch(config)
    }

    override fun selectProxy(tag: String): Boolean = core.select(ConfigBuilder.PROXY_GROUP_TAG, tag)

    override fun testProxies() {
        val tags = proxyTags
        thread(isDaemon = true, name = "sing-box-delay") {
            val delays = tags.associateWith { tag ->
                core.delay(tag, delayProbeUrl, DELAY_TIMEOUT_MS)?.let(ProxyDelay::Measured) ?: ProxyDelay.Failed
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
        workDir.mkdirs()
        runCatching { boxLog.writeText("") }
        proxyTags = config.proxies.map { it.tag }
        core.start(buildConfig(config), workDir)
        running = true
        log.i(TAG, "sing-box started")
        traffic = sampleTraffic()
    }

    private fun sampleTraffic(): ScheduledExecutorService {
        var last: SingBoxCore.Traffic? = null
        return Executors.newSingleThreadScheduledExecutor { Thread(it, "sing-box-traffic").apply { isDaemon = true } }
            .apply {
                scheduleAtFixedRate({
                    val now = core.traffic() ?: return@scheduleAtFixedRate
                    last?.let { listener.onThroughput(now.up - it.up, now.down - it.down) }
                    last = now
                }, 0, 1, TimeUnit.SECONDS)
            }
    }

    private fun terminate() {
        if (!running) return
        running = false
        traffic?.shutdownNow()
        traffic = null
        runCatching { core.stop() }.onFailure { log.w(TAG, "sing-box stop failed", it) }
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
        routing = AppRouting.DesktopProcesses(directProcesses)
    )

    private companion object {
        const val TAG = "SingBoxCore"
        const val DELAY_TIMEOUT_MS = 5_000
    }
}

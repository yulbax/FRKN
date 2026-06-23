package io.github.yulbax.frkn.vpn.core

interface VpnEngine {
    val probeSocksPort: Int
    val probeUsername: String
    val probePassword: String

    fun start(config: EngineConfig)

    fun reloadRouting(config: EngineConfig)

    fun selectProxy(tag: String): Boolean

    fun testProxies()

    fun hasFingerprintError(): Boolean

    fun stop()
}

interface EngineListener {
    fun onThroughput(uplinkBytesPerSec: Long, downlinkBytesPerSec: Long)
    fun onProxyDelays(delays: Map<String, Int>)
    fun onStopRequested()
}

fun interface DefaultInterfaceListener {
    fun onDefaultInterfaceChanged(name: String, index: Int)
}

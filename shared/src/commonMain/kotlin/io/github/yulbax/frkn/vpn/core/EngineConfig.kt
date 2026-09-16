package io.github.yulbax.frkn.vpn.core

data class EngineConfig(
    val proxies: List<EngineProxy>,
    val activeProxyTag: String,
    val byeDpiPackages: List<String>,
    val vpnPackages: List<String>,
    val tunneledPackages: List<String>,
    val byeDpiSocksPort: Int,
    val network: NetworkOptions
)

data class EngineProxy(
    val tag: String,
    val outboundDescriptor: String
)

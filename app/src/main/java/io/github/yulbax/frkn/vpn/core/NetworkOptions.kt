package io.github.yulbax.frkn.vpn.core

data class NetworkOptions(
    val tunStack: TunStack = TunStack.GVISOR,
    val mtu: Int = 9000,
    val ipv6Mode: Ipv6Mode = Ipv6Mode.DISABLE,
    val dnsRemote: String = "1.1.1.1",
    val dnsDirect: String = "1.1.1.1",
    val sniff: Boolean = true,
    val bypassLan: Boolean = false,
    val preferredFingerprint: TlsFingerprint? = null
)

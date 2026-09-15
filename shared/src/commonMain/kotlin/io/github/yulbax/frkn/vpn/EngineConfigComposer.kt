package io.github.yulbax.frkn.vpn

import io.github.yulbax.frkn.data.RoutedApps
import io.github.yulbax.frkn.vpn.core.EngineConfig
import io.github.yulbax.frkn.vpn.core.EngineProxy

object ProxyTag {
    private const val PREFIX = "p"

    fun of(profileId: Long): String = "$PREFIX$profileId"

    fun profileId(tag: String): Long? =
        tag.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.toLongOrNull()
}

data class AppliedConfig(
    val engineConfig: EngineConfig,
    val configName: String,
    val membershipKey: String,
    val routingKey: String
) {
    val selectedTag: String get() = engineConfig.activeProxyTag
    val vpnActive: Boolean get() = engineConfig.vpnPackages.isNotEmpty()
    val needsByeDpi: Boolean get() = engineConfig.byeDpiPackages.isNotEmpty()

    fun isStructuralChangeFrom(other: AppliedConfig): Boolean =
        membershipKey != other.membershipKey || routingKey != other.routingKey
}

object EngineConfigComposer {

    fun compose(inputs: SessionInputs, ownPackage: String, byeDpiPort: Int): AppliedConfig {
        val selected = inputs.selected ?: throw IllegalStateException("No server selected")
        val profiles = inputs.profiles
        val foreignApps = inputs.apps.filterNot { it.packageName == ownPackage }
        val routed = RoutedApps.from(foreignApps)
        check(!routed.isEmpty) { "No apps assigned to VPN or ByeDPI" }

        return AppliedConfig(
            engineConfig = EngineConfig(
                proxies = profiles.map { EngineProxy(ProxyTag.of(it.id), it.outboundJson) },
                activeProxyTag = ProxyTag.of(selected.id),
                byeDpiPackages = routed.byeDpiPackages,
                vpnPackages = routed.vpnPackages,
                tunneledPackages = routed.tunneledPackages,
                byeDpiSocksPort = byeDpiPort,
                network = inputs.settings.networkOptions()
            ),
            configName = selected.name.ifBlank { "(unnamed)" },
            membershipKey = profiles.joinToString("|") { "${it.id}:${it.name}:${it.outboundJson}" },
            routingKey = foreignApps.sortedBy { it.packageName }
                .joinToString("|") { "${it.packageName}=${it.connectionType}" }
        )
    }
}

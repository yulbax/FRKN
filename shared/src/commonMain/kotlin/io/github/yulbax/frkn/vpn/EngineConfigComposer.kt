package io.github.yulbax.frkn.vpn

import io.github.yulbax.frkn.data.RoutedApps
import io.github.yulbax.frkn.data.profile.ProfileEntity
import io.github.yulbax.frkn.vpn.core.EngineConfig
import io.github.yulbax.frkn.vpn.core.EngineProxy
import io.github.yulbax.frkn.vpn.core.EngineRequirement

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
    val sessionRequirements: Set<EngineRequirement>,
    val routingKey: String,
    val vpnActive: Boolean
) {
    val selectedTag: String get() = engineConfig.activeProxyTag
    val needsByeDpi: Boolean get() = engineConfig.byeDpiPackages.isNotEmpty()

    fun isStructuralChangeFrom(other: AppliedConfig): Boolean =
        membershipKey != other.membershipKey ||
            routingKey != other.routingKey ||
            sessionRequirements != other.sessionRequirements
}

object EngineConfigComposer {

    fun compose(inputs: SessionInputs, ownPackage: String, byeDpiPort: Int): AppliedConfig {
        val selected = inputs.selected ?: throw IllegalStateException("No server selected")
        val profiles = inputs.profiles
        val foreignApps = inputs.apps.filterNot { it.packageName == ownPackage }
        val routed = RoutedApps.from(foreignApps, inputs.settings.routeAllTraffic)
        check(!routed.isEmpty) { "No apps assigned to VPN or ByeDPI" }

        return AppliedConfig(
            engineConfig = EngineConfig(
                proxies = profiles.map { it.engineProxy() },
                activeProxyTag = ProxyTag.of(selected.id),
                byeDpiPackages = routed.byeDpiPackages,
                vpnPackages = routed.vpnPackages,
                tunneledPackages = routed.tunneledPackages,
                byeDpiSocksPort = byeDpiPort,
                network = inputs.settings.networkOptions()
            ),
            configName = selected.name.ifBlank { "(unnamed)" },
            membershipKey = profiles.joinToString("|") { "${it.id}:${it.name}:${it.outboundJson}" },
            sessionRequirements = selected.engineProxy().sessionRequirements,
            routingKey = foreignApps.sortedBy { it.packageName }
                .joinToString("|") { "${it.packageName}=${it.connectionType}" },
            vpnActive = routed.hasVpn
        )
    }

    private fun ProfileEntity.engineProxy(): EngineProxy =
        protocol?.engineProxy(ProxyTag.of(id), outboundJson) ?: EngineProxy(ProxyTag.of(id), outboundJson)
}

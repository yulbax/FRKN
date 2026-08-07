package io.github.yulbax.frkn.data

import io.github.yulbax.frkn.data.profile.ProfileEntity
import kotlinx.serialization.Serializable

/** Stable external format. Room entities must not become part of this contract. */
@Serializable
data class AppConfigBackup(
    val version: Int = MIN_SUPPORTED_VERSION,
    val apps: Map<String, String>? = null,
    val settings: BackupSettings? = null,
    val profiles: List<BackupProfile>? = null
) {
    companion object {
        const val MIN_SUPPORTED_VERSION = 1
        const val CURRENT_VERSION = 4
        const val MAX_IMPORT_BYTES = 5L * 1024 * 1024
        const val MAX_ENTRIES_PER_SECTION = 10_000
    }
}

@Serializable
data class BackupSettings(
    val showSystemApps: Boolean = false,
    val byeDpiArgs: String = "",
    val tunStack: String = "gvisor",
    val mtu: Int = 9000,
    val ipv6Mode: String = "disable",
    val dnsRemote: String = "1.1.1.1",
    val dnsDirect: String = "1.1.1.1",
    val sniff: Boolean = true,
    val bypassLan: Boolean = false,
    val autoConnect: Boolean = false,
    val preferredFingerprint: String = "",
    val homeHintSeen: Boolean = false,
    val appsHintSeen: Boolean = false
)

@Serializable
data class BackupProfile(
    val name: String = "",
    val type: String = "",
    val link: String = "",
    val outboundJson: String = "",
    val selected: Boolean = false,
    val subscriptionUrl: String = ""
)

internal fun SettingsEntity.toBackupSettings() = BackupSettings(
    showSystemApps = showSystemApps,
    byeDpiArgs = byeDpiArgs,
    tunStack = tunStack,
    mtu = mtu,
    ipv6Mode = ipv6Mode,
    dnsRemote = dnsRemote,
    dnsDirect = dnsDirect,
    sniff = sniff,
    bypassLan = bypassLan,
    autoConnect = autoConnect,
    preferredFingerprint = preferredFingerprint,
    homeHintSeen = homeHintSeen,
    appsHintSeen = appsHintSeen
)

internal fun BackupSettings.toEntity() = SettingsEntity(
    showSystemApps = showSystemApps,
    byeDpiArgs = byeDpiArgs,
    tunStack = tunStack,
    mtu = mtu,
    ipv6Mode = ipv6Mode,
    dnsRemote = dnsRemote,
    dnsDirect = dnsDirect,
    sniff = sniff,
    bypassLan = bypassLan,
    autoConnect = autoConnect,
    preferredFingerprint = preferredFingerprint,
    homeHintSeen = homeHintSeen,
    appsHintSeen = appsHintSeen
)

internal fun ProfileEntity.toBackupProfile() = BackupProfile(
    name = name,
    type = type,
    link = link,
    outboundJson = outboundJson,
    selected = selected,
    subscriptionUrl = subscriptionUrl
)

internal fun BackupProfile.toEntity(canonicalOutboundJson: String) = ProfileEntity(
    name = name,
    type = type,
    link = link,
    outboundJson = canonicalOutboundJson,
    subscriptionUrl = subscriptionUrl
)

package io.github.yulbax.frkn.data

import io.github.yulbax.frkn.data.profile.ProfileEntity
import io.github.yulbax.frkn.util.ParsedProfile
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
    val showSystemApps: Boolean = SETTINGS_DEFAULTS.showSystemApps,
    val byeDpiArgs: String = SETTINGS_DEFAULTS.byeDpiArgs,
    val tunStack: String = SETTINGS_DEFAULTS.tunStack,
    val mtu: Int = SETTINGS_DEFAULTS.mtu,
    val ipv6Mode: String = SETTINGS_DEFAULTS.ipv6Mode,
    val dnsRemote: String = SETTINGS_DEFAULTS.dnsRemote,
    val dnsDirect: String = SETTINGS_DEFAULTS.dnsDirect,
    val sniff: Boolean = SETTINGS_DEFAULTS.sniff,
    val bypassLan: Boolean = SETTINGS_DEFAULTS.bypassLan,
    val autoConnect: Boolean = SETTINGS_DEFAULTS.autoConnect,
    val preferredFingerprint: String = SETTINGS_DEFAULTS.preferredFingerprint,
    val homeHintSeen: Boolean = SETTINGS_DEFAULTS.homeHintSeen,
    val appsHintSeen: Boolean = SETTINGS_DEFAULTS.appsHintSeen
)

private val SETTINGS_DEFAULTS = SettingsEntity()

@Serializable
data class BackupProfile(
    val name: String = "",
    val type: String = "",
    val link: String = "",
    val outboundJson: String = "",
    val selected: Boolean = false,
    val subscriptionUrl: String = ""
)

fun SettingsEntity.toBackupSettings() = BackupSettings(
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

fun BackupSettings.toEntity() = SettingsEntity(
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

fun ProfileEntity.toBackupProfile() = BackupProfile(
    name = name,
    type = type.wire,
    link = link,
    outboundJson = outboundJson,
    selected = selected,
    subscriptionUrl = subscriptionUrl
)

fun BackupProfile.toEntity(parsed: ParsedProfile) = ProfileEntity(
    name = name,
    type = parsed.protocol,
    link = link,
    outboundJson = parsed.outboundJson(),
    subscriptionUrl = subscriptionUrl
)

package io.github.yulbax.frkn.data

import kotlinx.serialization.Serializable

@Serializable
data class AppConfigBackup(
    val version: Int = 1,
    val apps: Map<String, String>,
    val settings: SettingsEntity? = null
) {
    companion object {
        const val CURRENT_VERSION = 2
    }
}

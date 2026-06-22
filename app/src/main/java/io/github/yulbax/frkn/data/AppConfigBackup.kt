package io.github.yulbax.frkn.data

import io.github.yulbax.frkn.data.profile.ProfileEntity
import kotlinx.serialization.Serializable

@Serializable
data class AppConfigBackup(
    val version: Int = 1,
    val apps: Map<String, String>? = null,
    val settings: SettingsEntity? = null,
    val profiles: List<ProfileEntity>? = null
) {
    companion object {
        const val CURRENT_VERSION = 3
    }
}

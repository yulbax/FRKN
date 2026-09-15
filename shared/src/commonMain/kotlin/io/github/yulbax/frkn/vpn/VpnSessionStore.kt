package io.github.yulbax.frkn.vpn

import io.github.yulbax.frkn.data.App
import io.github.yulbax.frkn.data.SettingsEntity
import io.github.yulbax.frkn.data.profile.ProfileEntity
import io.github.yulbax.frkn.data.profile.ProfileOperationResult
import kotlinx.coroutines.flow.Flow

data class SessionInputs(
    val selected: ProfileEntity?,
    val profiles: List<ProfileEntity>,
    val settings: SettingsEntity,
    val apps: List<App>
)

interface VpnSessionStore {
    suspend fun load(): SessionInputs

    fun routingChanges(): Flow<Unit>

    suspend fun selectedSubscription(): ProfileEntity?

    suspend fun refreshSubscription(profile: ProfileEntity): ProfileOperationResult
}

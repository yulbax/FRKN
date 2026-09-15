package io.github.yulbax.frkn.di

import android.content.Context
import androidx.room.Room
import io.github.yulbax.frkn.data.AppDao
import io.github.yulbax.frkn.data.AppDatabase
import io.github.yulbax.frkn.data.ConfigBackupRepository
import io.github.yulbax.frkn.data.RoomVpnSessionStore
import io.github.yulbax.frkn.data.SettingsDao
import io.github.yulbax.frkn.data.SettingsRepository
import io.github.yulbax.frkn.data.profile.ProfileDao
import io.github.yulbax.frkn.data.profile.ProfileRepository
import io.github.yulbax.frkn.data.profile.SubscriptionProfileSource
import io.github.yulbax.frkn.data.profile.HttpSubscriptionProfileSource
import io.github.yulbax.frkn.vpn.SocksSiteProbe
import io.github.yulbax.frkn.vpn.ByeDpiSiteProbe
import io.github.yulbax.frkn.vpn.VpnCommandBus
import io.github.yulbax.frkn.vpn.VpnSessionStore
import io.github.yulbax.frkn.vpn.VpnStateRepository
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module
@ComponentScan("io.github.yulbax.frkn")
class AppModule {
    @Single
    fun database(context: Context): AppDatabase = AppDatabase.build(
        Room.databaseBuilder<AppDatabase>(
            context = context.applicationContext,
            name = context.getDatabasePath(AppDatabase.FILE_NAME).absolutePath
        )
    )

    @Single
    fun appDao(database: AppDatabase): AppDao = database.appDao()

    @Single
    fun settingsDao(database: AppDatabase): SettingsDao = database.settingsDao()

    @Single
    fun profileDao(database: AppDatabase): ProfileDao = database.profileDao()

    @Single
    fun settingsRepository(database: AppDatabase, settingsDao: SettingsDao): SettingsRepository =
        SettingsRepository(database, settingsDao)

    @Single
    fun profileRepository(
        database: AppDatabase,
        profileDao: ProfileDao,
        subscriptionSource: SubscriptionProfileSource
    ): ProfileRepository = ProfileRepository(database, profileDao, subscriptionSource)

    @Single
    fun vpnSessionStore(database: AppDatabase, profileRepository: ProfileRepository): VpnSessionStore =
        RoomVpnSessionStore(database, profileRepository)

    @Single
    fun configBackupRepository(database: AppDatabase, profileRepository: ProfileRepository): ConfigBackupRepository =
        ConfigBackupRepository(database, profileRepository)

    @Single
    fun byeDpiSiteProbe(): ByeDpiSiteProbe = SocksSiteProbe

    @Single
    fun subscriptionProfileSource(): SubscriptionProfileSource = HttpSubscriptionProfileSource()

    @Single
    fun vpnStateRepository(): VpnStateRepository = VpnStateRepository()

    @Single
    fun vpnCommandBus(): VpnCommandBus = VpnCommandBus()
}

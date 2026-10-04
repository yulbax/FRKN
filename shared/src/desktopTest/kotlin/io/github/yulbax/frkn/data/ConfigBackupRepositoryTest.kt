package io.github.yulbax.frkn.data

import androidx.room.Room
import io.github.yulbax.frkn.data.profile.ProfileRepository
import io.github.yulbax.frkn.data.profile.SubscriptionProfileSource
import io.github.yulbax.frkn.proxy.ParsedProfile
import io.github.yulbax.frkn.util.AppLog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBackupRepositoryTest {
    private val database = AppDatabase.build(Room.inMemoryDatabaseBuilder<AppDatabase>())
    private val repository = ConfigBackupRepository(
        database,
        ProfileRepository(database, database.profileDao(), NoSubscriptions, SilentLog)
    )

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun importingSettingsKeepsTheDeviceRouteAllTrafficChoice() = runBlocking {
        database.settingsDao().upsertSettings(SettingsEntity(routeAllTraffic = true, mtu = 1500))
        val backup = AppConfigBackup(
            version = AppConfigBackup.CURRENT_VERSION,
            settings = BackupSettings(mtu = 1280)
        )

        repository.apply(backup, BackupSelection(settings = true, apps = false, profiles = false))

        val settings = requireNotNull(database.settingsDao().getSettings())
        assertEquals(1280, settings.mtu)
        assertTrue(settings.routeAllTraffic)
    }

    private object NoSubscriptions : SubscriptionProfileSource {
        override suspend fun fetch(url: String): List<ParsedProfile> = emptyList()
    }

    private object SilentLog : AppLog {
        override fun i(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, t: Throwable?) = Unit
        override fun e(tag: String, message: String, t: Throwable?) = Unit
    }
}

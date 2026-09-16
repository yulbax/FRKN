package io.github.yulbax.frkn.data

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import androidx.room.immediateTransaction
import androidx.room.migration.Migration
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.yulbax.frkn.data.profile.ProfileDao
import io.github.yulbax.frkn.data.profile.ProfileEntity
import io.github.yulbax.frkn.data.profile.ProxyProtocolConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

@Database(
    entities = [App::class, SettingsEntity::class, ProfileEntity::class],
    version = 3,
    exportSchema = true
)
@TypeConverters(ProxyProtocolConverter::class)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun appDao(): AppDao
    abstract fun settingsDao(): SettingsDao
    abstract fun profileDao(): ProfileDao

    suspend fun <R> transaction(block: suspend () -> R): R =
        useWriterConnection { connection -> connection.immediateTransaction { block() } }

    companion object {
        const val FILE_NAME = "frkn.db"

        val MIGRATIONS: Array<Migration> = arrayOf(
            object : Migration(1, 2) {
                override fun migrate(connection: SQLiteConnection) {
                    connection.execSQL("ALTER TABLE settings ADD COLUMN preferredFingerprint TEXT NOT NULL DEFAULT ''")
                }
            },
            object : Migration(2, 3) {
                override fun migrate(connection: SQLiteConnection) {
                    connection.execSQL("ALTER TABLE settings ADD COLUMN homeHintSeen INTEGER NOT NULL DEFAULT 0")
                    connection.execSQL("ALTER TABLE settings ADD COLUMN appsHintSeen INTEGER NOT NULL DEFAULT 0")
                }
            }
        )

        fun build(builder: Builder<AppDatabase>): AppDatabase =
            builder
                .addMigrations(*MIGRATIONS)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
    }
}

@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

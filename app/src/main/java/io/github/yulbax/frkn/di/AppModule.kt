package io.github.yulbax.frkn.di

import android.content.Context
import io.github.yulbax.frkn.data.AppDao
import io.github.yulbax.frkn.data.AppDatabase
import io.github.yulbax.frkn.data.SettingsDao
import io.github.yulbax.frkn.data.profile.ProfileDao
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module
@ComponentScan("io.github.yulbax.frkn")
class AppModule {
    @Single
    fun database(context: Context): AppDatabase = AppDatabase.build(context)

    @Single
    fun appDao(database: AppDatabase): AppDao = database.appDao()

    @Single
    fun settingsDao(database: AppDatabase): SettingsDao = database.settingsDao()

    @Single
    fun profileDao(database: AppDatabase): ProfileDao = database.profileDao()
}

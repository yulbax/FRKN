package io.github.yulbax.frkn.di

import io.github.yulbax.frkn.data.AppDatabase
import io.github.yulbax.frkn.ui.viewmodel.AppsViewModel
import io.github.yulbax.frkn.ui.viewmodel.ConnectionViewModel
import io.github.yulbax.frkn.ui.viewmodel.ProfileViewModel
import io.github.yulbax.frkn.ui.viewmodel.SettingsViewModel
import io.github.yulbax.frkn.util.AppSyncManager
import io.github.yulbax.frkn.util.FrknLog
import io.github.yulbax.frkn.vpn.DefaultNetworkMonitor
import io.github.yulbax.frkn.vpn.VpnCommandBus
import io.github.yulbax.frkn.vpn.VpnStateRepository
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single { AppDatabase.build(androidContext()) }
    single { get<AppDatabase>().appDao() }
    single { get<AppDatabase>().settingsDao() }
    single { get<AppDatabase>().profileDao() }

    single { FrknLog(androidContext()) }
    single { VpnStateRepository() }
    single { VpnCommandBus() }
    single { DefaultNetworkMonitor(androidContext()) }
    single(createdAtStart = true) { AppSyncManager(androidContext(), get()) }

    viewModel { AppsViewModel(androidApplication(), get(), get()) }
    viewModel { SettingsViewModel(androidApplication(), get(), get()) }
    viewModel { ProfileViewModel(androidApplication(), get()) }
    viewModel { ConnectionViewModel(androidApplication(), get(), get(), get(), get()) }
}

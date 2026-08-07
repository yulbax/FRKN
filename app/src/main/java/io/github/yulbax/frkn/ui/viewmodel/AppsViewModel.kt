package io.github.yulbax.frkn.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yulbax.frkn.data.App
import io.github.yulbax.frkn.data.AppDao
import io.github.yulbax.frkn.data.ConnectionType
import io.github.yulbax.frkn.data.InstalledApp
import io.github.yulbax.frkn.data.InstalledAppsRepository
import io.github.yulbax.frkn.data.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

data class AppInfo(
    val packageName: String,
    val name: String,
    val isSystemApp: Boolean,
    val isLaunchable: Boolean = true,
    val connectionType: ConnectionType = ConnectionType.VPN
)

@KoinViewModel
class AppsViewModel(
    private val appDao: AppDao,
    private val installedAppsRepository: InstalledAppsRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val appsHintSeen: StateFlow<Boolean> = settingsRepository.settings
        .map { it.appsHintSeen }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun dismissAppsHint() {
        viewModelScope.launch {
            settingsRepository.update { it.copy(appsHintSeen = true) }
        }
    }

    val isLoading: StateFlow<Boolean> = installedAppsRepository.isLoading
    val error: StateFlow<String?> = installedAppsRepository.error

    private val installedAppInfos = installedAppsRepository.installedApps.map { installed ->
        installed.map { it.toAppInfo() }
    }

    val apps: StateFlow<List<AppInfo>> = combine(
        installedAppInfos,
        appDao.getAllApps(),
        settingsRepository.settings.map { it.showSystemApps }
    ) { installed, saved, showSystem ->
        val savedMap = saved.associateBy { it.packageName }
        installed
            .filter { showSystem || !it.isSystemApp || it.isLaunchable }
            .map { app ->
                val savedApp = savedMap[app.packageName]
                if (savedApp != null) app.copy(connectionType = savedApp.connectionType)
                else app
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allApps: StateFlow<List<AppInfo>> = combine(
        installedAppInfos,
        appDao.getAllApps()
    ) { installed, saved ->
        val savedMap = saved.associateBy { it.packageName }
        installed.map { app ->
            savedMap[app.packageName]?.let { app.copy(connectionType = it.connectionType) } ?: app
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setConnectionType(packageName: String, name: String, isSystemApp: Boolean, type: ConnectionType) {
        viewModelScope.launch {
            appDao.setConnectionTypes(listOf(App(packageName, name, isSystemApp, type)))
        }
    }

    fun setAllConnectionTypes(targets: List<AppInfo>, type: ConnectionType) {
        if (targets.isEmpty()) return
        viewModelScope.launch {
            appDao.setConnectionTypes(targets.map { App(it.packageName, it.name, it.isSystemApp, type) })
        }
    }

    fun restoreConnectionTypes(snapshot: List<AppInfo>) {
        if (snapshot.isEmpty()) return
        viewModelScope.launch {
            appDao.setConnectionTypes(
                snapshot.map { App(it.packageName, it.name, it.isSystemApp, it.connectionType) }
            )
        }
    }

    fun retry() {
        installedAppsRepository.retry()
    }

    private fun InstalledApp.toAppInfo() = AppInfo(
        packageName = packageName,
        name = name,
        isSystemApp = isSystemApp,
        isLaunchable = isLaunchable
    )
}

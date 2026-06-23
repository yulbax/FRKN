package io.github.yulbax.frkn.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yulbax.frkn.data.App
import io.github.yulbax.frkn.data.AppDao
import io.github.yulbax.frkn.data.ConnectionType
import io.github.yulbax.frkn.data.SettingsDao
import io.github.yulbax.frkn.data.SettingsEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

data class AppInfo(
    val packageName: String,
    val name: String,
    val isSystemApp: Boolean,
    val isLaunchable: Boolean = true,
    val connectionType: ConnectionType = ConnectionType.VPN
)

@KoinViewModel
class AppsViewModel(
    private val application: Application,
    private val appDao: AppDao,
    private val settingsDao: SettingsDao
) : ViewModel() {

    val appsHintSeen: StateFlow<Boolean> = settingsDao.observeSettings()
        .map { it?.appsHintSeen ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun dismissAppsHint() {
        viewModelScope.launch {
            val current = settingsDao.observeSettings().first() ?: SettingsEntity()
            if (!current.appsHintSeen) settingsDao.upsertSettings(current.copy(appsHintSeen = true))
        }
    }

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val apps: StateFlow<List<AppInfo>> = combine(
        _installedApps,
        appDao.getAllApps(),
        settingsDao.observeSettings().map { it?.showSystemApps ?: false }
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

    init {
        loadInstalledApps()
    }

    fun setConnectionType(packageName: String, name: String, isSystemApp: Boolean, type: ConnectionType) {
        viewModelScope.launch {
            appDao.upsertApp(App(packageName, name, isSystemApp, type))
        }
    }

    fun setAllConnectionTypes(targets: List<AppInfo>, type: ConnectionType) {
        if (targets.isEmpty()) return
        viewModelScope.launch {
            appDao.upsertApps(targets.map { App(it.packageName, it.name, it.isSystemApp, type) })
        }
    }

    fun restoreConnectionTypes(snapshot: List<AppInfo>) {
        if (snapshot.isEmpty()) return
        viewModelScope.launch {
            appDao.upsertApps(snapshot.map { App(it.packageName, it.name, it.isSystemApp, it.connectionType) })
        }
    }

    fun retry() {
        _error.value = null
        _isLoading.value = true
        _installedApps.value = emptyList()
        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val packageManager = application.packageManager
                val launchablePackages = packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                ).mapNotNull { it.activityInfo?.packageName }.toSet()
                val installed = packageManager.getInstalledApplications(0)
                val apps = coroutineScope {
                    installed.map { info ->
                        async {
                            AppInfo(
                                packageName = info.packageName,
                                name = runCatching {
                                    packageManager.getApplicationLabel(info).toString()
                                }.getOrDefault(info.packageName),
                                isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                                isLaunchable = info.packageName in launchablePackages
                            )
                        }
                    }.awaitAll()
                }
                    .sortedBy { it.name.lowercase() }
                    .filter { it.packageName != application.packageName }
                _installedApps.value = apps

                val installedPackages = apps.mapTo(HashSet()) { it.packageName }
                val existingPackages = appDao.getAllApps().first().mapTo(HashSet()) { it.packageName }

                val orphaned = existingPackages.filter { it !in installedPackages }
                if (orphaned.isNotEmpty()) appDao.deleteApps(orphaned)

                val newApps = apps
                    .filter { it.packageName !in existingPackages }
                    .map { app ->
                        val type = if (isRussianPackage(app.packageName)) {
                            ConnectionType.DIRECT
                        } else {
                            ConnectionType.VPN
                        }
                        App(app.packageName, app.name, app.isSystemApp, type)
                    }
                if (newApps.isNotEmpty()) appDao.upsertApps(newApps)

                _isLoading.value = false
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load apps"
                _isLoading.value = false
            }
        }
    }

    companion object {
        fun isRussianPackage(packageName: String): Boolean {
            val blockedPackages = setOf("ru", "yandex", "vkontakte", "vk")
            return packageName.split('.').any { segment ->
                segment.lowercase() in blockedPackages
            }
        }
    }
}

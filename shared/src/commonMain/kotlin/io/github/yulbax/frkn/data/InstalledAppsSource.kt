package io.github.yulbax.frkn.data

import kotlinx.coroutines.flow.StateFlow

data class InstalledApp(
    val packageName: String,
    val name: String,
    val isSystemApp: Boolean,
    val isLaunchable: Boolean
)

interface InstalledAppsSource {
    val installedApps: StateFlow<List<InstalledApp>>
    val isLoading: StateFlow<Boolean>
    val error: StateFlow<String?>

    fun retry()
}

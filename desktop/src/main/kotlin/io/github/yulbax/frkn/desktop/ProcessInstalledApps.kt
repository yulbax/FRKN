package io.github.yulbax.frkn.desktop

import io.github.yulbax.frkn.data.App
import io.github.yulbax.frkn.data.AppDao
import io.github.yulbax.frkn.data.ConnectionType
import io.github.yulbax.frkn.data.InstalledApp
import io.github.yulbax.frkn.data.InstalledAppsSource
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

class ProcessInstalledApps(
    private val appDao: AppDao,
    private val ownExecutable: String?,
    private val scope: CoroutineScope
) : InstalledAppsSource {

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    override val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()

    private val refreshMutex = Mutex()
    private val systemRoots = listOfNotNull(System.getenv("SystemRoot"), "/usr/", "/lib", "/sbin", "/bin")

    init {
        scope.launch {
            while (true) {
                refresh()
                delay(REFRESH_INTERVAL)
            }
        }
    }

    override fun retry() {
        _isLoading.value = true
        scope.launch { refresh() }
    }

    private suspend fun refresh() = refreshMutex.withLock {
        try {
            val running = runningExecutables()
            val saved = appDao.getAllAppsSnapshot()
            val savedNames = saved.mapTo(HashSet()) { it.packageName }
            val discovered = running.filter { it.packageName !in savedNames }
            if (discovered.isNotEmpty()) {
                appDao.upsertApps(discovered.map { App(it.packageName, it.name, it.isSystemApp, ConnectionType.DIRECT) })
            }
            val runningNames = running.mapTo(HashSet()) { it.packageName }
            val remembered = saved
                .filter { it.packageName !in runningNames && it.packageName != ownExecutable }
                .map { InstalledApp(it.packageName, it.name, it.isSystemApp, isLaunchable = true) }
            _installedApps.value = (running + remembered).sortedBy { it.name.lowercase() }
            _error.value = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _error.value = error.message ?: "Failed to list running programs"
        } finally {
            _isLoading.value = false
        }
    }

    private fun runningExecutables(): List<InstalledApp> =
        ProcessHandle.allProcesses()
            .map { it.info().command().orElse(null) }
            .toList()
            .filterNotNull()
            .map(::File)
            .filter { it.name.isNotBlank() && it.name != ownExecutable }
            .distinctBy { it.name }
            .map { file ->
                InstalledApp(
                    packageName = file.name,
                    name = file.nameWithoutExtension,
                    isSystemApp = systemRoots.any { file.path.startsWith(it, ignoreCase = true) },
                    isLaunchable = true
                )
            }

    private companion object {
        val REFRESH_INTERVAL = 15.seconds
    }
}

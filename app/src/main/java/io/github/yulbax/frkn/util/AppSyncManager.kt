package io.github.yulbax.frkn.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import io.github.yulbax.frkn.data.App
import io.github.yulbax.frkn.data.AppDao
import io.github.yulbax.frkn.data.ConnectionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

@Single(createdAtStart = true)
class AppSyncManager(
    private val context: Context,
    private val appDao: AppDao
) {
    init {
        registerReceiver()
        CoroutineScope(Dispatchers.IO).launch {
            fullSync()
        }
    }
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val packageName = intent.data?.schemeSpecificPart ?: return
            val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            if (replacing) return

            when (intent.action) {
                Intent.ACTION_PACKAGE_ADDED -> {
                    CoroutineScope(Dispatchers.IO).launch {
                        handleNewPackage(packageName)
                    }
                }
                Intent.ACTION_PACKAGE_FULLY_REMOVED -> {
                    CoroutineScope(Dispatchers.IO).launch {
                        appDao.deleteApp(packageName)
                    }
                }
            }
        }
    }

    fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addDataScheme("package")
        }
        context.registerReceiver(packageReceiver, filter)
    }

    suspend fun fullSync() = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val installed = packageManager.getInstalledApplications(0)

        val appsData = coroutineScope {
            installed.map { info ->
                async {
                    val name = runCatching {
                        packageManager.getApplicationLabel(info).toString()
                    }.getOrDefault(info.packageName)
                    val isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                    App(
                        packageName = info.packageName,
                        name = name,
                        isSystemApp = isSystemApp,
                        connectionType = getDefaultConnectionType(info.packageName)
                    )
                }
            }.awaitAll()
        }.filter { it.packageName != context.packageName }

        val installedPackages = appsData.mapTo(HashSet()) { it.packageName }
        val existingPackages = appDao.getAllApps().first().mapTo(HashSet()) { it.packageName }

        val orphaned = existingPackages.filter { it !in installedPackages }
        if (orphaned.isNotEmpty()) appDao.deleteApps(orphaned)

        val newApps = appsData.filter { it.packageName !in existingPackages }
        if (newApps.isNotEmpty()) appDao.upsertApps(newApps)
    }

    private suspend fun handleNewPackage(packageName: String) {
        val packageManager = context.packageManager
        try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            val name = packageManager.getApplicationLabel(info).toString()
            val isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            appDao.upsertApp(App(
                packageName = packageName,
                name = name,
                isSystemApp = isSystemApp,
                connectionType = getDefaultConnectionType(packageName)
            ))
        } catch (_: Exception) {}
    }

    private fun getDefaultConnectionType(packageName: String): ConnectionType {
        return if (packageName.split('.').any { it.equals("ru", ignoreCase = true) }) {
            ConnectionType.DIRECT
        } else {
            ConnectionType.VPN
        }
    }
}

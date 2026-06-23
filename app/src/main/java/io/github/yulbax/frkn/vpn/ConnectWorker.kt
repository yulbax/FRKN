package io.github.yulbax.frkn.vpn

import android.app.NotificationManager
import android.content.Context
import android.net.VpnService
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.yulbax.frkn.R
import io.github.yulbax.frkn.data.AppDatabase
import io.github.yulbax.frkn.data.SettingsEntity
import io.github.yulbax.frkn.util.FrknLog
import io.github.yulbax.frkn.util.createNotificationChannel
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ConnectWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val database: AppDatabase by inject()
    private val log: FrknLog by inject()

    override suspend fun doWork(): Result {
        val settings = database.settingsDao().observeSettings().first() ?: SettingsEntity()
        val consentGranted = VpnService.prepare(applicationContext) == null
        val serverSelected = database.profileDao().getSelected() != null
        if (settings.autoConnect && serverSelected && consentGranted) {
            log.i(TAG, "autostart: starting VPN")
            runCatching { FrknVpnService.start(applicationContext) }
                .onFailure { log.e(TAG, "autostart: start failed", it) }
        } else {
            log.i(
                TAG,
                "autostart skipped: autoConnect=${settings.autoConnect} " +
                    "serverSelected=$serverSelected consent=$consentGranted"
            )
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        applicationContext.createNotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.autostart_channel),
            NotificationManager.IMPORTANCE_MIN
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(applicationContext.getString(R.string.autostart_connecting))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val TAG = "Autostart"
        const val CHANNEL_ID = "frkn_autostart"
        const val NOTIFICATION_ID = 2
    }
}

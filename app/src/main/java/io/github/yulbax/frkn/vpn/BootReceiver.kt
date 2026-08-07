package io.github.yulbax.frkn.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import io.github.yulbax.frkn.data.AppDatabase
import io.github.yulbax.frkn.data.SettingsEntity
import io.github.yulbax.frkn.util.FrknLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.milliseconds

class BootReceiver : BroadcastReceiver(), KoinComponent {
    private val database: AppDatabase by inject()
    private val log: FrknLog by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(RECEIVER_TIMEOUT_MS.milliseconds) {
                    val settings = database.settingsDao().observeSettings().first() ?: SettingsEntity()
                    val serverSelected = database.profileDao().getSelected() != null
                    val consentGranted = VpnService.prepare(context) == null
                    if (settings.autoConnect && serverSelected && consentGranted) {
                        log.i(TAG, "boot received: starting VPN")
                        FrknVpnService.start(context)
                    } else {
                        log.i(
                            TAG,
                            "autostart skipped: autoConnect=${settings.autoConnect} " +
                                "serverSelected=$serverSelected consent=$consentGranted"
                        )
                    }
                }
            } catch (error: CancellationException) {
                log.w(TAG, "autostart timed out", error)
            } catch (error: Exception) {
                log.e(TAG, "autostart failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "Autostart"
        const val RECEIVER_TIMEOUT_MS = 8_000L
    }
}

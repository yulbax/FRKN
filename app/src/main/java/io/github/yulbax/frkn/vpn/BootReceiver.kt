package io.github.yulbax.frkn.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.yulbax.frkn.util.FrknLog
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class BootReceiver : BroadcastReceiver(), KoinComponent {
    private val log: FrknLog by inject()

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                log.i("Autostart", "boot received (${intent.action}) — enqueuing auto-connect")
                AutoConnect.enqueue(context)
            }
        }
    }
}

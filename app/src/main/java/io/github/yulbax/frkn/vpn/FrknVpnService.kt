package io.github.yulbax.frkn.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.yulbax.frkn.BuildConfig
import io.github.yulbax.frkn.util.FrknLog
import io.github.yulbax.frkn.vpn.core.ConnectionOwnerInfo
import io.github.yulbax.frkn.vpn.core.TunConfig
import io.github.yulbax.frkn.vpn.core.TunPlatform
import io.github.yulbax.frkn.vpn.singbox.SingBoxEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.InetSocketAddress

@SuppressLint("VpnServicePolicy")
class FrknVpnService :
    VpnService(),
    TunPlatform,
    VpnHost,
    KoinComponent {

    private val store: VpnSessionStore by inject()
    private val vpnStateRepository: VpnStateRepository by inject()
    private val commandBus: VpnCommandBus by inject()
    private val networkMonitor: DefaultNetworkMonitor by inject()
    private val frknLog: FrknLog by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var controller: VpnController
    private lateinit var tunSession: TunSession
    private lateinit var notification: VpnNotificationController
    private var notificationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        tunSession = TunSession(this, networkMonitor, frknLog)
        notification = VpnNotificationController(this, vpnStateRepository.stats)
        controller = VpnController(scope, commandBus, vpnStateRepository, host = this, log = frknLog) { controller ->
            VpnSession(
                engine = SingBoxEngine(applicationContext, this, networkMonitor, controller, frknLog),
                byeDpi = ByeDpiRunner(packageName, frknLog) { fd -> protect(fd) },
                store = store,
                stateRepository = vpnStateRepository,
                health = HealthMonitor(
                    vpnStateRepository,
                    frknLog,
                    SocksHealthProbe(BuildConfig.VPN_HEALTH_PROBE_URL, BuildConfig.BYEDPI_HEALTH_PROBE_URL)
                ),
                quality = SocksSiteProbe,
                log = frknLog,
                ownPackage = packageName,
                scope = scope,
                requestWork = controller::requestWork,
                onByeDpiExit = controller::onByeDpiExit
            )
        }
        networkMonitor.onUnderlyingNetworkChanged = { network ->
            runCatching { setUnderlyingNetworks(network?.let { arrayOf(it) }) }
        }
        controller.launch()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            controller.stop(startId, force = false)
        } else {
            notification.startForeground()
            controller.start(startId, systemInitiated = intent?.action != ACTION_START)
        }
        return START_NOT_STICKY
    }

    override fun onSessionStarting() {
        notification.startForeground()
    }

    override fun onSessionStarted() {
        notificationJob = notification.observe(scope)
    }

    override fun allowsUserStop(): Boolean = !isAlwaysOn

    override fun onSessionStopped(stopToken: Int?, stopHost: Boolean) {
        runCatching { notificationJob?.cancel() }
        notificationJob = null
        runCatching { tunSession.close() }
            .onFailure { frknLog.w(TAG, "failed to close TUN", it) }
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }.onFailure { frknLog.w(TAG, "failed to remove foreground notification", it) }
        if (stopHost) {
            runCatching {
                if (stopToken != null) stopSelfResult(stopToken) else stopSelf()
            }.onFailure { frknLog.w(TAG, "failed to stop VPN service", it) }
        }
    }

    override fun openTun(config: TunConfig): Int = tunSession.open(config)

    override fun protectFd(fd: Int): Boolean = protect(fd)

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwnerInfo = tunSession.findConnectionOwner(
        ipProtocol,
        InetSocketAddress(sourceAddress, sourcePort),
        InetSocketAddress(destinationAddress, destinationPort)
    )

    override fun onRevoke() {
        controller.stop(token = null, force = true)
    }

    override fun onDestroy() {
        networkMonitor.onUnderlyingNetworkChanged = null
        controller.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "io.github.yulbax.frkn.action.START"
        const val ACTION_STOP = "io.github.yulbax.frkn.action.STOP"

        private const val TAG = "FrknVpnService"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, FrknVpnService::class.java).setAction(ACTION_START)
            )
        }
    }
}

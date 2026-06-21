package io.github.yulbax.frkn.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.yulbax.frkn.data.App
import io.github.yulbax.frkn.data.AppDatabase
import io.github.yulbax.frkn.data.ConnectionType
import io.github.yulbax.frkn.util.SubscriptionFetcher
import io.github.yulbax.frkn.data.SettingsEntity
import io.github.yulbax.frkn.data.profile.ProfileEntity
import io.github.yulbax.frkn.engine.ByeDpi
import io.github.yulbax.frkn.vpn.core.ConnectionOwnerInfo
import io.github.yulbax.frkn.vpn.core.EngineConfig
import io.github.yulbax.frkn.vpn.core.EngineListener
import io.github.yulbax.frkn.vpn.core.EngineProxy
import io.github.yulbax.frkn.vpn.core.Ipv6Mode
import io.github.yulbax.frkn.vpn.core.NetworkOptions
import io.github.yulbax.frkn.vpn.core.TlsFingerprint
import io.github.yulbax.frkn.vpn.core.TunConfig
import io.github.yulbax.frkn.vpn.core.TunStack
import io.github.yulbax.frkn.vpn.core.TunPlatform
import io.github.yulbax.frkn.vpn.core.VpnEngine
import io.github.yulbax.frkn.vpn.core.freeLoopbackPort
import io.github.yulbax.frkn.vpn.singbox.SingBoxEngine
import io.github.yulbax.frkn.util.FrknLog
import io.github.yulbax.frkn.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.InetSocketAddress
import kotlin.time.Duration.Companion.milliseconds

private enum class Lifecycle { Idle, Starting, Running }

@SuppressLint("VpnServicePolicy")
class FrknVpnService :
    VpnService(),
    TunPlatform,
    EngineListener,
    KoinComponent {

    private val database: AppDatabase by inject()
    private val vpnStateRepository: VpnStateRepository by inject()
    private val commandBus: VpnCommandBus by inject()
    private val networkMonitor: DefaultNetworkMonitor by inject()
    private val frknLog: FrknLog by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var engine: VpnEngine
    private var tunInterface: ParcelFileDescriptor? = null
    private var tunSignature: String? = null
    private var byeDpi: ByeDpi? = null
    private var socketProtector: SocketProtector? = null

    @Volatile private var lifecycle = Lifecycle.Idle

    private var routeWatchJob: Job? = null
    private var notificationJob: Job? = null
    private var byeDpiArgs: List<String> = ByeDpi.DEFAULT_DESYNC_ARGS

    private var activeConfigName: String = ""
    private var byeDpiPort: Int = ByeDpi.DEFAULT_PORT
    private var appliedMembershipKey = ""
    private var appliedRoutingKey = ""
    private var appliedSelectedTag = ""

    private lateinit var notification: VpnNotificationController
    private lateinit var health: HealthMonitor

    override fun onCreate() {
        super.onCreate()
        engine = SingBoxEngine(applicationContext, this, networkMonitor, this, frknLog)
        notification = VpnNotificationController(this, vpnStateRepository.stats)
        health = HealthMonitor(vpnStateRepository)
        commandLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> requestStart()
            ACTION_STOP -> commandBus.stop()
        }
        return START_NOT_STICKY
    }

    private fun commandLoop() {
        scope.launch {
            commandBus.commands.collect { command ->
                when (command) {
                    Command.Start -> doStart()
                    Command.Reload -> doReload()
                    Command.Recover -> doRecover()
                    Command.CheckByeDpi -> doCheckByeDpi()
                    Command.Stop -> doStop()
                }
            }
        }
    }

    private fun requestStart() {
        if (lifecycle != Lifecycle.Idle) return
        lifecycle = Lifecycle.Starting
        frknLog.i(TAG, "start requested")
        vpnStateRepository.update(VpnState.Connecting)
        notification.startForeground()
        commandBus.start()
    }

    private suspend fun doStart() {
        if (lifecycle != Lifecycle.Starting) return
        try {
            val db = database
            byeDpiArgs = ByeDpi.parseArgs(
                db.settingsDao().observeSettings().first()?.byeDpiArgs ?: ""
            )
            byeDpiPort = freeLoopbackPort()
            val config = composeEngineConfig(db)
            frknLog.i(
                TAG,
                "config: server='${activeConfigName}' profiles=${config.proxies.size} " +
                    "vpnApps=${config.vpnPackages.size} byedpiApps=${config.byeDpiPackages.size} " +
                    "byedpiPort=$byeDpiPort"
            )
            reconcileByeDpi(needed = config.byeDpiPackages.isNotEmpty())
            networkMonitor.start()
            engine.start(config)
            lifecycle = Lifecycle.Running
            frknLog.i(TAG, "engine started")
            vpnStateRepository.update(VpnState.Verifying)
            startRouteWatch(db)
            startHealth(db)
            notificationJob = notification.observe(scope)
        } catch (t: Throwable) {
            frknLog.e(TAG, "start failed", t)
            Telemetry.recordNonFatal(t, "VPN start failed: server=$activeConfigName")
            vpnStateRepository.update(VpnState.Error(t.message ?: "Failed to start VPN"))
            doStop()
        }
    }

    private suspend fun composeEngineConfig(db: AppDatabase): EngineConfig {
        val selectedProfile = db.profileDao().getSelected()
            ?: throw IllegalStateException("No server selected")
        val profiles = db.profileDao().observeAll().first()
        setActiveConfigName(selectedProfile.name)

        val settings = db.settingsDao().observeSettings().first() ?: SettingsEntity()
        val apps = db.appDao().getAllApps().first().filterNot { it.packageName == packageName }
        val byeDpiPackages = apps.filter { it.connectionType == ConnectionType.BYEDPI }
            .map { it.packageName }
        val vpnPackages = apps.filter { it.connectionType == ConnectionType.VPN }
            .map { it.packageName }
        val tunneledPackages = byeDpiPackages + vpnPackages
        if (tunneledPackages.isEmpty()) error("No apps assigned to VPN or ByeDPI")

        val selectedTag = proxyTag(selectedProfile.id)
        appliedMembershipKey = membershipKey(profiles)
        appliedRoutingKey = routingKey(apps)
        appliedSelectedTag = selectedTag

        return EngineConfig(
            proxies = profiles.map { EngineProxy(proxyTag(it.id), it.outboundJson) },
            activeProxyTag = selectedTag,
            byeDpiPackages = byeDpiPackages,
            vpnPackages = vpnPackages,
            tunneledPackages = tunneledPackages,
            byeDpiSocksPort = byeDpiPort,
            network = NetworkOptions(
                tunStack = TunStack.fromWire(settings.tunStack),
                mtu = settings.mtu,
                ipv6Mode = Ipv6Mode.fromWire(settings.ipv6Mode),
                dnsRemote = settings.dnsRemote,
                dnsDirect = settings.dnsDirect,
                sniff = settings.sniff,
                bypassLan = settings.bypassLan,
                preferredFingerprint = TlsFingerprint.fromWire(settings.preferredFingerprint)
            )
        )
    }

    private fun setActiveConfigName(name: String) {
        activeConfigName = name.ifBlank { "(unnamed)" }
        vpnStateRepository.updateStats { it.copy(configName = activeConfigName) }
    }

    private fun proxyTag(id: Long): String = "p$id"

    private fun membershipKey(profiles: List<ProfileEntity>): String =
        profiles.joinToString("|") { "${it.id}:${it.outboundJson.hashCode()}" }

    private fun routingKey(apps: List<App>): String =
        apps.sortedBy { it.packageName }.joinToString("|") { "${it.packageName}=${it.connectionType}" }

    private fun reconcileByeDpi(needed: Boolean): Boolean = when {
        needed && byeDpi == null -> {
            val protectName =
                "$packageName.byedpi-protect.${SystemClock.elapsedRealtimeNanos()}"
            socketProtector = SocketProtector(protectName) { fd -> protect(fd) }
                .also { it.start() }
            byeDpi = ByeDpi(
                port = byeDpiPort,
                protectPath = protectName,
                extraArgs = byeDpiArgs
            ).also { it.start() }
            frknLog.i(TAG, "byedpi started on port $byeDpiPort args=$byeDpiArgs")
            vpnStateRepository.updateStats { it.copy(byeDpiPort = byeDpiPort) }
            true
        }
        !needed && byeDpi != null -> {
            val oldByeDpi = byeDpi
            val oldProtector = socketProtector
            byeDpi = null
            socketProtector = null
            vpnStateRepository.updateStats { it.copy(byeDpiPort = 0) }
            scope.launch {
                runCatching { oldByeDpi?.stop() }
                runCatching { oldProtector?.stop() }
            }
            true
        }
        else -> false
    }

    @OptIn(FlowPreview::class)
    private fun startRouteWatch(db: AppDatabase) {
        routeWatchJob?.cancel()
        routeWatchJob = scope.launch {
            combine(
                db.profileDao().observeAll(),
                db.profileDao().observeSelected(),
                db.appDao().getAllApps()
            ) { profiles, selected, apps ->
                Triple(profiles, selected, apps)
            }
                .distinctUntilChanged()
                .debounce(300.milliseconds)
                .collect { commandBus.reload() }
        }
    }

    private suspend fun doReload() {
        if (lifecycle != Lifecycle.Running) return
        val db = database
        val selected = db.profileDao().getSelected()
        if (selected == null) {
            frknLog.i(TAG, "selected server removed — stopping")
            doStop()
            return
        }
        val profiles = db.profileDao().observeAll().first()
        val apps = db.appDao().getAllApps().first().filterNot { it.packageName == packageName }
        val selectedTag = proxyTag(selected.id)
        val structuralChange = membershipKey(profiles) != appliedMembershipKey ||
            routingKey(apps) != appliedRoutingKey
        val serverSwitched = selectedTag != appliedSelectedTag
        if (!structuralChange && !serverSwitched) return

        if (serverSwitched) {
            setActiveConfigName(selected.name)
            vpnStateRepository.update(VpnState.Verifying)
        }
        var byeDpiToggled = false
        if (!structuralChange && engine.selectProxy(selectedTag)) {
            appliedSelectedTag = selectedTag
        } else {
            val config = composeEngineConfig(db)
            byeDpiToggled = reconcileByeDpi(needed = config.byeDpiPackages.isNotEmpty())
            runCatching { engine.reloadRouting(config) }
                .onFailure { frknLog.e(TAG, "live config reload failed", it) }
        }
        if (serverSwitched || byeDpiToggled) startHealth(db)
    }

    private suspend fun doRecover() {
        if (lifecycle != Lifecycle.Running) return
        frknLog.i(TAG, "both channels down — reloading service")
        val config = composeEngineConfig(database)
        reconcileByeDpi(needed = config.byeDpiPackages.isNotEmpty())
        runCatching { engine.reloadRouting(config) }
            .onFailure { frknLog.e(TAG, "recovery reload failed", it) }
    }

    private fun startHealth(db: AppDatabase) {
        health.start(
            scope = scope,
            engine = engine,
            byeDpiPort = byeDpiPort.takeIf { byeDpi != null },
            onRefreshSubscription = { refreshSubscription(db) },
            onRecoveryReload = { commandBus.recover() },
            onByedpiUp = { commandBus.checkByeDpi() }
        )
    }

    private fun doCheckByeDpi() {
        if (lifecycle != Lifecycle.Running || byeDpi == null) return
        if (vpnStateRepository.stats.value.byedpiChecking) return
        val port = byeDpiPort
        scope.launch {
            vpnStateRepository.updateStats { it.copy(byedpiChecking = true) }
            val reachable = runCatching { ByeDpiQuality.reachableCount(port) }.getOrDefault(0)
            vpnStateRepository.updateStats {
                it.copy(byedpiChecking = false, byedpiReachable = reachable, byedpiTotal = ByeDpiQuality.quickTotal)
            }
        }
    }

    private suspend fun refreshSubscription(db: AppDatabase) {
        val selected = db.profileDao().getSelected() ?: return
        val subUrl = selected.subscriptionUrl
        if (subUrl.isBlank()) return
        frknLog.i(TAG, "reconnect threshold reached — refreshing subscription '${selected.name}'")
        val parsedList = runCatching { SubscriptionFetcher.fetch(subUrl) }
            .onFailure { frknLog.e(TAG, "subscription refresh failed", it) }
            .getOrNull()
            ?: return
        if (parsedList.isEmpty()) {
            frknLog.i(TAG, "subscription refresh returned no servers")
            return
        }
        val fresh = parsedList.firstOrNull { it.name == selected.name } ?: parsedList.first()
        val freshOutbound = Json.encodeToString(JsonObject.serializer(), fresh.outbound)
        if (freshOutbound == selected.outboundJson) {
            frknLog.i(TAG, "subscription refresh: outbound unchanged")
            return
        }
        db.profileDao().updateConfig(
            id = selected.id,
            name = selected.name,
            type = fresh.type,
            link = fresh.link,
            outboundJson = freshOutbound
        )
        frknLog.i(TAG, "subscription refreshed — engine will reload with new outbound")
    }

    private fun doStop() {
        if (lifecycle == Lifecycle.Idle) return
        frknLog.i(TAG, "stop")
        lifecycle = Lifecycle.Idle
        routeWatchJob?.cancel()
        routeWatchJob = null
        health.stop()
        notificationJob?.cancel()
        notificationJob = null
        releaseEngineResources()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        vpnStateRepository.update(VpnState.Disconnected)
        vpnStateRepository.resetStats()
    }

    private fun releaseEngineResources() {
        runCatching { engine.stop() }
        runCatching { networkMonitor.stop() }
        runCatching { byeDpi?.stop() }
        byeDpi = null
        runCatching { socketProtector?.stop() }
        socketProtector = null
        tunInterface?.runCatching { close() }
        tunInterface = null
        tunSignature = null
    }

    override fun onThroughput(uplinkBytesPerSec: Long, downlinkBytesPerSec: Long) {
        vpnStateRepository.updateStats {
            it.copy(uplink = uplinkBytesPerSec, downlink = downlinkBytesPerSec)
        }
    }

    override fun onStopRequested() {
        commandBus.stop()
    }

    override fun openTun(config: TunConfig): Int {
        if (prepare(this) != null) error("android: missing vpn permission")

        val signature = listOf(
            config.mtu.toString(),
            config.inet4.joinToString(",") { "${it.address}/${it.prefix}" },
            config.inet6.joinToString(",") { "${it.address}/${it.prefix}" },
            config.autoRoute.toString(),
            config.dnsServers.sorted().joinToString(","),
            config.includePackages.sorted().joinToString(","),
            config.excludePackages.sorted().joinToString(",")
        ).joinToString("|")

        val existing = tunInterface
        if (existing != null && signature == tunSignature) {
            return existing.fd
        }

        val builder = Builder().setSession("FRKN").setMtu(config.mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        config.inet4.forEach { builder.addAddress(it.address, it.prefix) }
        config.inet6.forEach { builder.addAddress(it.address, it.prefix) }
        if (config.autoRoute) {
            config.dnsServers.forEach { builder.addDnsServer(it) }
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
            config.includePackages.forEach { runCatching { builder.addAllowedApplication(it) } }
            config.excludePackages.forEach {
                try {
                    builder.addDisallowedApplication(it)
                } catch (e: NameNotFoundException) {
                    Log.w(TAG, "exclude package not found", e)
                }
            }
        }

        val pfd = builder.establish() ?: error("android: establish() failed")
        frknLog.i(TAG, "tun established mtu=${config.mtu} include=${config.includePackages.size} exclude=${config.excludePackages.size}")
        tunInterface?.runCatching { close() }
        tunInterface = pfd
        tunSignature = signature
        networkMonitor.currentNetwork()?.let {
            runCatching { setUnderlyingNetworks(arrayOf(it)) }
        }
        return pfd.fd
    }

    override fun protectFd(fd: Int): Boolean = protect(fd)

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwnerInfo {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) error("unsupported")
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val uid = connectivity.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort)
        )
        if (uid == Process.INVALID_UID) error("connection owner not found")
        val packages = packageManager.getPackagesForUid(uid)
        return ConnectionOwnerInfo(uid, packages?.toList() ?: emptyList())
    }

    override fun onRevoke() {
        commandBus.stop()
    }

    override fun onDestroy() {
        routeWatchJob?.cancel()
        health.stop()
        notificationJob?.cancel()
        releaseEngineResources()
        scope.cancel()
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

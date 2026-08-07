package io.github.yulbax.frkn.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.yulbax.frkn.data.App
import io.github.yulbax.frkn.data.AppDatabase
import io.github.yulbax.frkn.data.ConnectionType
import io.github.yulbax.frkn.data.SettingsEntity
import io.github.yulbax.frkn.data.profile.ProfileEntity
import io.github.yulbax.frkn.data.profile.ProfileOperationResult
import io.github.yulbax.frkn.data.profile.ProfileRepository
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.InetSocketAddress
import kotlin.time.Duration.Companion.milliseconds

private enum class Lifecycle { Idle, Starting, Running, Stopping }

private sealed interface ActorCommand {
    data class Start(val startId: Int?, val systemInitiated: Boolean) : ActorCommand
    data class Stop(val startId: Int?, val force: Boolean) : ActorCommand
    data class ByeDpiExited(val instance: ByeDpi, val code: Int) : ActorCommand
    data class Work(val command: Command, val generation: Long) : ActorCommand
}

private data class AppliedConfig(
    val engineConfig: EngineConfig,
    val configName: String,
    val membershipKey: String,
    val routingKey: String,
    val selectedTag: String,
    val vpnActive: Boolean
)

@SuppressLint("VpnServicePolicy")
class FrknVpnService :
    VpnService(),
    TunPlatform,
    EngineListener,
    KoinComponent {

    private val database: AppDatabase by inject()
    private val profileRepository: ProfileRepository by inject()
    private val vpnStateRepository: VpnStateRepository by inject()
    private val commandBus: VpnCommandBus by inject()
    private val networkMonitor: DefaultNetworkMonitor by inject()
    private val frknLog: FrknLog by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controlCommands = Channel<ActorCommand>(Channel.BUFFERED)
    private val workCommands = Channel<ActorCommand.Work>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private lateinit var engine: VpnEngine
    private var tunInterface: ParcelFileDescriptor? = null
    private var tunSignature: String? = null
    private var byeDpi: ByeDpi? = null
    private var socketProtector: SocketProtector? = null

    @Volatile private var lifecycle = Lifecycle.Idle

    private var routeWatchJob: Job? = null
    private var actorJob: Job? = null
    private var notificationJob: Job? = null
    private var byeDpiCheckJob: Job? = null
    private var byeDpiArgs: List<String> = ByeDpi.DEFAULT_DESYNC_ARGS

    private var byeDpiPort: Int = ByeDpi.DEFAULT_PORT
    private var appliedConfig: AppliedConfig? = null
    private var latestStartId: Int? = null
    @Volatile private var sessionGeneration = 0L
    private var byeDpiStartedAt = 0L
    private var rapidByeDpiExits = 0

    private lateinit var notification: VpnNotificationController
    private lateinit var health: HealthMonitor

    override fun onCreate() {
        super.onCreate()
        engine = SingBoxEngine(applicationContext, this, networkMonitor, this, frknLog)
        notification = VpnNotificationController(this, vpnStateRepository.stats)
        health = HealthMonitor(vpnStateRepository, frknLog)
        networkMonitor.onUnderlyingNetworkChanged = { network ->
            runCatching { setUnderlyingNetworks(network?.let { arrayOf(it) }) }
        }
        commandLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            enqueueControl(ActorCommand.Stop(startId, force = false))
        } else {
            notification.startForeground()
            enqueueControl(
                ActorCommand.Start(
                    startId = startId,
                    systemInitiated = intent?.action != ACTION_START
                )
            )
        }
        return START_NOT_STICKY
    }

    private fun commandLoop() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            commandBus.commands.collect { command ->
                when (command) {
                    Command.Start -> enqueueControl(ActorCommand.Start(null, false))
                    Command.Stop -> enqueueControl(ActorCommand.Stop(null, force = false))
                    else -> enqueueWork(command)
                }
            }
        }
        actorJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { actorLoop() }
    }

    private fun enqueueControl(command: ActorCommand) {
        if (controlCommands.trySend(command).isFailure) {
            frknLog.e(TAG, "control command queue is unavailable: $command")
        }
    }

    private fun enqueueWork(command: Command) {
        val work = ActorCommand.Work(command, sessionGeneration)
        if (workCommands.trySend(work).isFailure) {
            frknLog.w(TAG, "work command queue is unavailable: $command")
        }
    }

    private suspend fun actorLoop() {
        while (true) {
            val command = controlCommands.tryReceive().getOrNull() ?: select<ActorCommand?> {
                controlCommands.onReceiveCatching { it.getOrNull() }
                workCommands.onReceiveCatching { it.getOrNull() }
            } ?: return
            try {
                when (command) {
                    is ActorCommand.Start -> {
                        command.startId?.let { latestStartId = it }
                        doStart(command.systemInitiated)
                    }
                    is ActorCommand.Stop -> {
                        command.startId?.let { latestStartId = it }
                        doStop(command)
                    }
                    is ActorCommand.ByeDpiExited -> handleByeDpiExit(command)
                    is ActorCommand.Work -> {
                        if (command.generation != sessionGeneration) continue
                        when (command.command) {
                            Command.Reload -> doReload()
                            Command.Recover -> doRecover()
                            Command.CheckByeDpi -> doCheckByeDpi()
                            Command.TestProxies -> doTestProxies()
                            Command.Start, Command.Stop -> Unit
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                handleCommandFailure(command, t)
            }
        }
    }

    private fun handleCommandFailure(command: ActorCommand, error: Throwable) {
        val operation = when (command) {
            is ActorCommand.Start -> "start"
            is ActorCommand.Stop -> "stop"
            is ActorCommand.ByeDpiExited -> "ByeDPI restart"
            is ActorCommand.Work -> command.command.toString()
        }
        frknLog.e(TAG, "$operation failed", error)
        runCatching {
            Telemetry.recordNonFatal(
                error,
                "VPN $operation failed: server=${appliedConfig?.configName.orEmpty()}"
            )
        }
        val nonFatalWork = command is ActorCommand.Work &&
            (command.command == Command.CheckByeDpi || command.command == Command.TestProxies)
        if (!nonFatalWork) {
            runCatching {
                stopInternal(
                    finalState = VpnState.Error(error.message ?: "VPN $operation failed"),
                    stopStartId = latestStartId,
                    stopService = true
                )
            }.onFailure { cleanupError ->
                lifecycle = Lifecycle.Idle
                frknLog.e(TAG, "cleanup after $operation failed", cleanupError)
            }
        }
    }

    private suspend fun doStart(systemInitiated: Boolean) {
        if (lifecycle != Lifecycle.Idle) return
        notification.startForeground()
        lifecycle = Lifecycle.Starting
        sessionGeneration++
        rapidByeDpiExits = 0
        frknLog.i(TAG, "start requested system=$systemInitiated")
        vpnStateRepository.update(VpnState.Connecting)
        appliedConfig = null
        val db = database
        byeDpiArgs = ByeDpi.parseArgs(
            db.settingsDao().observeSettings().first()?.byeDpiArgs ?: ""
        )
        byeDpiPort = freeLoopbackPort()
        val config = composeEngineConfig(db)
        frknLog.i(
            TAG,
            "config: server='${config.configName}' profiles=${config.engineConfig.proxies.size} " +
                "vpnApps=${config.engineConfig.vpnPackages.size} " +
                "byedpiApps=${config.engineConfig.byeDpiPackages.size} " +
                "byedpiPort=$byeDpiPort"
        )
        if (config.engineConfig.byeDpiPackages.isNotEmpty()) startByeDpi()
        networkMonitor.start()
        engine.start(config.engineConfig)
        commitConfig(config)
        lifecycle = Lifecycle.Running
        frknLog.i(TAG, "engine started")
        vpnStateRepository.update(VpnState.Verifying)
        startRouteWatch(db)
        startHealth(db)
        notificationJob = notification.observe(scope)
    }

    private suspend fun composeEngineConfig(db: AppDatabase): AppliedConfig {
        val selectedProfile = db.profileDao().getSelected()
            ?: throw IllegalStateException("No server selected")
        val profiles = db.profileDao().observeAll().first()

        val settings = db.settingsDao().observeSettings().first() ?: SettingsEntity()
        val apps = db.appDao().getAllApps().first().filterNot { it.packageName == packageName }
        val byeDpiPackages = apps.filter { it.connectionType == ConnectionType.BYEDPI }
            .map { it.packageName }
        val vpnPackages = apps.filter { it.connectionType == ConnectionType.VPN }
            .map { it.packageName }
        val tunneledPackages = byeDpiPackages + vpnPackages
        if (tunneledPackages.isEmpty()) error("No apps assigned to VPN or ByeDPI")

        val selectedTag = proxyTag(selectedProfile.id)
        return AppliedConfig(
            engineConfig = EngineConfig(
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
            ),
            configName = selectedProfile.name.ifBlank { "(unnamed)" },
            membershipKey = membershipKey(profiles),
            routingKey = routingKey(apps),
            selectedTag = selectedTag,
            vpnActive = vpnPackages.isNotEmpty()
        )
    }

    private fun commitConfig(config: AppliedConfig) {
        appliedConfig = config
        vpnStateRepository.updateStats {
            it.copy(
                configName = config.configName,
                byeDpiPort = byeDpiPort.takeIf { config.engineConfig.byeDpiPackages.isNotEmpty() }
                    ?: 0
            )
        }
    }

    private fun proxyTag(id: Long): String = "p$id"

    private fun membershipKey(profiles: List<ProfileEntity>): String =
        profiles.joinToString("|") { "${it.id}:${it.name}:${it.outboundJson}" }

    private fun routingKey(apps: List<App>): String =
        apps.sortedBy { it.packageName }.joinToString("|") { "${it.packageName}=${it.connectionType}" }

    private fun startByeDpi() {
        if (byeDpi != null) return
        val protectName = "$packageName.byedpi-protect.${SystemClock.elapsedRealtimeNanos()}"
        val protector = SocketProtector(protectName, frknLog) { fd -> protect(fd) }
        lateinit var process: ByeDpi
        process = ByeDpi(
            port = byeDpiPort,
            protectPath = protectName,
            extraArgs = byeDpiArgs,
            onUnexpectedExit = { code ->
                enqueueControl(ActorCommand.ByeDpiExited(process, code))
            }
        )
        try {
            protector.start()
            socketProtector = protector
            byeDpi = process
            byeDpiStartedAt = SystemClock.elapsedRealtime()
            process.start()
            frknLog.i(TAG, "byedpi started on port $byeDpiPort args=$byeDpiArgs")
            vpnStateRepository.updateStats { it.copy(byeDpiPort = byeDpiPort) }
        } catch (t: Throwable) {
            byeDpi = null
            socketProtector = null
            runCatching { process.stop() }
            runCatching { protector.stop() }
            throw t
        }
    }

    private fun stopByeDpi() {
        val process = byeDpi
        val protector = socketProtector
        byeDpi = null
        socketProtector = null
        vpnStateRepository.updateStats { it.copy(byeDpiPort = 0, byedpiChecking = false) }
        var failure: Throwable? = null
        try {
            process?.stop()
        } catch (t: Throwable) {
            failure = t
        }
        try {
            protector?.stop()
        } catch (t: Throwable) {
            if (failure == null) failure = t else failure.addSuppressed(t)
        }
        failure?.let { throw it }
    }

    private fun handleByeDpiExit(command: ActorCommand.ByeDpiExited) {
        if (byeDpi !== command.instance) return
        frknLog.w(TAG, "byedpi exited unexpectedly code=${command.code}")
        byeDpi = null
        val protector = socketProtector
        socketProtector = null
        runCatching { protector?.stop() }
        vpnStateRepository.updateStats { it.copy(byeDpiPort = 0, byedpiChecking = false) }

        val config = appliedConfig ?: return
        if (lifecycle != Lifecycle.Running || config.engineConfig.byeDpiPackages.isEmpty()) return
        val runtime = SystemClock.elapsedRealtime() - byeDpiStartedAt
        rapidByeDpiExits = if (runtime >= BYEDPI_STABLE_RUNTIME_MS) 1 else rapidByeDpiExits + 1
        check(rapidByeDpiExits <= MAX_RAPID_BYEDPI_EXITS) {
            "ByeDPI repeatedly exited with code ${command.code}"
        }
        startByeDpi()
        startHealth(database)
    }

    private fun applyReload(config: AppliedConfig) {
        val needed = config.engineConfig.byeDpiPackages.isNotEmpty()
        val startedForReload = needed && byeDpi == null
        if (startedForReload) startByeDpi()
        try {
            engine.reloadRouting(config.engineConfig)
        } catch (t: Throwable) {
            if (startedForReload) {
                runCatching { stopByeDpi() }.onFailure { t.addSuppressed(it) }
            }
            throw t
        }
        if (!needed && byeDpi != null) stopByeDpi()
        commitConfig(config)
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
                .collect { enqueueWork(Command.Reload) }
        }
    }

    private suspend fun doReload() {
        if (lifecycle != Lifecycle.Running) return
        val db = database
        val selected = db.profileDao().getSelected()
        if (selected == null) {
            frknLog.i(TAG, "selected server removed; stopping")
            stopInternal(VpnState.Disconnected, latestStartId, stopService = true)
            return
        }
        val current = checkNotNull(appliedConfig) { "VPN has no applied config" }
        val desired = composeEngineConfig(db)
        val structuralChange = desired.membershipKey != current.membershipKey ||
            desired.routingKey != current.routingKey
        val serverSwitched = desired.selectedTag != current.selectedTag
        if (!structuralChange && !serverSwitched) return

        vpnStateRepository.update(VpnState.Verifying)
        if (!structuralChange && engine.selectProxy(desired.selectedTag)) {
            commitConfig(desired)
        } else {
            applyReload(desired)
        }
        startHealth(db)
    }

    private suspend fun doRecover() {
        if (lifecycle != Lifecycle.Running) return
        frknLog.i(TAG, "both channels down; reloading service")
        val config = composeEngineConfig(database)
        applyReload(config)
        vpnStateRepository.update(VpnState.Verifying)
        startHealth(database)
    }

    private fun startHealth(db: AppDatabase) {
        val config = appliedConfig ?: return
        health.start(
            scope = scope,
            params = HealthParams(
                probe = ProbeParams(
                    engine = engine,
                    byeDpiPort = byeDpiPort.takeIf { byeDpi != null },
                    vpnActive = config.vpnActive,
                    isFingerprintError = { engine.hasFingerprintError() }
                ),
                onRefreshSubscription = { refreshSubscription(db) },
                onRecoveryReload = { enqueueWork(Command.Recover) },
                onByedpiUp = { enqueueWork(Command.CheckByeDpi) }
            )
        )
    }

    private fun doTestProxies() {
        if (lifecycle != Lifecycle.Running) return
        engine.testProxies()
    }

    private fun doCheckByeDpi() {
        if (lifecycle != Lifecycle.Running || byeDpi == null) return
        if (byeDpiCheckJob?.isActive == true) return
        val port = byeDpiPort
        val generation = sessionGeneration
        byeDpiCheckJob = scope.launch {
            vpnStateRepository.updateStats { it.copy(byedpiChecking = true) }
            try {
                val reachable = ByeDpiQuality.reachableCount(port)
                ensureActive()
                if (lifecycle == Lifecycle.Running && sessionGeneration == generation && byeDpi != null) {
                    vpnStateRepository.updateStats {
                        it.copy(
                            byedpiChecking = false,
                            byedpiReachable = reachable,
                            byedpiTotal = ByeDpiQuality.quickTotal
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                frknLog.w(TAG, "ByeDPI quality check failed", t)
                if (lifecycle == Lifecycle.Running && sessionGeneration == generation) {
                    vpnStateRepository.updateStats { it.copy(byedpiChecking = false) }
                }
            }
        }
    }

    private suspend fun refreshSubscription(db: AppDatabase) {
        val selected = db.profileDao().getSelected() ?: return
        if (selected.subscriptionUrl.isBlank()) return
        frknLog.i(TAG, "reconnect threshold reached; refreshing subscription '${selected.name}'")
        when (val result = profileRepository.refreshSubscription(selected)) {
            is ProfileOperationResult.Success ->
                frknLog.i(TAG, "subscription refreshed; engine will reload with new outbound")
            ProfileOperationResult.EmptySubscription ->
                frknLog.i(TAG, "subscription refresh returned no servers")
            is ProfileOperationResult.FetchFailed ->
                frknLog.e(TAG, "subscription refresh failed", result.cause)
            ProfileOperationResult.InvalidLink ->
                frknLog.w(TAG, "subscription refresh rejected an invalid link")
        }
    }

    private fun doStop(command: ActorCommand.Stop) {
        if (!command.force && lifecycle != Lifecycle.Idle && isAlwaysOn) {
            frknLog.i(TAG, "ignoring app stop while always-on VPN is enabled")
            return
        }
        stopInternal(
            finalState = VpnState.Disconnected,
            stopStartId = command.startId ?: latestStartId,
            stopService = true
        )
    }

    private fun stopInternal(finalState: VpnState, stopStartId: Int?, stopService: Boolean) {
        frknLog.i(TAG, "stop state=${finalState::class.simpleName}")
        lifecycle = Lifecycle.Stopping
        sessionGeneration++
        runCatching { routeWatchJob?.cancel() }
        routeWatchJob = null
        runCatching { health.stop() }
        runCatching { notificationJob?.cancel() }
        notificationJob = null
        runCatching { byeDpiCheckJob?.cancel() }
        byeDpiCheckJob = null
        while (workCommands.tryReceive().isSuccess) {
            // Drain work from the previous session before accepting a new start.
        }
        releaseEngineResources()
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }.onFailure { frknLog.w(TAG, "failed to remove foreground notification", it) }
        runCatching { vpnStateRepository.resetStats() }
        runCatching { vpnStateRepository.update(finalState) }
        lifecycle = Lifecycle.Idle
        if (stopService) {
            runCatching {
                if (stopStartId != null) stopSelfResult(stopStartId) else stopSelf()
            }.onFailure { frknLog.w(TAG, "failed to stop VPN service", it) }
        }
    }

    private fun releaseEngineResources() {
        runCatching { engine.stop() }
            .onFailure { frknLog.w(TAG, "failed to stop sing-box", it) }
        runCatching { networkMonitor.stop() }
            .onFailure { frknLog.w(TAG, "failed to stop network monitor", it) }
        runCatching { stopByeDpi() }
            .onFailure { frknLog.w(TAG, "failed to stop ByeDPI", it) }
        runCatching { tunInterface?.close() }
            .onFailure { frknLog.w(TAG, "failed to close TUN", it) }
        tunInterface = null
        tunSignature = null
        appliedConfig = null
    }

    override fun onThroughput(uplinkBytesPerSec: Long, downlinkBytesPerSec: Long) {
        if (lifecycle != Lifecycle.Running) return
        vpnStateRepository.updateStats {
            it.copy(uplink = uplinkBytesPerSec, downlink = downlinkBytesPerSec)
        }
    }

    override fun onProxyDelays(delays: Map<String, Int>) {
        if (lifecycle != Lifecycle.Running) return
        vpnStateRepository.updateProxyDelays(delays)
    }

    override fun onStopRequested() {
        enqueueControl(ActorCommand.Stop(null, force = true))
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

        val builder = Builder().setSession("FRKN").setMtu(config.mtu).setMetered(false)
        config.inet4.forEach { builder.addAddress(it.address, it.prefix) }
        config.inet6.forEach { builder.addAddress(it.address, it.prefix) }
        if (config.autoRoute) {
            config.dnsServers.forEach { builder.addDnsServer(it) }
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
            config.includePackages.forEach { builder.addAllowedApplication(it) }
            config.excludePackages.forEach { builder.addDisallowedApplication(it) }
        }

        val pfd = builder.establish() ?: error("android: establish() failed")
        frknLog.i(TAG, "tun established mtu=${config.mtu} include=${config.includePackages.size} exclude=${config.excludePackages.size}")
        runCatching { tunInterface?.close() }
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
        enqueueControl(ActorCommand.Stop(null, force = true))
    }

    override fun onDestroy() {
        val needsCleanup = lifecycle != Lifecycle.Idle
        val finalState = vpnStateRepository.state.value
            .takeIf { it is VpnState.Error }
            ?: VpnState.Disconnected
        if (needsCleanup) lifecycle = Lifecycle.Stopping
        networkMonitor.onUnderlyingNetworkChanged = null
        controlCommands.close()
        workCommands.close()
        scope.cancel()
        runBlocking { actorJob?.join() }
        if (needsCleanup) stopInternal(finalState, stopStartId = null, stopService = false)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "io.github.yulbax.frkn.action.START"
        const val ACTION_STOP = "io.github.yulbax.frkn.action.STOP"

        private const val TAG = "FrknVpnService"
        private const val BYEDPI_STABLE_RUNTIME_MS = 30_000L
        private const val MAX_RAPID_BYEDPI_EXITS = 3

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, FrknVpnService::class.java).setAction(ACTION_START)
            )
        }
    }
}

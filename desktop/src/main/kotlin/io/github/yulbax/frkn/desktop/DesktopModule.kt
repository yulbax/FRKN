package io.github.yulbax.frkn.desktop

import androidx.room.Room
import io.github.yulbax.frkn.data.AppDatabase
import io.github.yulbax.frkn.data.ConfigBackupRepository
import io.github.yulbax.frkn.data.InstalledAppsSource
import io.github.yulbax.frkn.data.RoomVpnSessionStore
import io.github.yulbax.frkn.data.SettingsRepository
import io.github.yulbax.frkn.data.profile.HttpSubscriptionProfileSource
import io.github.yulbax.frkn.data.profile.ProfileRepository
import io.github.yulbax.frkn.data.profile.SubscriptionProfileSource
import io.github.yulbax.frkn.util.AppLog
import io.github.yulbax.frkn.util.DiagnosticsSource
import io.github.yulbax.frkn.util.FileAppLog
import io.github.yulbax.frkn.util.VersionInfo
import io.github.yulbax.frkn.util.VpnLauncher
import io.github.yulbax.frkn.vpn.ByeDpiSiteProbe
import io.github.yulbax.frkn.vpn.HealthMonitor
import io.github.yulbax.frkn.vpn.ProbeUrls
import io.github.yulbax.frkn.vpn.SocksHealthProbe
import io.github.yulbax.frkn.vpn.SocksSiteProbe
import io.github.yulbax.frkn.vpn.VpnCommandBus
import io.github.yulbax.frkn.vpn.VpnController
import io.github.yulbax.frkn.vpn.VpnSession
import io.github.yulbax.frkn.vpn.VpnSessionStore
import io.github.yulbax.frkn.vpn.VpnStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module

val desktopModule = module {
    single { FileAppLog(DesktopPaths.logFile) { level, tag, text -> println("${level.symbol}/$tag: $text") } }
    single<AppLog> { get<FileAppLog>() }

    single { AppDatabase.build(Room.databaseBuilder<AppDatabase>(name = DesktopPaths.database.absolutePath)) }
    single { get<AppDatabase>().appDao() }
    single { get<AppDatabase>().settingsDao() }
    single { get<AppDatabase>().profileDao() }
    single { SettingsRepository(get(), get()) }
    single<SubscriptionProfileSource> { HttpSubscriptionProfileSource() }
    single { ProfileRepository(get(), get(), get()) }
    single { ConfigBackupRepository(get(), get()) }
    single<VpnSessionStore> { RoomVpnSessionStore(get(), get()) }

    single { VpnStateRepository() }
    single { VpnCommandBus() }
    single<ByeDpiSiteProbe> { SocksSiteProbe }
    single<DiagnosticsSource> { DesktopDiagnostics(get(), DesktopPaths.workDir) }
    single<VersionInfo> { DesktopVersionInfo(DesktopPaths.singBox) }
    single<InstalledAppsSource>(createdAtStart = true) {
        ProcessInstalledApps(get(), DesktopPaths.ownExecutable, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }

    single {
        val log = get<AppLog>()
        val stateRepository = get<VpnStateRepository>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        VpnController(scope, get(), stateRepository, DesktopHost, log) { controller ->
            VpnSession(
                engine = SingBoxProcessEngine(
                    binary = DesktopPaths.singBox,
                    workDir = DesktopPaths.workDir,
                    directProcesses = listOf(DesktopPaths.ciadpi.name),
                    delayProbeUrl = ProbeUrls.VPN,
                    listener = controller,
                    log = log
                ),
                byeDpi = CiadpiProcess(DesktopPaths.ciadpi, log),
                store = get(),
                stateRepository = stateRepository,
                health = HealthMonitor(stateRepository, log, SocksHealthProbe(ProbeUrls.VPN, ProbeUrls.BYEDPI)),
                quality = SocksSiteProbe,
                log = log,
                ownPackage = DesktopPaths.ownExecutable.orEmpty(),
                scope = scope,
                requestWork = controller::requestWork,
                onByeDpiExit = controller::onByeDpiExit
            )
        }
    }
    single<VpnLauncher> { DesktopVpnLauncher(get(), get(), get()) }
}

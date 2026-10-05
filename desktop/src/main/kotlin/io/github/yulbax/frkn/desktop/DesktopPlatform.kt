package io.github.yulbax.frkn.desktop

import io.github.yulbax.frkn.data.profile.ProfileRepository
import io.github.yulbax.frkn.util.DiagnosticsReport
import io.github.yulbax.frkn.util.DiagnosticsSource
import io.github.yulbax.frkn.util.FileAppLog
import io.github.yulbax.frkn.util.VersionInfo
import io.github.yulbax.frkn.util.VpnLauncher
import io.github.yulbax.frkn.vpn.VpnController
import io.github.yulbax.frkn.vpn.VpnHost
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DesktopHost : VpnHost {
    override fun onSessionStarting() = Unit

    override fun onSessionStarted() = Unit

    override fun allowsUserStop(): Boolean = true

    override fun onSessionStopped(stopToken: Int?, stopHost: Boolean) = Unit
}

class DesktopVpnLauncher(private val controller: VpnController) : VpnLauncher {
    override fun start() {
        controller.start(token = null, systemInitiated = false)
    }
}

class DesktopDiagnostics(
    private val log: FileAppLog,
    private val service: DesktopService,
    private val versionInfo: VersionInfo,
    private val profiles: ProfileRepository
) : DiagnosticsSource {
    override suspend fun collect(): String = withContext(Dispatchers.IO) {
        DiagnosticsReport.build(
            environment = listOf(
                "app" to "${versionInfo.appVersion ?: "unknown"} (core ${versionInfo.coreVersion() ?: "unknown"}, byedpi ${versionInfo.byeDpiVersion})",
                "os" to "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})",
                "java" to System.getProperty("java.version"),
                "service" to (service.peek()?.version ?: "not running")
            ),
            appLog = log.dump(),
            boxLog = File(service.peek()?.workDir ?: DesktopPaths.serviceSocket.parentFile, "box.log"),
            profiles = profiles.profileDiagnostics()
        )
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        log.clear()
        service.clearCoreLog()
    }
}

class DesktopVersionInfo(private val service: DesktopService) : VersionInfo {
    override val appVersion: String? = System.getProperty("frkn.version")
    override val byeDpiVersion: String = System.getProperty("frkn.byedpi.version") ?: "unknown"

    override suspend fun coreVersion(): String? = withContext(Dispatchers.IO) {
        service.peek()?.core?.ifBlank { null }
    }
}

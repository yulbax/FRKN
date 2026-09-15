package io.github.yulbax.frkn.desktop

import io.github.yulbax.frkn.util.AppLog
import io.github.yulbax.frkn.util.DiagnosticsReport
import io.github.yulbax.frkn.util.DiagnosticsSource
import io.github.yulbax.frkn.util.FileAppLog
import io.github.yulbax.frkn.util.VersionInfo
import io.github.yulbax.frkn.util.VpnLauncher
import io.github.yulbax.frkn.vpn.VpnController
import io.github.yulbax.frkn.vpn.VpnHost
import io.github.yulbax.frkn.vpn.VpnState
import io.github.yulbax.frkn.vpn.VpnStateRepository
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DesktopHost : VpnHost {
    override fun onSessionStarting() = Unit

    override fun onSessionStarted() = Unit

    override fun allowsUserStop(): Boolean = true

    override fun onSessionStopped(stopToken: Int?, stopHost: Boolean) = Unit
}

class DesktopVpnLauncher(
    private val controller: VpnController,
    private val stateRepository: VpnStateRepository,
    private val log: AppLog
) : VpnLauncher {
    override fun start() {
        if (DesktopPaths.isWindows && !WindowsElevation.isElevated()) {
            log.w(TAG, "VPN start refused: process is not elevated")
            stateRepository.update(VpnState.Error(ELEVATION_REQUIRED))
            return
        }
        controller.start(token = null, systemInitiated = false)
    }

    private companion object {
        const val TAG = "DesktopVpnLauncher"
        const val ELEVATION_REQUIRED = "Administrator rights are required to create the VPN adapter. Restart FRKN as administrator."
    }
}

class DesktopDiagnostics(private val log: FileAppLog, private val workDir: File) : DiagnosticsSource {
    override suspend fun collect(): String = withContext(Dispatchers.IO) {
        DiagnosticsReport.build(
            environment = listOf(
                "os" to "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})",
                "java" to System.getProperty("java.version"),
                "elevated" to WindowsElevation.isElevated().toString()
            ),
            appLog = log.dump(),
            boxLog = File(workDir, "box.log")
        )
    }
}

class DesktopVersionInfo(private val singBox: File) : VersionInfo {
    override val appVersion: String? = System.getProperty("frkn.version")
    override val byeDpiVersion: String = System.getProperty("frkn.byedpi.version") ?: "unknown"

    override suspend fun coreVersion(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder(singBox.absolutePath, "version").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(5, TimeUnit.SECONDS)
            VERSION_REGEX.find(output)?.groupValues?.get(1)
        }.getOrNull()
    }

    private companion object {
        val VERSION_REGEX = Regex("version\\s+(\\S+)")
    }
}

object WindowsElevation {
    private const val HIGH_INTEGRITY_SID = "S-1-16-12288"

    fun isElevated(): Boolean {
        if (!DesktopPaths.isWindows) return true
        return runCatching {
            val process = ProcessBuilder("whoami", "/groups").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(5, TimeUnit.SECONDS)
            output.contains(HIGH_INTEGRITY_SID)
        }.getOrDefault(false)
    }

    fun relaunchElevated(): Boolean {
        val executable = ProcessHandle.current().info().command().orElse(null) ?: return false
        if (!File(executable).name.equals("FRKN.exe", ignoreCase = true)) return false
        return runCatching {
            ProcessBuilder(
                "powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command",
                "Start-Process -FilePath '${executable.replace("'", "''")}' -Verb RunAs"
            ).start().waitFor() == 0
        }.getOrDefault(false)
    }
}

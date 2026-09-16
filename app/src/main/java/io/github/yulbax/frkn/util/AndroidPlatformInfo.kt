package io.github.yulbax.frkn.util

import android.content.Context
import io.github.yulbax.frkn.engine.BYEDPI_VERSION
import io.github.yulbax.frkn.vpn.FrknVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libbox.Libbox
import org.koin.core.annotation.Single

@Single(binds = [VpnLauncher::class])
class AndroidVpnLauncher(private val context: Context) : VpnLauncher {
    override fun start() = FrknVpnService.start(context)
}

@Single(binds = [DiagnosticsSource::class])
class AndroidDiagnosticsSource(
    private val context: Context,
    private val frknLog: FrknLog
) : DiagnosticsSource {
    override suspend fun collect(): String = Diagnostics.collect(context, frknLog)
}

@Single(binds = [VersionInfo::class])
class AndroidVersionInfo(private val context: Context) : VersionInfo {
    override val appVersion: String?
        get() = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()

    override val byeDpiVersion: String = BYEDPI_VERSION

    override suspend fun coreVersion(): String? = withContext(Dispatchers.IO) {
        runCatching { Libbox.version() }.getOrNull()
    }
}

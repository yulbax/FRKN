package io.github.yulbax.frkn.util

import android.content.Context
import android.os.Build
import io.github.yulbax.frkn.data.profile.ProfileRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Diagnostics {
    suspend fun collect(
        context: Context,
        frknLog: FrknLog,
        versionInfo: VersionInfo,
        profiles: ProfileRepository
    ): String = withContext(Dispatchers.IO) {
        DiagnosticsReport.build(
            environment = listOf(
                "app" to "${versionInfo.appVersion ?: "unknown"} (core ${versionInfo.coreVersion() ?: "unknown"}, byedpi ${versionInfo.byeDpiVersion})",
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                "abi" to Build.SUPPORTED_ABIS.joinToString(",")
            ),
            appLog = frknLog.dump(),
            boxLog = File(File(context.filesDir, "work"), "box.log"),
            profiles = profiles.profileDiagnostics()
        )
    }
}

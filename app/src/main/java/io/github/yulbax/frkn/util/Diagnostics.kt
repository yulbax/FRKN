package io.github.yulbax.frkn.util

import android.content.Context
import android.os.Build
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Diagnostics {
    suspend fun collect(context: Context, frknLog: FrknLog): String = withContext(Dispatchers.IO) {
        DiagnosticsReport.build(
            environment = listOf(
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                "abi" to Build.SUPPORTED_ABIS.joinToString(",")
            ),
            appLog = frknLog.dump(),
            boxLog = File(File(context.filesDir, "work"), "box.log")
        )
    }
}

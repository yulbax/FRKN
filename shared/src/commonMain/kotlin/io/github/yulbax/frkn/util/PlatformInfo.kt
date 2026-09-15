package io.github.yulbax.frkn.util

fun interface VpnLauncher {
    fun start()
}

fun interface DiagnosticsSource {
    suspend fun collect(): String
}

interface VersionInfo {
    val appVersion: String?
    val byeDpiVersion: String

    suspend fun coreVersion(): String?
}

package io.github.yulbax.frkn.vpn

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds

class SocksHealthProbe(
    private val vpnProbeUrl: String,
    private val byeDpiProbeUrl: String
) : HealthProbe {

    override fun observe(params: ProbeParams): Flow<HealthSnapshot> = flow {
        val engine = params.engine
        val credentials = SocksCredentials(engine.probeUsername, engine.probePassword)
        var country = ""
        while (true) {
            val vpnDelay = if (params.vpnActive) latencyMs(engine.probeSocksPort, vpnProbeUrl, credentials) else null
            val byedpiDelay = params.byeDpiPort?.let { latencyMs(it, byeDpiProbeUrl, null) }
            if (vpnDelay != null && country.isEmpty()) {
                country = resolveCountry(engine.probeSocksPort, credentials) ?: country
            }
            val snapshot = HealthSnapshot(
                vpnActive = params.vpnActive,
                vpnDelayMs = vpnDelay,
                vpnCountry = country,
                byedpiActive = params.byeDpiPort != null,
                byedpiDelayMs = byedpiDelay,
                fpError = params.isFingerprintError()
            )
            emit(snapshot)
            delay((if (snapshot.allActiveUp) HEALTH_INTERVAL_MS else HEALTH_RETRY_MS).milliseconds)
        }
    }

    private suspend fun latencyMs(socksPort: Int, url: String, credentials: SocksCredentials?): Int? {
        val start = System.nanoTime()
        return SocksHttp.request(socksPort, url, "GET", PROBE_TIMEOUT_MS, credentials) { connection ->
            val code = connection.responseCode
            connection.drain()
            if (code in 200..399) ((System.nanoTime() - start) / 1_000_000).toInt().coerceAtLeast(1) else null
        }
    }

    private suspend fun resolveCountry(socksPort: Int, credentials: SocksCredentials): String? =
        SocksHttp.request(socksPort, GEO_URL, "GET", PROBE_TIMEOUT_MS, credentials) { connection ->
            val body = connection.inputStream.use { it.readBytes().decodeToString() }
            COUNTRY_REGEX.find(body)?.groupValues?.get(1)?.uppercase()
        }

    private fun java.net.HttpURLConnection.drain() {
        runCatching { (if (responseCode >= 400) errorStream else inputStream)?.use { it.readBytes() } }
    }

    private companion object {
        const val HEALTH_INTERVAL_MS = 15_000L
        const val HEALTH_RETRY_MS = 3_000L
        const val PROBE_TIMEOUT_MS = 6_000
        const val GEO_URL = "https://api.ipapi.is/"
        val COUNTRY_REGEX = Regex("\"(?:cc|country_code)\"\\s*:\\s*\"([A-Za-z]{2})\"")
    }
}

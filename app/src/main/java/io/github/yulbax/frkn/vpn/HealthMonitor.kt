package io.github.yulbax.frkn.vpn

import io.github.yulbax.frkn.util.FrknLog
import io.github.yulbax.frkn.vpn.core.VpnEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds


data class HealthParams(
    val engine: VpnEngine,
    val byeDpiPort: Int?,
    val vpnActive: Boolean,
    val onRefreshSubscription: suspend () -> Unit,
    val onRecoveryReload: suspend () -> Unit,
    val onByedpiUp: suspend () -> Unit,
    val isFingerprintError: () -> Boolean
)

class HealthMonitor(
    private val stateRepository: VpnStateRepository,
    private val log: FrknLog
) {
    private var job: Job? = null
    private var vpnCountry: String = ""
    private var byedpiCountry: String = ""

    fun start(scope: CoroutineScope, params: HealthParams) {
        val engine = params.engine
        val byeDpiPort = params.byeDpiPort
        val vpnActive = params.vpnActive
        val onRefreshSubscription = params.onRefreshSubscription
        val onRecoveryReload = params.onRecoveryReload
        val onByedpiUp = params.onByedpiUp
        val isFingerprintError = params.isFingerprintError
        job?.cancel()
        vpnCountry = ""
        byedpiCountry = ""
        job = scope.launch {
            var failures = 0
            var wasByedpiUp = false
            var prevVpnUp: Boolean? = null
            var prevByedpiUp: Boolean? = null
            while (isActive) {
                val (vpnDelay, newVpnCountry) =
                    probeChannel(
                        engine.probeSocksPort,
                        vpnCountry,
                        VPN_PROBE_URL,
                        engine.probeUsername,
                        engine.probePassword
                    )
                vpnCountry = newVpnCountry

                val (byedpiDelay, newByedpiCountry) =
                    if (byeDpiPort != null)
                        probeChannel(byeDpiPort, byedpiCountry, BYEDPI_PROBE_URL)
                    else null to byedpiCountry

                byedpiCountry = newByedpiCountry
                val vpnUp = vpnDelay != null
                val byedpiUp = byedpiDelay != null

                if (byedpiUp && !wasByedpiUp) onByedpiUp()
                wasByedpiUp = byedpiUp

                val byedpiActive = byeDpiPort != null

                if (vpnActive && vpnUp != prevVpnUp) {
                    if (vpnUp) {
                        val country = if (vpnCountry.isNotEmpty()) " $vpnCountry" else ""
                        log.i(TAG, "vpn channel up (${vpnDelay}ms$country)")
                    } else {
                        log.w(TAG, "vpn channel down")
                    }
                    prevVpnUp = vpnUp
                }
                if (byedpiActive && byedpiUp != prevByedpiUp) {
                    if (byedpiUp) log.i(TAG, "byedpi channel up (${byedpiDelay}ms)")
                    else log.w(TAG, "byedpi channel down")
                    prevByedpiUp = byedpiUp
                }
                val anyUp = vpnUp || byedpiUp
                val allActiveUp = (!vpnActive || vpnUp) && (!byedpiActive || byedpiUp)
                val fpError = isFingerprintError()

                stateRepository.updateStats {
                    it.copy(
                        vpnUp = vpnUp,
                        vpnLatencyMs = vpnDelay ?: 0,
                        vpnCountry = vpnCountry,
                        vpnCycling = vpnActive && !vpnUp && fpError,
                        byedpiActive = byedpiActive,
                        byedpiUp = byedpiUp,
                        byedpiLatencyMs = byedpiDelay ?: 0,
                        byedpiCountry = byedpiCountry
                    )
                }

                if (allActiveUp) failures = 0 else failures++

                if (anyUp) {
                    stateRepository.update(VpnState.Connected(vpnDelay ?: byedpiDelay ?: 0))
                } else {
                    stateRepository.update(VpnState.Verifying)
                }

                if (!allActiveUp) {
                    if (fpError) {
                        onRecoveryReload()
                    } else {
                        if (failures % REFRESH_SUBSCRIPTION_AFTER_FAILURES == 0) onRefreshSubscription()
                        if (failures % RELOAD_EVERY_N_FAILURES == 0) onRecoveryReload()
                    }
                }

                val interval = if (allActiveUp && vpnUp) HEALTH_INTERVAL_MS else HEALTH_RETRY_MS
                delay(interval.milliseconds)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        SocksProbe.close()
    }

    private suspend fun probeChannel(
        socksPort: Int,
        currentCountry: String,
        probeUrl: String,
        username: String? = null,
        password: String? = null
    ): Pair<Int?, String> {
        val delay = SocksProbe.latencyMs(socksPort, username, password, probeUrl)
        val country = if (delay != null && currentCountry.isEmpty()) {
            SocksProbe.resolveCountry(socksPort, username, password) ?: currentCountry
        } else {
            currentCountry
        }
        return delay to country
    }

    companion object {
        private const val TAG = "Health"
        private const val HEALTH_INTERVAL_MS = 15_000L
        private const val HEALTH_RETRY_MS = 3_000L
        private const val RELOAD_EVERY_N_FAILURES = 4
        private const val REFRESH_SUBSCRIPTION_AFTER_FAILURES = 3
        private const val VPN_PROBE_URL = "https://www.gstatic.com/generate_204"
        private const val BYEDPI_PROBE_URL = "https://www.youtube.com/generate_204"
    }
}

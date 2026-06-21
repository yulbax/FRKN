package io.github.yulbax.frkn.vpn

import io.github.yulbax.frkn.vpn.core.VpnEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds


class HealthMonitor(
    private val stateRepository: VpnStateRepository
) {
    private var job: Job? = null
    private var vpnCountry: String = ""
    private var byedpiCountry: String = ""

    fun start(
        scope: CoroutineScope,
        engine: VpnEngine,
        byeDpiPort: Int?,
        onRefreshSubscription: suspend () -> Unit,
        onRecoveryReload: suspend () -> Unit,
        onByedpiUp: suspend () -> Unit
    ) {
        job?.cancel()
        vpnCountry = ""
        byedpiCountry = ""
        job = scope.launch {
            var failures = 0
            var wasByedpiUp = false
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

                stateRepository.updateStats {
                    it.copy(
                        vpnUp = vpnUp,
                        vpnLatencyMs = vpnDelay ?: 0,
                        vpnCountry = vpnCountry,
                        byedpiActive = byeDpiPort != null,
                        byedpiUp = byedpiUp,
                        byedpiLatencyMs = byedpiDelay ?: 0,
                        byedpiCountry = byedpiCountry
                    )
                }

                if (vpnUp || byedpiUp) {
                    failures = 0
                    stateRepository.update(VpnState.Connected(vpnDelay ?: byedpiDelay ?: 0))
                    delay((if (vpnUp) HEALTH_INTERVAL_MS else HEALTH_RETRY_MS).milliseconds)
                } else {
                    failures++
                    stateRepository.update(VpnState.Reconnecting(failures))
                    if (failures % REFRESH_SUBSCRIPTION_AFTER_FAILURES == 0) onRefreshSubscription()
                    if (failures % RELOAD_EVERY_N_FAILURES == 0) onRecoveryReload()
                    delay(HEALTH_RETRY_MS.milliseconds)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
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
        private const val HEALTH_INTERVAL_MS = 15_000L
        private const val HEALTH_RETRY_MS = 3_000L
        private const val RELOAD_EVERY_N_FAILURES = 4
        private const val REFRESH_SUBSCRIPTION_AFTER_FAILURES = 3
        private const val VPN_PROBE_URL = "https://www.gstatic.com/generate_204"
        private const val BYEDPI_PROBE_URL = "https://www.youtube.com/generate_204"
    }
}

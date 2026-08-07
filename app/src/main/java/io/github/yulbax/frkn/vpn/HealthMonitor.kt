package io.github.yulbax.frkn.vpn

import io.github.yulbax.frkn.BuildConfig
import io.github.yulbax.frkn.util.FrknLog
import io.github.yulbax.frkn.vpn.core.VpnEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlin.time.Duration.Companion.milliseconds

data class ProbeParams(
    val engine: VpnEngine,
    val byeDpiPort: Int?,
    val vpnActive: Boolean,
    val isFingerprintError: () -> Boolean
)

data class HealthParams(
    val probe: ProbeParams,
    val onRefreshSubscription: suspend () -> Unit,
    val onRecoveryReload: suspend () -> Unit,
    val onByedpiUp: suspend () -> Unit
)

data class HealthSnapshot(
    val vpnActive: Boolean,
    val vpnDelayMs: Int?,
    val vpnCountry: String,
    val byedpiActive: Boolean,
    val byedpiDelayMs: Int?,
    val fpError: Boolean
) {
    val vpnUp get() = vpnDelayMs != null
    val byedpiUp get() = byedpiDelayMs != null
    val anyUp get() = vpnUp || byedpiUp
    val allActiveUp get() = (!vpnActive || vpnUp) && (!byedpiActive || byedpiUp)
}

class ConnectionHealthObserver {
    fun observe(params: ProbeParams): Flow<HealthSnapshot> = flow {
        val engine = params.engine
        var country = ""
        while (true) {
            val vpnDelay = if (params.vpnActive) {
                SocksProbe.latencyMs(
                    engine.probeSocksPort, engine.probeUsername, engine.probePassword, VPN_PROBE_URL
                )
            } else null
            val byedpiDelay = params.byeDpiPort?.let {
                SocksProbe.latencyMs(it, null, null, BYEDPI_PROBE_URL)
            }
            if (vpnDelay != null && country.isEmpty()) {
                country = SocksProbe.resolveCountry(
                    engine.probeSocksPort, engine.probeUsername, engine.probePassword
                ) ?: country
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

    companion object {
        private const val HEALTH_INTERVAL_MS = 15_000L
        private const val HEALTH_RETRY_MS = 3_000L
        private const val VPN_PROBE_URL = BuildConfig.VPN_HEALTH_PROBE_URL
        private const val BYEDPI_PROBE_URL = BuildConfig.BYEDPI_HEALTH_PROBE_URL
    }
}

class HealthMonitor(
    private val stateRepository: VpnStateRepository,
    private val log: FrknLog
) {
    private val observer = ConnectionHealthObserver()
    private var scope: CoroutineScope? = null

    fun start(scope: CoroutineScope, params: HealthParams) {
        stop()
        val childScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        this.scope = childScope

        val health = observer.observe(params.probe)
            .shareIn(childScope, SharingStarted.Eagerly, replay = 1)

        health.onEach(::publishStats).launchIn(childScope)

        health.filter { it.vpnActive }
            .distinctUntilChangedBy { it.vpnUp }
            .onEach { logVpnTransition(it) }
            .launchIn(childScope)

        health.filter { it.byedpiActive }
            .distinctUntilChangedBy { it.byedpiUp }
            .onEach { onByedpiTransition(it, params) }
            .launchIn(childScope)

        childScope.launch {
            var failures = 0
            health.collect { snapshot ->
                failures = if (snapshot.allActiveUp) 0 else failures + 1
                if (!snapshot.allActiveUp) recover(failures, snapshot, params)
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        SocksProbe.close()
    }

    private fun publishStats(s: HealthSnapshot) {
        stateRepository.updateStats {
            it.copy(
                vpnUp = s.vpnUp,
                vpnLatencyMs = s.vpnDelayMs ?: 0,
                vpnCountry = s.vpnCountry,
                vpnCycling = s.vpnActive && !s.vpnUp && s.fpError,
                byedpiActive = s.byedpiActive,
                byedpiUp = s.byedpiUp,
                byedpiLatencyMs = s.byedpiDelayMs ?: 0
            )
        }
        stateRepository.update(
            if (s.anyUp) VpnState.Connected(s.vpnDelayMs ?: s.byedpiDelayMs ?: 0)
            else VpnState.Verifying
        )
    }

    private fun logVpnTransition(s: HealthSnapshot) {
        if (s.vpnUp) {
            val country = if (s.vpnCountry.isNotEmpty()) " ${s.vpnCountry}" else ""
            log.i(TAG, "vpn channel up (${s.vpnDelayMs}ms$country)")
        } else {
            log.w(TAG, "vpn channel down")
        }
    }

    private suspend fun onByedpiTransition(s: HealthSnapshot, params: HealthParams) {
        if (s.byedpiUp) {
            log.i(TAG, "byedpi channel up (${s.byedpiDelayMs}ms)")
            params.onByedpiUp()
        } else {
            log.w(TAG, "byedpi channel down")
        }
    }

    private suspend fun recover(failures: Int, s: HealthSnapshot, params: HealthParams) {
        if (s.fpError) {
            params.onRecoveryReload()
            return
        }
        if (failures % REFRESH_SUBSCRIPTION_AFTER_FAILURES == 0) params.onRefreshSubscription()
        if (failures % RELOAD_EVERY_N_FAILURES == 0) params.onRecoveryReload()
    }

    companion object {
        private const val TAG = "Health"
        private const val RELOAD_EVERY_N_FAILURES = 4
        private const val REFRESH_SUBSCRIPTION_AFTER_FAILURES = 3
    }
}

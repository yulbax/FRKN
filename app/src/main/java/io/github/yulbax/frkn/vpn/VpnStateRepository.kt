package io.github.yulbax.frkn.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.koin.core.annotation.Single

data class ConnectionStats(
    val configName: String = "",
    val vpnUp: Boolean = false,
    val vpnLatencyMs: Int = 0,
    val vpnCountry: String = "",
    val vpnCycling: Boolean = false,
    val byedpiActive: Boolean = false,
    val byedpiUp: Boolean = false,
    val byedpiLatencyMs: Int = 0,
    val byedpiCountry: String = "",
    val byedpiChecking: Boolean = false,
    val byedpiReachable: Int = 0,
    val byedpiTotal: Int = 0,
    val byeDpiPort: Int = 0,
    val uplink: Long = 0,
    val downlink: Long = 0
)

@Single
class VpnStateRepository {
    private val _state = MutableStateFlow<VpnState>(VpnState.Disconnected)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(ConnectionStats())
    val stats: StateFlow<ConnectionStats> = _stats.asStateFlow()

    private val _proxyDelays = MutableStateFlow<Map<String, Int>>(emptyMap())
    val proxyDelays: StateFlow<Map<String, Int>> = _proxyDelays.asStateFlow()

    fun update(state: VpnState) {
        _state.value = state
    }

    fun updateStats(block: (ConnectionStats) -> ConnectionStats) {
        _stats.update(block)
    }

    fun updateProxyDelays(delays: Map<String, Int>) {
        _proxyDelays.value = delays
    }

    fun resetStats() {
        _stats.value = ConnectionStats()
        _proxyDelays.value = emptyMap()
    }
}

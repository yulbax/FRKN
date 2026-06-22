package io.github.yulbax.frkn.vpn

sealed interface VpnState {
    data object Disconnected : VpnState

    data object Connecting : VpnState

    data object Verifying : VpnState

    data class Connected(val latencyMs: Int) : VpnState

    data class Error(val message: String) : VpnState
}

package io.github.yulbax.frkn.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yulbax.frkn.data.AppDao
import io.github.yulbax.frkn.data.ConnectionType
import io.github.yulbax.frkn.data.SettingsDao
import io.github.yulbax.frkn.data.SettingsEntity
import kotlinx.coroutines.flow.first
import io.github.yulbax.frkn.vpn.ByeDpiQuality
import io.github.yulbax.frkn.vpn.ConnectionStats
import io.github.yulbax.frkn.vpn.FrknVpnService
import io.github.yulbax.frkn.vpn.VpnCommandBus
import io.github.yulbax.frkn.util.Telemetry
import io.github.yulbax.frkn.vpn.VpnState
import io.github.yulbax.frkn.vpn.VpnStateRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FrknUiState(
    val homeHintSeen: Boolean = true,
    val hasRoutedApps: Boolean = false
)

class ConnectionViewModel(
    private val application: Application,
    vpnStateRepository: VpnStateRepository,
    appDao: AppDao,
    private val settingsDao: SettingsDao,
    private val commandBus: VpnCommandBus
) : ViewModel() {

    val uiState: StateFlow<FrknUiState> = combine(
        settingsDao.observeSettings().map { it?.homeHintSeen ?: false },
        appDao.getAllApps().map { apps ->
            apps.any {
                it.connectionType == ConnectionType.VPN ||
                    it.connectionType == ConnectionType.BYEDPI
            }
        }
    ) { homeHintSeen, hasRoutedApps -> FrknUiState(homeHintSeen, hasRoutedApps) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FrknUiState())

    fun dismissHomeHint() {
        viewModelScope.launch {
            val current = settingsDao.observeSettings().first() ?: SettingsEntity()
            if (!current.homeHintSeen) settingsDao.upsertSettings(current.copy(homeHintSeen = true))
        }
    }

    val state: StateFlow<VpnState> = vpnStateRepository.state
    val stats: StateFlow<ConnectionStats> = vpnStateRepository.stats

    data class ByeDpiTestState(
        val running: Boolean = false,
        val full: Boolean = false,
        val total: Int = 0,
        val results: List<ByeDpiQuality.SiteResult> = emptyList()
    )

    private var testJob: Job? = null
    private val _byeDpiTest = MutableStateFlow(ByeDpiTestState())
    val byeDpiTest: StateFlow<ByeDpiTestState> = _byeDpiTest.asStateFlow()

    fun startVpn() {
        Telemetry.logVpnToggle(connect = true)
        FrknVpnService.start(application)
    }

    fun stopVpn() {
        Telemetry.logVpnToggle(connect = false)
        commandBus.stop()
    }

    fun runByeDpiTest(full: Boolean) {
        val port = stats.value.byeDpiPort
        if (port <= 0) return
        testJob?.cancel()
        val groups = if (full) ByeDpiQuality.GROUPS else listOf(ByeDpiQuality.QUICK)
        val total = groups.sumOf { it.sites.size }
        _byeDpiTest.value = ByeDpiTestState(running = true, full = full, total = total)
        testJob = viewModelScope.launch {
            ByeDpiQuality.probe(port, groups).collect { result ->
                _byeDpiTest.update { it.copy(results = it.results + result) }
            }
            _byeDpiTest.update { it.copy(running = false) }
        }
    }

    fun stopByeDpiTest() {
        testJob?.cancel()
        _byeDpiTest.update { it.copy(running = false) }
    }
}

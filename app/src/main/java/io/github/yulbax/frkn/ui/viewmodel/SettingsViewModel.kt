package io.github.yulbax.frkn.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yulbax.frkn.data.AppDao
import io.github.yulbax.frkn.data.SettingsDao
import io.github.yulbax.frkn.data.SettingsEntity
import io.github.yulbax.frkn.engine.ByeDpi
import io.github.yulbax.frkn.util.Diagnostics
import io.github.yulbax.frkn.vpn.core.Ipv6Mode
import io.github.yulbax.frkn.vpn.core.TlsFingerprint
import io.github.yulbax.frkn.vpn.core.TunStack
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val application: Application,
    private val settingsDao: SettingsDao,
    private val appDao: AppDao
) : ViewModel() {

    private val settings: StateFlow<SettingsEntity> = settingsDao.observeSettings()
        .map { it ?: SettingsEntity() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsEntity())

    val byeDpiArgsDefault: String = ByeDpi.DEFAULT_DESYNC_ARGS.joinToString(" ")

    val uiState: StateFlow<SettingsUiState> = settings
        .map { it.toUiState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsEntity().toUiState())

    fun toggleShowSystemApps() = update { it.copy(showSystemApps = !it.showSystemApps) }
    fun setByeDpiArgs(args: String) = update { it.copy(byeDpiArgs = args) }

    fun setTunStack(value: TunStack) = update { it.copy(tunStack = value.wire) }
    fun setMtu(value: Int) = update { it.copy(mtu = value) }
    fun setIpv6Mode(value: Ipv6Mode) = update { it.copy(ipv6Mode = value.wire) }
    fun setDnsRemote(value: String) = update { it.copy(dnsRemote = value.trim()) }
    fun setDnsDirect(value: String) = update { it.copy(dnsDirect = value.trim()) }
    fun setSniff(value: Boolean) = update { it.copy(sniff = value) }
    fun setBypassLan(value: Boolean) = update { it.copy(bypassLan = value) }
    fun setAutoConnect(value: Boolean) = update { it.copy(autoConnect = value) }
    fun setPreferredFingerprint(value: TlsFingerprint?) = update { it.copy(preferredFingerprint = value?.wire ?: "") }
    fun exportAppConfig(onReady: (Uri) -> Unit) {
        viewModelScope.launch {
            runCatching { Diagnostics.exportAppConfig(application, appDao, settingsDao) }.onSuccess(onReady)
        }
    }

    fun importAppConfig(uri: Uri, onResult: (Diagnostics.ImportResult) -> Unit) {
        viewModelScope.launch {
            val result = Diagnostics.importAppConfig(application, appDao, settingsDao, uri)
            onResult(result)
        }
    }

    private fun update(transform: (SettingsEntity) -> SettingsEntity) {
        viewModelScope.launch {
            val current = settingsDao.observeSettings().first() ?: SettingsEntity()
            settingsDao.upsertSettings(transform(current))
        }
    }
}

data class SettingsUiState(
    val showSystemApps: Boolean = false,
    val byeDpiArgs: String = "",
    val autoConnect: Boolean = false,
    val tunStack: TunStack = TunStack.GVISOR,
    val mtu: Int = 0,
    val ipv6Mode: Ipv6Mode = Ipv6Mode.DISABLE,
    val dnsRemote: String = "",
    val dnsDirect: String = "",
    val sniff: Boolean = false,
    val bypassLan: Boolean = false,
    val preferredFingerprint: TlsFingerprint? = null
)

private fun SettingsEntity.toUiState() = SettingsUiState(
    showSystemApps = showSystemApps,
    byeDpiArgs = byeDpiArgs,
    autoConnect = autoConnect,
    tunStack = TunStack.fromWire(tunStack),
    mtu = mtu,
    ipv6Mode = Ipv6Mode.fromWire(ipv6Mode),
    dnsRemote = dnsRemote,
    dnsDirect = dnsDirect,
    sniff = sniff,
    bypassLan = bypassLan,
    preferredFingerprint = TlsFingerprint.fromWire(preferredFingerprint)
)

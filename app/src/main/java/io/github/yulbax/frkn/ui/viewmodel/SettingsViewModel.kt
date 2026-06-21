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

    val showSystemApps: StateFlow<Boolean> = settings
        .map { it.showSystemApps }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val byeDpiArgs: StateFlow<String> = settings
        .map { it.byeDpiArgs }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val byeDpiArgsDefault: String = ByeDpi.DEFAULT_DESYNC_ARGS.joinToString(" ")

    private val defaults = SettingsEntity()

    val tunStack: StateFlow<TunStack> = field { TunStack.fromWire(it.tunStack) }
    val mtu: StateFlow<Int> = field { it.mtu }
    val ipv6Mode: StateFlow<Ipv6Mode> = field { Ipv6Mode.fromWire(it.ipv6Mode) }
    val dnsRemote: StateFlow<String> = field { it.dnsRemote }
    val dnsDirect: StateFlow<String> = field { it.dnsDirect }
    val sniff: StateFlow<Boolean> = field { it.sniff }
    val bypassLan: StateFlow<Boolean> = field { it.bypassLan }
    val autoConnect: StateFlow<Boolean> = field { it.autoConnect }
    val preferredFingerprint: StateFlow<TlsFingerprint?> = field { TlsFingerprint.fromWire(it.preferredFingerprint) }

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

    private fun <T> field(selector: (SettingsEntity) -> T): StateFlow<T> = settings
        .map(selector)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), selector(defaults))


    private fun update(transform: (SettingsEntity) -> SettingsEntity) {
        viewModelScope.launch {
            val current = settingsDao.observeSettings().first() ?: SettingsEntity()
            settingsDao.upsertSettings(transform(current))
        }
    }
}

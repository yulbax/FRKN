package io.github.yulbax.frkn.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yulbax.frkn.R
import io.github.yulbax.frkn.data.profile.ProfileEntity
import io.github.yulbax.frkn.data.profile.ProfileOperationResult
import io.github.yulbax.frkn.data.profile.ProfileRepository
import io.github.yulbax.frkn.util.FrknLog
import io.github.yulbax.frkn.util.redactSensitiveData
import io.github.yulbax.frkn.vpn.VpnCommandBus
import io.github.yulbax.frkn.vpn.VpnStateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

data class ServersUiState(
    val profiles: List<ProfileEntity> = emptyList(),
    val selected: ProfileEntity? = null,
    val delays: Map<Long, Int> = emptyMap(),
    val error: String? = null
)

@KoinViewModel
class ProfileViewModel(
    private val application: Application,
    private val profileRepository: ProfileRepository,
    stateRepository: VpnStateRepository,
    private val commandBus: VpnCommandBus,
    private val log: FrknLog
) : ViewModel() {

    private val _error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ServersUiState> = combine(
        profileRepository.profiles,
        profileRepository.selected,
        stateRepository.proxyDelays,
        _error
    ) { profiles, selected, delays, error ->
        ServersUiState(profiles, selected, delays.toProfileDelays(), error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ServersUiState())

    fun testAll() {
        commandBus.testProxies()
    }

    fun clearError() {
        _error.value = null
    }

    fun add(raw: String) {
        viewModelScope.launch(Dispatchers.IO) {
            handleResult("add server", profileRepository.add(raw))
        }
    }

    fun update(profile: ProfileEntity, name: String, link: String) {
        viewModelScope.launch(Dispatchers.IO) {
            handleResult("edit server", profileRepository.update(profile, name, link))
        }
    }

    fun refreshSubscription(profile: ProfileEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            handleResult("subscription refresh", profileRepository.refreshSubscription(profile))
        }
    }

    fun select(profile: ProfileEntity) {
        viewModelScope.launch(Dispatchers.IO) { profileRepository.select(profile) }
    }

    fun delete(profile: ProfileEntity) {
        viewModelScope.launch(Dispatchers.IO) { profileRepository.delete(profile) }
    }

    private fun handleResult(operation: String, result: ProfileOperationResult) {
        when (result) {
            is ProfileOperationResult.Success -> {
                if (result.affected > 1) log.i(TAG, "$operation: ${result.affected} servers")
            }
            ProfileOperationResult.InvalidLink -> {
                log.w(TAG, "$operation: unsupported or invalid link")
                _error.value = application.getString(R.string.invalid_link_error)
            }
            ProfileOperationResult.EmptySubscription -> {
                log.w(TAG, "$operation: subscription returned no servers")
                _error.value = application.getString(R.string.no_servers_in_subscription)
            }
            is ProfileOperationResult.FetchFailed -> {
                log.w(TAG, "$operation: subscription fetch failed", result.cause)
                _error.value = application.getString(
                    R.string.fetch_subscription_failed,
                    redactSensitiveData(result.cause.message ?: "unknown error")
                )
            }
        }
    }

    private fun Map<String, Int>.toProfileDelays(): Map<Long, Int> =
        mapNotNull { (tag, delay) ->
            tag.removePrefix("p").toLongOrNull()?.let { it to delay }
        }.toMap()

    private companion object {
        const val TAG = "Servers"
    }
}

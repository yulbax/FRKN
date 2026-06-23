package io.github.yulbax.frkn.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yulbax.frkn.R
import io.github.yulbax.frkn.util.FrknLog
import io.github.yulbax.frkn.util.LinkParser
import io.github.yulbax.frkn.util.SubscriptionFetcher
import io.github.yulbax.frkn.data.profile.ProfileDao
import io.github.yulbax.frkn.data.profile.ProfileEntity
import io.github.yulbax.frkn.vpn.VpnCommandBus
import io.github.yulbax.frkn.vpn.VpnStateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.koin.android.annotation.KoinViewModel

data class ServersUiState(
    val profiles: List<ProfileEntity> = emptyList(),
    val selected: ProfileEntity? = null,
    val delays: Map<Long, Int> = emptyMap(),
    val error: String? = null
)

@KoinViewModel
class ProfileViewModel(
    private val application: Application,
    private val profileDao: ProfileDao,
    private val stateRepository: VpnStateRepository,
    private val commandBus: VpnCommandBus,
    private val log: FrknLog
) : ViewModel() {

    private val _error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ServersUiState> = combine(
        profileDao.observeAll(),
        profileDao.observeSelected(),
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
        val input = raw.trim()
        when {
            LinkParser.parse(input) != null -> addLink(input)
            input.startsWith("http://", ignoreCase = true) ||
                input.startsWith("https://", ignoreCase = true) -> importSubscription(input)
            else -> {
                log.w(TAG, "add server: unsupported or invalid link")
                _error.value = application.getString(R.string.invalid_link_error)
            }
        }
    }

    fun addLink(link: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val parsed = LinkParser.parse(link)
            if (parsed == null) {
                log.w(TAG, "add server: link parse failed")
                _error.value = application.getString(R.string.invalid_link_error)
                return@launch
            }
            val id = profileDao.insert(
                ProfileEntity(
                    name = parsed.name,
                    type = parsed.type,
                    link = link.trim(),
                    outboundJson = Json.encodeToString(JsonObject.serializer(), parsed.outbound)
                )
            )
            if (profileDao.getSelected() == null) profileDao.selectExclusive(id)
        }
    }

    fun importSubscription(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val parsedList = runCatching { SubscriptionFetcher.fetch(url.trim()) }
                .getOrElse {
                    log.w(TAG, "subscription fetch failed", it)
                    _error.value = application.getString(R.string.fetch_subscription_failed, it.message)
                    return@launch
                }
            if (parsedList.isEmpty()) {
                log.w(TAG, "subscription returned no servers")
                _error.value = application.getString(R.string.no_servers_in_subscription)
                return@launch
            }
            log.i(TAG, "subscription imported ${parsedList.size} servers")
            var firstId = -1L
            for (parsed in parsedList) {
                val id = profileDao.insert(
                    ProfileEntity(
                        name = parsed.name,
                        type = parsed.type,
                        link = parsed.link,
                        outboundJson = Json.encodeToString(JsonObject.serializer(), parsed.outbound),
                        subscriptionUrl = url.trim()
                    )
                )
                if (firstId == -1L) firstId = id
            }
            if (profileDao.getSelected() == null && firstId != -1L) profileDao.selectExclusive(firstId)
        }
    }

    fun update(profile: ProfileEntity, name: String, link: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val trimmedLink = link.trim()
            if (trimmedLink.isNotEmpty() && trimmedLink != profile.link) {
                val parsed = LinkParser.parse(trimmedLink)
                if (parsed == null) {
                    log.w(TAG, "edit server: link parse failed")
                    _error.value = application.getString(R.string.invalid_link_error)
                    return@launch
                }
                profileDao.updateConfig(
                    id = profile.id,
                    name = name.trim().ifBlank { parsed.name },
                    type = parsed.type,
                    link = trimmedLink,
                    outboundJson = Json.encodeToString(JsonObject.serializer(), parsed.outbound)
                )
            } else {
                profileDao.updateName(profile.id, name.trim().ifBlank { profile.name })
            }
        }
    }

    fun refreshSubscription(profile: ProfileEntity) {
        val url = profile.subscriptionUrl.trim()
        if (url.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val parsedList = runCatching { SubscriptionFetcher.fetch(url) }
                .getOrElse {
                    log.w(TAG, "subscription refresh failed", it)
                    _error.value = application.getString(R.string.fetch_subscription_failed, it.message)
                    return@launch
                }
            if (parsedList.isEmpty()) {
                log.w(TAG, "subscription refresh returned no servers")
                _error.value = application.getString(R.string.no_servers_in_subscription)
                return@launch
            }
            val fresh = parsedList.firstOrNull { it.name == profile.name } ?: parsedList.first()
            profileDao.updateConfig(
                id = profile.id,
                name = profile.name,
                type = fresh.type,
                link = fresh.link,
                outboundJson = Json.encodeToString(JsonObject.serializer(), fresh.outbound)
            )
        }
    }

    fun select(profile: ProfileEntity) {
        viewModelScope.launch(Dispatchers.IO) { profileDao.selectExclusive(profile.id) }
    }

    fun delete(profile: ProfileEntity) {
        viewModelScope.launch(Dispatchers.IO) { profileDao.delete(profile) }
    }

    private fun Map<String, Int>.toProfileDelays(): Map<Long, Int> =
        mapNotNull { (tag, delay) ->
            tag.removePrefix("p").toLongOrNull()?.let { it to delay }
        }.toMap()

    private companion object {
        const val TAG = "Servers"
    }
}

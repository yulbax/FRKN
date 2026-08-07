package io.github.yulbax.frkn.data.profile

import androidx.room.withTransaction
import io.github.yulbax.frkn.data.AppDatabase
import io.github.yulbax.frkn.util.LinkParser
import io.github.yulbax.frkn.util.ParsedProfile
import io.github.yulbax.frkn.util.SubscriptionFetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.koin.core.annotation.Single

sealed interface ProfileOperationResult {
    data class Success(val affected: Int = 1) : ProfileOperationResult
    data object InvalidLink : ProfileOperationResult
    data object EmptySubscription : ProfileOperationResult
    data class FetchFailed(val cause: Throwable) : ProfileOperationResult
}

interface SubscriptionProfileSource {
    suspend fun fetch(url: String): List<ParsedProfile>
}

@Single(binds = [SubscriptionProfileSource::class])
class DefaultSubscriptionProfileSource : SubscriptionProfileSource {
    override suspend fun fetch(url: String): List<ParsedProfile> = SubscriptionFetcher.fetch(url)
}

@Single
class ProfileRepository(
    private val database: AppDatabase,
    private val profileDao: ProfileDao,
    private val subscriptionSource: SubscriptionProfileSource
) {
    val profiles: Flow<List<ProfileEntity>> = profileDao.observeAll()
    val selected: Flow<ProfileEntity?> = profileDao.observeSelected()

    suspend fun add(raw: String): ProfileOperationResult {
        val input = raw.trim()
        val parsed = LinkParser.parse(input)
        return when {
            parsed != null -> insertSingle(parsed)
            input.isHttpUrl() -> importSubscription(input)
            else -> ProfileOperationResult.InvalidLink
        }
    }

    suspend fun update(
        profile: ProfileEntity,
        name: String,
        link: String
    ): ProfileOperationResult {
        val trimmedLink = link.trim()
        if (trimmedLink.isNotEmpty() && trimmedLink != profile.link) {
            val parsed = LinkParser.parse(trimmedLink) ?: return ProfileOperationResult.InvalidLink
            profileDao.updateConfig(
                id = profile.id,
                name = name.trim().ifBlank { parsed.name },
                type = parsed.type,
                link = trimmedLink,
                outboundJson = parsed.outboundJson()
            )
        } else {
            profileDao.updateName(profile.id, name.trim().ifBlank { profile.name })
        }
        return ProfileOperationResult.Success()
    }

    suspend fun refreshSubscription(profile: ProfileEntity): ProfileOperationResult {
        val url = profile.subscriptionUrl.trim()
        if (url.isEmpty()) return ProfileOperationResult.Success(affected = 0)
        val parsed = fetchSubscription(url)
        if (parsed !is FetchResult.Success) return parsed.toOperationResult()
        if (parsed.profiles.isEmpty()) return ProfileOperationResult.EmptySubscription

        val fresh = parsed.profiles.firstOrNull { it.name == profile.name } ?: parsed.profiles.first()
        profileDao.updateConfig(
            id = profile.id,
            name = profile.name,
            type = fresh.type,
            link = fresh.link,
            outboundJson = fresh.outboundJson()
        )
        return ProfileOperationResult.Success()
    }

    suspend fun select(profile: ProfileEntity) {
        profileDao.selectExclusive(profile.id)
    }

    suspend fun delete(profile: ProfileEntity) {
        database.withTransaction {
            val deletingSelected = profileDao.getSelected()?.id == profile.id
            profileDao.delete(profile)
            if (deletingSelected) {
                profileDao.getAll().firstOrNull()?.let { profileDao.selectExclusive(it.id) }
            }
        }
    }

    private suspend fun insertSingle(profile: ParsedProfile): ProfileOperationResult {
        database.withTransaction {
            val id = profileDao.insert(profile.toEntity())
            if (profileDao.getSelected() == null) profileDao.selectExclusive(id)
        }
        return ProfileOperationResult.Success()
    }

    private suspend fun importSubscription(url: String): ProfileOperationResult {
        val parsed = fetchSubscription(url)
        if (parsed !is FetchResult.Success) return parsed.toOperationResult()
        if (parsed.profiles.isEmpty()) return ProfileOperationResult.EmptySubscription

        database.withTransaction {
            val ids = profileDao.insertAll(parsed.profiles.map { it.toEntity(subscriptionUrl = url) })
            if (profileDao.getSelected() == null) ids.firstOrNull()?.let { profileDao.selectExclusive(it) }
        }
        return ProfileOperationResult.Success(affected = parsed.profiles.size)
    }

    private suspend fun fetchSubscription(url: String): FetchResult = try {
        FetchResult.Success(subscriptionSource.fetch(url))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        FetchResult.Failed(error)
    }

    private fun ParsedProfile.toEntity(subscriptionUrl: String = "") = ProfileEntity(
        name = name,
        type = type,
        link = link,
        outboundJson = outboundJson(),
        subscriptionUrl = subscriptionUrl
    )

    private fun ParsedProfile.outboundJson(): String =
        Json.encodeToString(JsonObject.serializer(), outbound)

    private fun String.isHttpUrl(): Boolean =
        startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

    private sealed interface FetchResult {
        data class Success(val profiles: List<ParsedProfile>) : FetchResult
        data class Failed(val cause: Throwable) : FetchResult
    }

    private fun FetchResult.toOperationResult(): ProfileOperationResult = when (this) {
        is FetchResult.Success -> ProfileOperationResult.Success(affected = profiles.size)
        is FetchResult.Failed -> ProfileOperationResult.FetchFailed(cause)
    }
}

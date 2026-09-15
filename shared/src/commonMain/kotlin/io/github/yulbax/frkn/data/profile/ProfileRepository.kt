package io.github.yulbax.frkn.data.profile

import io.github.yulbax.frkn.data.AppDatabase
import io.github.yulbax.frkn.util.LinkParser
import io.github.yulbax.frkn.util.ParsedProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

interface SubscriptionProfileSource {
    suspend fun fetch(url: String): List<ParsedProfile>
}

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
                type = parsed.protocol,
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
            type = fresh.protocol,
            link = fresh.link,
            outboundJson = fresh.outboundJson()
        )
        return ProfileOperationResult.Success()
    }

    suspend fun select(profile: ProfileEntity) {
        profileDao.selectExclusive(profile.id)
    }

    suspend fun delete(profile: ProfileEntity) {
        database.transaction {
            profileDao.delete(profile)
            ensureSelection()
        }
    }

    suspend fun ensureSelection(preferredId: Long? = null) {
        if (profileDao.getSelected() != null) return
        val id = preferredId ?: profileDao.getAll().firstOrNull()?.id ?: return
        profileDao.selectExclusive(id)
    }

    private suspend fun insertSingle(profile: ParsedProfile): ProfileOperationResult {
        database.transaction {
            val id = profileDao.insert(profile.toEntity())
            ensureSelection(preferredId = id)
        }
        return ProfileOperationResult.Success()
    }

    private suspend fun importSubscription(url: String): ProfileOperationResult {
        val parsed = fetchSubscription(url)
        if (parsed !is FetchResult.Success) return parsed.toOperationResult()
        if (parsed.profiles.isEmpty()) return ProfileOperationResult.EmptySubscription

        database.transaction {
            val ids = profileDao.insertAll(parsed.profiles.map { it.toEntity(subscriptionUrl = url) })
            ensureSelection(preferredId = ids.firstOrNull())
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
        type = protocol,
        link = link,
        outboundJson = outboundJson(),
        subscriptionUrl = subscriptionUrl
    )

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

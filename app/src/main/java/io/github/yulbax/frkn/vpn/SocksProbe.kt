package io.github.yulbax.frkn.vpn

import android.os.SystemClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

object SocksProbe {
    private const val PROBE_TIMEOUT_MS = 6_000
    private const val GEO_URL = "https://api.ipapi.is/"
    private val COUNTRY_REGEX = Regex("\"(?:cc|country_code)\"\\s*:\\s*\"([A-Za-z]{2})\"")
    private val authenticatorMutex = Mutex()
    private val clients = ConcurrentHashMap<Int, HttpClient>()

    suspend fun latencyMs(socksPort: Int, username: String?, password: String?, url: String): Int? =
        withContext(Dispatchers.IO) {
            withClient(socksPort, username, password) { client ->
                val start = SystemClock.elapsedRealtime()
                val response = client.get(url)
                if (response.status.value in 200..399) {
                    (SystemClock.elapsedRealtime() - start).toInt().coerceAtLeast(1)
                } else {
                    null
                }
            }
        }

    suspend fun resolveCountry(socksPort: Int, username: String?, password: String?): String? =
        withContext(Dispatchers.IO) {
            withClient(socksPort, username, password) { client ->
                val body = client.get(GEO_URL).bodyAsText()
                COUNTRY_REGEX.find(body)?.groupValues?.get(1)?.uppercase()
            }
        }

    fun close() {
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
    }

    private fun clientFor(port: Int): HttpClient = clients.getOrPut(port) {
        HttpClient(Android) {
            expectSuccess = false
            engine {
                proxy = ProxyBuilder.socks("127.0.0.1", port)
                connectTimeout = PROBE_TIMEOUT_MS
                socketTimeout = PROBE_TIMEOUT_MS
            }
        }
    }

    private suspend fun <T> withClient(
        port: Int,
        username: String?,
        password: String?,
        block: suspend (HttpClient) -> T
    ): T? {
        val useAuth = username != null && password != null
        suspend fun execute(): T? = try {
            withTimeoutOrNull(PROBE_TIMEOUT_MS.milliseconds) { block(clientFor(port)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

        if (!useAuth) return execute()
        return authenticatorMutex.withLock {
            val credentials = PasswordAuthentication(username, password.toCharArray())
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication = credentials
            })
            try {
                execute()
            } finally {
                Authenticator.setDefault(null)
            }
        }
    }

}

package io.github.yulbax.frkn.vpn

import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.util.concurrent.atomic.AtomicReference

object SocksProbe {
    private const val GEO_URL = "https://api.country.is/"
    private const val PROBE_TIMEOUT_MS = 6_000
    private val COUNTRY_REGEX = Regex("\"country\"\\s*:\\s*\"([A-Za-z]{2})\"")
    private val proxyCredentials = AtomicReference<PasswordAuthentication?>(null)

    init {
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? = proxyCredentials.get()
        })
    }

    suspend fun latencyMs(socksPort: Int, username: String?, password: String?, url: String): Int? =
        withContext(Dispatchers.IO) {
            withClient(socksPort, username, password) { client ->
                val start = System.currentTimeMillis()
                val response = client.get(url)
                if (response.status.value in 200..399) {
                    (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
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

    private suspend fun <T> withClient(
        port: Int,
        username: String?,
        password: String?,
        block: suspend (HttpClient) -> T
    ): T? {
        val useAuth = username != null && password != null
        if (useAuth) proxyCredentials.set(PasswordAuthentication(username, password.toCharArray()))
        return try {
            HttpClient(Android) {
                expectSuccess = false
                engine {
                    proxy = ProxyBuilder.socks("127.0.0.1", port)
                    connectTimeout = PROBE_TIMEOUT_MS
                    socketTimeout = PROBE_TIMEOUT_MS
                }
            }.use { client -> block(client) }
        } catch (_: Throwable) {
            null
        } finally {
            if (useAuth) proxyCredentials.set(null)
        }
    }
}

package io.github.yulbax.frkn.desktop

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

class ClashApi(private val port: Int, private val secret: String) {

    fun isReady(): Boolean = request("GET", "/version", timeoutMs = 1_000) { it.responseCode == 200 } ?: false

    fun select(group: String, outbound: String): Boolean =
        request("PUT", "/proxies/${encode(group)}", body = "{\"name\":\"${outbound.replace("\"", "\\\"")}\"}") {
            it.responseCode in 200..299
        } ?: false

    fun delay(outbound: String, url: String, timeoutMs: Int): Int? =
        request(
            "GET",
            "/proxies/${encode(outbound)}/delay?timeout=$timeoutMs&url=${encode(url)}",
            timeoutMs = timeoutMs + 2_000
        ) { connection ->
            if (connection.responseCode != 200) return@request null
            val body = connection.inputStream.use { it.readBytes().decodeToString() }
            DELAY_REGEX.find(body)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
        }

    fun streamTraffic(onSample: (up: Long, down: Long) -> Unit): HttpURLConnection {
        val connection = open("GET", "/traffic", timeoutMs = 0)
        Thread({
            runCatching {
                connection.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val up = UP_REGEX.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: return@forEach
                        val down = DOWN_REGEX.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: return@forEach
                        onSample(up, down)
                    }
                }
            }
        }, "sing-box-traffic").apply { isDaemon = true }.start()
        return connection
    }

    private fun <T> request(
        method: String,
        path: String,
        body: String? = null,
        timeoutMs: Int = 5_000,
        read: (HttpURLConnection) -> T
    ): T? = runCatching {
        val connection = open(method, path, timeoutMs)
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.encodeToByteArray()) }
            }
            read(connection)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun open(method: String, path: String, timeoutMs: Int): HttpURLConnection =
        (URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = if (timeoutMs == 0) 5_000 else timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Authorization", "Bearer $secret")
        }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    private companion object {
        val DELAY_REGEX = Regex("\"delay\"\\s*:\\s*(\\d+)")
        val UP_REGEX = Regex("\"up\"\\s*:\\s*(\\d+)")
        val DOWN_REGEX = Regex("\"down\"\\s*:\\s*(\\d+)")
    }
}

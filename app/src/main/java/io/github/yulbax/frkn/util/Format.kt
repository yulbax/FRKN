package io.github.yulbax.frkn.util

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.URLDecoder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

fun formatRate(bytesPerSec: Long): String = when {
    bytesPerSec >= 1_000_000 -> "%.1f MB/s".format(bytesPerSec / 1_000_000.0)
    bytesPerSec >= 1_000 -> "%.0f KB/s".format(bytesPerSec / 1_000.0)
    else -> "$bytesPerSec B/s"
}

data class ParsedProfile(
    val name: String,
    val type: String,
    val outbound: JsonObject,
    val link: String
)

@OptIn(ExperimentalEncodingApi::class)
object LinkParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(rawLink: String): ParsedProfile? {
        val link = rawLink.trim()
        return runCatching {
            when {
                link.startsWith("vmess://") -> parseVmess(link)
                link.startsWith("vless://") -> parseVless(link)
                link.startsWith("trojan://") -> parseTrojan(link)
                link.startsWith("ss://") -> parseShadowsocks(link)
                link.startsWith("hysteria2://") -> parseHysteria2(link)
                link.startsWith("hy2://") -> parseHysteria2(link)
                else -> null
            }
        }.getOrNull()
    }

    private fun parseVmess(link: String): ParsedProfile {
        val decoded = decodeBase64(link.removePrefix("vmess://"))
        val obj = json.parseToJsonElement(decoded).let { it as JsonObject }
        fun str(key: String): String = obj[key]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() } ?: ""
        val name = str("ps").ifEmpty { str("add") }
        val net = str("net").ifEmpty { "tcp" }
        val tls = str("tls") == "tls"
        val host = str("host")
        val path = str("path").ifEmpty { "/" }
        val sni = str("sni").ifEmpty { host }.ifEmpty { str("add") }

        val outbound = buildJsonObject {
            put("type", "vmess")
            put("server", str("add"))
            put("server_port", str("port").toIntOrNull() ?: 443)
            put("uuid", str("id"))
            put("security", str("scy").ifEmpty { "auto" })
            put("alter_id", str("aid").toIntOrNull() ?: 0)
            transport(net, path, host, str("path"))?.let { put("transport", it) }
            if (tls) put("tls", tlsBlock(sni, null, null, null, fp = "", insecure = false, alpn = str("alpn")))
        }
        return ParsedProfile(name, "vmess", outbound, link)
    }

    private fun parseVless(link: String): ParsedProfile {
        val uri = URI(link)
        val q = uri.queryMap()
        val security = q["security"] ?: "none"
        val net = q["type"] ?: "tcp"
        val host = q["host"] ?: ""
        val path = q["path"]?.let { decode(it) } ?: "/"
        val sni = q["sni"] ?: host.ifEmpty { uri.host }

        val outbound = buildJsonObject {
            put("type", "vless")
            put("server", uri.host)
            put("server_port", uri.port)
            put("uuid", uri.userInfo ?: "")
            q["flow"]?.takeIf { it.isNotEmpty() }?.let { put("flow", it) }
            transport(net, path, host, q["serviceName"])?.let { put("transport", it) }
            when (security) {
                "tls" -> put("tls", tlsBlock(sni, null, null, null, q["fp"] ?: "", q["allowInsecure"] == "1", q["alpn"] ?: ""))
                "reality" -> put("tls", tlsBlock(sni, true, q["pbk"], q["sid"], q["fp"] ?: "chrome", false, q["alpn"] ?: ""))
            }
        }
        return ParsedProfile(uri.fragmentName(uri.host), "vless", outbound, link)
    }

    private fun parseTrojan(link: String): ParsedProfile {
        val uri = URI(link)
        val q = uri.queryMap()
        val net = q["type"] ?: "tcp"
        val host = q["host"] ?: ""
        val sni = q["sni"] ?: host.ifEmpty { uri.host }

        val outbound = buildJsonObject {
            put("type", "trojan")
            put("server", uri.host)
            put("server_port", uri.port)
            put("password", uri.userInfo ?: "")
            transport(net, q["path"]?.let { decode(it) } ?: "/", host, q["serviceName"])?.let { put("transport", it) }
            put("tls", tlsBlock(sni, null, null, null, q["fp"] ?: "", q["allowInsecure"] == "1", q["alpn"] ?: ""))
        }
        return ParsedProfile(uri.fragmentName(uri.host), "trojan", outbound, link)
    }

    private fun parseShadowsocks(link: String): ParsedProfile {
        val body = link.removePrefix("ss://")
        val name = body.substringAfter('#', "").let { if (it.isEmpty()) "" else decode(it) }
        val main = body.substringBefore('#')

        val method: String
        val password: String
        val host: String
        val port: Int
        if (main.contains('@')) {
            val userInfo = main.substringBefore('@')
            val server = main.substringAfter('@')
            val creds = if (userInfo.contains(':')) userInfo else decodeBase64(userInfo)
            method = creds.substringBefore(':')
            password = creds.substringAfter(':')
            host = server.substringBeforeLast(':').substringBefore('?')
            port = server.substringAfterLast(':').substringBefore('?').toIntOrNull() ?: 8388
        } else {
            val decoded = decodeBase64(main)
            method = decoded.substringBefore(':')
            password = decoded.substringAfter(':').substringBeforeLast('@')
            val server = decoded.substringAfterLast('@')
            host = server.substringBeforeLast(':')
            port = server.substringAfterLast(':').toIntOrNull() ?: 8388
        }

        val outbound = buildJsonObject {
            put("type", "shadowsocks")
            put("server", host)
            put("server_port", port)
            put("method", method)
            put("password", password)
        }
        return ParsedProfile(name.ifEmpty { host }, "shadowsocks", outbound, link)
    }

    private fun parseHysteria2(link: String): ParsedProfile {
        val uri = URI(link.replaceFirst("hy2://", "hysteria2://"))
        val q = uri.queryMap()
        val sni = q["sni"] ?: uri.host

        val outbound = buildJsonObject {
            put("type", "hysteria2")
            put("server", uri.host)
            put("server_port", if (uri.port > 0) uri.port else 443)
            put("password", uri.userInfo ?: "")
            q["obfs"]?.takeIf { it.isNotEmpty() }?.let { obfs ->
                putJsonObject("obfs") {
                    put("type", obfs)
                    q["obfs-password"]?.let { put("password", it) }
                }
            }
            put("tls", tlsBlock(sni, null, null, null, "", q["insecure"] == "1", q["alpn"] ?: ""))
        }
        return ParsedProfile(uri.fragmentName(uri.host), "hysteria2", outbound, link)
    }

    private fun transport(net: String, path: String, host: String, serviceName: String?): JsonObject? =
        when (net) {
            "ws" -> buildJsonObject {
                put("type", "ws")
                put("path", path)
                if (host.isNotEmpty()) putJsonObject("headers") { put("Host", host) }
            }
            "grpc" -> buildJsonObject {
                put("type", "grpc")
                put("service_name", serviceName ?: path.trim('/'))
            }
            "httpupgrade" -> buildJsonObject {
                put("type", "httpupgrade")
                put("path", path)
                if (host.isNotEmpty()) put("host", host)
            }
            "http" -> buildJsonObject {
                put("type", "http")
                putJsonArray("path") { add(path) }
                if (host.isNotEmpty()) putJsonArray("host") { add(host) }
            }
            else -> null
        }

    private fun tlsBlock(
        serverName: String,
        reality: Boolean?,
        publicKey: String?,
        shortId: String?,
        fp: String,
        insecure: Boolean,
        alpn: String = ""
    ): JsonObject = buildJsonObject {
        put("enabled", true)
        if (serverName.isNotEmpty()) put("server_name", serverName)
        if (insecure) put("insecure", true)
        alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let {
            putJsonArray("alpn") { it.forEach { proto -> add(proto) } }
        }
        if (fp.isNotEmpty()) putJsonObject("utls") {
            put("enabled", true)
            put("fingerprint", safeFingerprint(fp))
        }
        if (reality == true) putJsonObject("reality") {
            put("enabled", true)
            put("public_key", publicKey ?: "")
            put("short_id", shortId ?: "")
        }
    }

    fun safeFingerprint(fp: String): String =
        when (fp.lowercase()) {
            "randomized" -> "random"
            else -> fp
        }

    private fun URI.queryMap(): Map<String, String> {
        val raw = rawQuery ?: return emptyMap()
        return raw.split('&').mapNotNull {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=', "")
            if (k.isEmpty()) null else k to decode(v)
        }.toMap()
    }

    private fun URI.fragmentName(fallback: String): String =
        fragment?.takeIf { it.isNotEmpty() }?.let { decode(it) } ?: fallback

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun decodeBase64(input: String): String {
        val s = input.trim().replace('-', '+').replace('_', '/')
        val padded = s.padEnd((s.length + 3) / 4 * 4, '=')
        return runCatching { String(Base64.decode(padded)) }
            .getOrElse { String(Base64.UrlSafe.decode(input.trim().trimEnd('='))) }
    }
}

@OptIn(ExperimentalEncodingApi::class)
object SubscriptionFetcher {
    suspend fun fetch(url: String): List<ParsedProfile> {
        val body = HttpClient(Android).use { client ->
            client.get(url).bodyAsText()
        }
        return parseBody(body)
    }

    fun parseBody(body: String): List<ParsedProfile> {
        val text = if (body.contains("://")) body else decodeBase64OrSelf(body)
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { LinkParser.parse(it) }
            .toList()
    }

    private fun decodeBase64OrSelf(input: String): String {
        val s = input.trim().replace('-', '+').replace('_', '/').replace("\n", "").replace("\r", "")
        val padded = s.padEnd((s.length + 3) / 4 * 4, '=')
        return runCatching { String(Base64.decode(padded)) }.getOrDefault(input)
    }
}

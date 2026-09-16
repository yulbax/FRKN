package io.github.yulbax.frkn.util

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

enum class ProxyProtocol(val wire: String, val schemes: List<String>) {
    VMESS("vmess", listOf("vmess")),
    VLESS("vless", listOf("vless")),
    TROJAN("trojan", listOf("trojan")),
    SHADOWSOCKS("shadowsocks", listOf("ss")),
    HYSTERIA2("hysteria2", listOf("hysteria2", "hy2"));

    companion object {
        val allSchemes: List<String> get() = entries.flatMap { it.schemes }

        fun fromWire(value: String): ProxyProtocol? = entries.firstOrNull { it.wire == value }

        fun fromLink(link: String): ProxyProtocol? =
            entries.firstOrNull { protocol -> protocol.schemes.any { link.startsWith("$it://") } }
    }
}

data class ParsedProfile(
    val name: String,
    val protocol: ProxyProtocol,
    val outbound: JsonObject,
    val link: String
) {
    fun outboundJson(): String = compactJson.encodeToString(JsonObject.serializer(), outbound)
}

private val compactJson = Json { ignoreUnknownKeys = true }

object LinkParser {

    fun parse(rawLink: String): ParsedProfile? {
        val link = rawLink.trim()
        val protocol = ProxyProtocol.fromLink(link) ?: return null
        return runCatching {
            when (protocol) {
                ProxyProtocol.VMESS -> parseVmess(link)
                ProxyProtocol.VLESS -> parseVless(link)
                ProxyProtocol.TROJAN -> parseTrojan(link)
                ProxyProtocol.SHADOWSOCKS -> parseShadowsocks(link)
                ProxyProtocol.HYSTERIA2 -> parseHysteria2(link)
            }
        }.getOrNull()
    }

    private fun parseVmess(link: String): ParsedProfile {
        val decoded = decodeBase64Lenient(link.removePrefix("vmess://")) ?: error("invalid vmess payload")
        val obj = compactJson.parseToJsonElement(decoded).let { it as JsonObject }
        fun str(key: String): String = obj[key]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() } ?: ""
        val name = str("ps").ifEmpty { str("add") }
        val net = str("net").ifEmpty { "tcp" }
        val tls = str("tls") == "tls"
        val host = str("host")
        val path = str("path").ifEmpty { "/" }
        val sni = str("sni").ifEmpty { host }.ifEmpty { str("add") }

        val outbound = buildJsonObject {
            put("type", ProxyProtocol.VMESS.wire)
            put("server", str("add"))
            put("server_port", str("port").toIntOrNull() ?: 443)
            put("uuid", str("id"))
            put("security", str("scy").ifEmpty { "auto" })
            put("alter_id", str("aid").toIntOrNull() ?: 0)
            transport(net, path, host, str("path"))?.let { put("transport", it) }
            if (tls) put("tls", tlsBlock(sni, null, null, null, fp = "", insecure = false, alpn = str("alpn")))
        }
        return ParsedProfile(name, ProxyProtocol.VMESS, outbound, link)
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
            put("type", ProxyProtocol.VLESS.wire)
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
        return ParsedProfile(uri.fragmentName(uri.host), ProxyProtocol.VLESS, outbound, link)
    }

    private fun parseTrojan(link: String): ParsedProfile {
        val uri = URI(link)
        val q = uri.queryMap()
        val net = q["type"] ?: "tcp"
        val host = q["host"] ?: ""
        val sni = q["sni"] ?: host.ifEmpty { uri.host }

        val outbound = buildJsonObject {
            put("type", ProxyProtocol.TROJAN.wire)
            put("server", uri.host)
            put("server_port", uri.port)
            put("password", uri.userInfo ?: "")
            transport(net, q["path"]?.let { decode(it) } ?: "/", host, q["serviceName"])?.let { put("transport", it) }
            put("tls", tlsBlock(sni, null, null, null, q["fp"] ?: "", q["allowInsecure"] == "1", q["alpn"] ?: ""))
        }
        return ParsedProfile(uri.fragmentName(uri.host), ProxyProtocol.TROJAN, outbound, link)
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
            val creds = if (userInfo.contains(':')) userInfo else decodeBase64Lenient(userInfo) ?: error("invalid ss credentials")
            method = creds.substringBefore(':')
            password = creds.substringAfter(':')
            host = server.substringBeforeLast(':').substringBefore('?')
            port = server.substringAfterLast(':').substringBefore('?').toIntOrNull() ?: 8388
        } else {
            val decoded = decodeBase64Lenient(main) ?: error("invalid ss payload")
            method = decoded.substringBefore(':')
            password = decoded.substringAfter(':').substringBeforeLast('@')
            val server = decoded.substringAfterLast('@')
            host = server.substringBeforeLast(':')
            port = server.substringAfterLast(':').toIntOrNull() ?: 8388
        }

        val outbound = buildJsonObject {
            put("type", ProxyProtocol.SHADOWSOCKS.wire)
            put("server", host)
            put("server_port", port)
            put("method", method)
            put("password", password)
        }
        return ParsedProfile(name.ifEmpty { host }, ProxyProtocol.SHADOWSOCKS, outbound, link)
    }

    private fun parseHysteria2(link: String): ParsedProfile {
        val uri = URI(link.replaceFirst("hy2://", "hysteria2://"))
        val q = uri.queryMap()
        val sni = q["sni"] ?: uri.host

        val outbound = buildJsonObject {
            put("type", ProxyProtocol.HYSTERIA2.wire)
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
        return ParsedProfile(uri.fragmentName(uri.host), ProxyProtocol.HYSTERIA2, outbound, link)
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
            put("fingerprint", fp)
        }
        if (reality == true) putJsonObject("reality") {
            put("enabled", true)
            put("public_key", publicKey ?: "")
            put("short_id", shortId ?: "")
        }
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
}

object SubscriptionParser {
    fun parseBody(body: String): List<ParsedProfile> {
        val text = if (body.contains("://")) body else decodeBase64Lenient(body) ?: body
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { LinkParser.parse(it) }
            .toList()
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun decodeBase64Lenient(input: String): String? {
    val normalized = input.trim()
        .replace("\n", "")
        .replace("\r", "")
        .replace('-', '+')
        .replace('_', '/')
        .trimEnd('=')
    val padded = normalized.padEnd((normalized.length + 3) / 4 * 4, '=')
    return runCatching { String(Base64.decode(padded)) }.getOrNull()
}

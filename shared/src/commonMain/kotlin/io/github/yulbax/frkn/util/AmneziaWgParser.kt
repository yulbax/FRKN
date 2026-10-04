package io.github.yulbax.frkn.util

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

object AmneziaWgParser {
    const val ENDPOINT_TYPE = "awg"

    private const val INTERFACE_SECTION = "interface"
    private const val PEER_SECTION = "peer"
    private const val DEFAULT_PORT = 51820

    private val OBFUSCATION_NUMBERS = listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4")
    private val OBFUSCATION_RANGES = listOf("h1", "h2", "h3", "h4").associateWith { it } + mapOf(
        "contentpaddingaddition" to "content_padding_addition",
        "rekeyaftertime" to "rekey_after_time",
        "rekeytimeout" to "rekey_timeout",
        "rejectaftertime" to "reject_after_time",
        "keepalivetimeout" to "keepalive_timeout",
        "maxhandshakeattempts" to "max_handshake_attempts"
    )
    private val OBFUSCATION_TEXT = mapOf(
        "i1" to "i1", "i2" to "i2", "i3" to "i3", "i4" to "i4", "i5" to "i5",
        "headerprotectionkey" to "header_protection_key"
    )
    private val OBFUSCATION_FLAGS = mapOf(
        "randomtrailers" to "random_trailers",
        "disablecookies" to "disable_cookies"
    )

    fun looksLikeConfig(text: String): Boolean =
        text.lineSequence().any { it.trim().equals("[$INTERFACE_SECTION]", ignoreCase = true) }

    fun parse(text: String, fallbackMtu: Int? = null): ParsedProfile {
        val sections = sections(text)
        val interfaceSection = sections.firstOrNull { it.first == INTERFACE_SECTION }?.second
            ?: error("missing [Interface] section")
        val peerSections = sections.filter { it.first == PEER_SECTION }.map { it.second }
        require(peerSections.isNotEmpty()) { "missing [Peer] section" }

        val privateKey = interfaceSection["privatekey"] ?: error("missing PrivateKey")
        val addresses = interfaceSection["address"]?.splitList() ?: error("missing Address")

        val endpoint = peerSections.first()["endpoint"] ?: error("missing Endpoint")
        val host = endpoint.substringBeforeLast(':').trim('[', ']')

        val outbound = buildJsonObject {
            put("type", ENDPOINT_TYPE)
            putJsonArray("address") { addresses.forEach { add(withPrefix(it)) } }
            put("private_key", privateKey)
            (interfaceSection["mtu"]?.toIntOrNull() ?: fallbackMtu)?.let { put("mtu", it) }
            interfaceSection["listenport"]?.toIntOrNull()?.let { put("listen_port", it) }
            putJsonArray("peers") { peerSections.forEach { add(peer(it)) } }
            OBFUSCATION_NUMBERS.forEach { key ->
                interfaceSection[key]?.toLongOrNull()?.let { put(key, it) }
            }
            OBFUSCATION_RANGES.forEach { (key, jsonName) ->
                interfaceSection[key]?.let { put(jsonName, it) }
            }
            OBFUSCATION_TEXT.forEach { (key, jsonName) ->
                interfaceSection[key]?.let { put(jsonName, it) }
            }
            OBFUSCATION_FLAGS.forEach { (key, jsonName) ->
                interfaceSection[key]?.let { put(jsonName, it.isEnabled()) }
            }
        }
        return ParsedProfile(host, ProxyProtocol.AMNEZIAWG, outbound, text)
    }

    private fun peer(values: Map<String, String>): JsonObject {
        val endpoint = values["endpoint"] ?: error("missing Endpoint")
        val publicKey = values["publickey"] ?: error("missing PublicKey")
        return buildJsonObject {
            put("address", endpoint.substringBeforeLast(':').trim('[', ']'))
            put("port", endpoint.substringAfterLast(':').toIntOrNull() ?: DEFAULT_PORT)
            put("public_key", publicKey)
            values["presharedkey"]?.let { put("preshared_key", it) }
            putJsonArray("allowed_ips") {
                (values["allowedips"]?.splitList() ?: listOf("0.0.0.0/0")).forEach { add(it) }
            }
            values["persistentkeepalive"]?.lowerBound()?.let { put("persistent_keepalive_interval", it) }
        }
    }

    private fun sections(text: String): List<Pair<String, Map<String, String>>> {
        val sections = mutableListOf<Pair<String, MutableMap<String, String>>>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("[") && line.endsWith("]") ->
                    sections += line.trim('[', ']').lowercase() to mutableMapOf()
                line.contains('=') -> sections.lastOrNull()?.second?.put(
                    line.substringBefore('=').trim().lowercase(),
                    line.substringAfter('=').trim()
                )
            }
        }
        return sections
    }

    private fun String.lowerBound(): Int? = substringBefore('-').trim().toIntOrNull()

    private fun String.isEnabled(): Boolean =
        equals("on", ignoreCase = true) || equals("true", ignoreCase = true) || this == "1"

    private fun String.splitList(): List<String> =
        split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun withPrefix(address: String): String = when {
        address.contains('/') -> address
        address.contains(':') -> "$address/128"
        else -> "$address/32"
    }
}

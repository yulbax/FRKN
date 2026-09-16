package io.github.yulbax.frkn.vpn.singbox

import io.github.yulbax.frkn.vpn.core.EngineProxy
import io.github.yulbax.frkn.vpn.core.NetworkOptions
import io.github.yulbax.frkn.vpn.core.TlsFingerprint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

sealed interface AppRouting {
    val appRuleKey: String

    data object AndroidPackages : AppRouting {
        override val appRuleKey: String = "package_name"
    }

    data class DesktopProcesses(
        val directProcesses: List<String>,
        val controlApi: ControlApi
    ) : AppRouting {
        override val appRuleKey: String = "process_name"
    }
}

data class ControlApi(val port: Int, val secret: String)

object ConfigBuilder {

    private val json = Json { prettyPrint = false }
    private const val TUN_INET6 = "fdfe:dcba:9876::1/126"
    private const val TUN_INET4 = "172.18.0.1/30"

    const val PROXY_GROUP_TAG = "proxy"
    private const val PROBE_INBOUND_TAG = "probe-in"

    fun build(
        proxies: List<EngineProxy>,
        activeProxyTag: String,
        byeDpiPackages: List<String>,
        vpnPackages: List<String>,
        tunneledPackages: List<String>,
        byeDpiPort: Int,
        probePort: Int,
        probeUser: String,
        probePass: String,
        options: NetworkOptions = NetworkOptions(),
        routing: AppRouting = AppRouting.AndroidPackages
    ): String {
        require(proxies.isNotEmpty()) { "No proxy outbounds" }
        val ipv6Enabled = options.ipv6Mode.ipv6Enabled

        val proxyOutbounds = proxies.map { proxy ->
            val outbound = JsonObject((Json.parseToJsonElement(proxy.outboundDescriptor) as JsonObject).toMutableMap().apply {
                put("tag", JsonPrimitive(proxy.tag))
            })
            applyPreferredFingerprint(outbound, options.preferredFingerprint)
        }
        val defaultTag = proxies.firstOrNull { it.tag == activeProxyTag }?.tag ?: proxies.first().tag

        val config = buildJsonObject {
            putJsonObject("log") {
                put("level", "warn")
                put("timestamp", true)
                put("output", "box.log")
            }

            putJsonObject("dns") {
                putJsonArray("servers") {
                    add(buildJsonObject {
                        put("tag", "remote")
                        put("type", "tls")
                        put("server", options.dnsRemote)
                        put("detour", PROXY_GROUP_TAG)
                    })
                    add(buildJsonObject {
                        put("tag", "local")
                        put("type", "udp")
                        put("server", options.dnsDirect)
                    })
                    if (byeDpiPackages.isNotEmpty()) {
                        add(buildJsonObject {
                            put("tag", "byedpi-dns")
                            put("type", "tls")
                            put("server", options.dnsRemote)
                            put("detour", "byedpi")
                        })
                    }
                }
                if (byeDpiPackages.isNotEmpty()) {
                    putJsonArray("rules") {
                        add(buildJsonObject {
                            putJsonArray(routing.appRuleKey) {
                                byeDpiPackages.forEach { add(it) }
                            }
                            put("action", "route")
                            put("server", "byedpi-dns")
                        })
                    }
                }
                put("final", "remote")
                put("strategy", options.ipv6Mode.dnsStrategy)
            }

            putJsonArray("inbounds") {
                add(buildJsonObject {
                    put("type", "tun")
                    put("tag", "tun-in")
                    putJsonArray("address") {
                        add(TUN_INET4)
                        if (ipv6Enabled) add(TUN_INET6)
                    }
                    put("mtu", options.mtu)
                    put("auto_route", true)
                    put("stack", options.tunStack.wire)
                    if (routing is AppRouting.DesktopProcesses) put("strict_route", true)
                    if (routing is AppRouting.AndroidPackages && tunneledPackages.isNotEmpty()) {
                        putJsonArray("include_package") {
                            tunneledPackages.forEach { add(it) }
                        }
                    }
                })
                add(buildJsonObject {
                    put("type", "socks")
                    put("tag", PROBE_INBOUND_TAG)
                    put("listen", "127.0.0.1")
                    put("listen_port", probePort)
                    putJsonArray("users") {
                        add(buildJsonObject {
                            put("username", probeUser)
                            put("password", probePass)
                        })
                    }
                })
            }

            putJsonArray("outbounds") {
                proxyOutbounds.forEach { add(it) }
                add(buildJsonObject {
                    put("type", "selector")
                    put("tag", PROXY_GROUP_TAG)
                    putJsonArray("outbounds") { proxies.forEach { add(it.tag) } }
                    put("default", defaultTag)
                    put("interrupt_exist_connections", true)
                })
                add(buildJsonObject {
                    put("type", "socks")
                    put("tag", "byedpi")
                    put("server", "127.0.0.1")
                    put("server_port", byeDpiPort)
                    put("version", "5")
                })
                add(buildJsonObject {
                    put("type", "direct")
                    put("tag", "direct")
                })
            }

            putJsonObject("route") {
                putJsonArray("rules") {
                    if (options.sniff) add(buildJsonObject { put("action", "sniff") })
                    add(buildJsonObject {
                        put("protocol", "dns")
                        put("action", "hijack-dns")
                    })
                    add(buildJsonObject {
                        putJsonArray("inbound") { add(PROBE_INBOUND_TAG) }
                        put("action", "route")
                        put("outbound", PROXY_GROUP_TAG)
                    })
                    if (routing is AppRouting.DesktopProcesses && routing.directProcesses.isNotEmpty()) {
                        add(buildJsonObject {
                            putJsonArray(routing.appRuleKey) { routing.directProcesses.forEach { add(it) } }
                            put("action", "route")
                            put("outbound", "direct")
                        })
                    }
                    if (options.bypassLan) {
                        add(buildJsonObject {
                            put("ip_is_private", true)
                            put("action", "route")
                            put("outbound", "direct")
                        })
                    }
                    if (byeDpiPackages.isNotEmpty()) {
                        add(buildJsonObject {
                            putJsonArray(routing.appRuleKey) {
                                byeDpiPackages.forEach { add(it) }
                            }
                            put("action", "route")
                            put("outbound", "byedpi")
                        })
                    }
                    if (vpnPackages.isNotEmpty()) {
                        add(buildJsonObject {
                            putJsonArray(routing.appRuleKey) {
                                vpnPackages.forEach { add(it) }
                            }
                            put("action", "route")
                            put("outbound", PROXY_GROUP_TAG)
                        })
                    }
                    if (routing is AppRouting.AndroidPackages) add(buildJsonObject { put("action", "reject") })
                }
                put("final", if (routing is AppRouting.AndroidPackages) PROXY_GROUP_TAG else "direct")
                put("auto_detect_interface", true)
                put("default_domain_resolver", "local")
            }

            if (routing is AppRouting.DesktopProcesses) {
                putJsonObject("experimental") {
                    putJsonObject("clash_api") {
                        put("external_controller", "127.0.0.1:${routing.controlApi.port}")
                        put("secret", routing.controlApi.secret)
                    }
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), config)
    }

    private fun applyPreferredFingerprint(outbound: JsonObject, preferred: TlsFingerprint?): JsonObject {
        if (preferred == null) return outbound
        val tls = outbound["tls"] as? JsonObject ?: return outbound
        val utls = tls["utls"] as? JsonObject ?: buildJsonObject { put("enabled", true) }
        val newUtls = JsonObject(utls.toMutableMap().apply {
            put("enabled", JsonPrimitive(true))
            put("fingerprint", JsonPrimitive(preferred.wire))
        })
        val newTls = JsonObject(tls.toMutableMap().apply { put("utls", newUtls) })
        return JsonObject(outbound.toMutableMap().apply { put("tls", newTls) })
    }
}

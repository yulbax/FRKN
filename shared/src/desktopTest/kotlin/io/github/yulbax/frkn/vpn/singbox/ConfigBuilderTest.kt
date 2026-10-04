package io.github.yulbax.frkn.vpn.singbox

import io.github.yulbax.frkn.proxy.LinkParser
import io.github.yulbax.frkn.proxy.ProxyProtocol
import io.github.yulbax.frkn.vpn.core.Ipv6Mode
import io.github.yulbax.frkn.vpn.core.NetworkOptions
import io.github.yulbax.frkn.vpn.core.TlsFingerprint
import io.github.yulbax.frkn.vpn.core.TunStack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBuilderTest {
    private val proxies = listOf(
        ProxyProtocol.VLESS.engineProxy("p1", """{"type":"vless","server":"a.example","server_port":443,"uuid":"u","tls":{"enabled":true,"server_name":"a.example"}}"""),
        ProxyProtocol.TROJAN.engineProxy("p2", """{"type":"trojan","server":"b.example","server_port":443,"password":"x","tls":{"enabled":true,"utls":{"enabled":true,"fingerprint":"firefox"}}}""")
    )

    @Test
    fun androidDefaultConfigIsUnchanged() {
        assertEquals(
            golden("android-default.json"),
            ConfigBuilder.build(proxies, "p2", listOf("org.telegram"), listOf("com.vpn.app"), listOf("org.telegram", "com.vpn.app"), 1081, 2080, "user", "pass")
        )
    }

    @Test
    fun androidCustomConfigIsUnchanged() {
        assertEquals(
            golden("android-custom.json"),
            ConfigBuilder.build(
                proxies, "p1", emptyList(), listOf("com.vpn.app"), listOf("com.vpn.app"), 1081, 2080, "user", "pass",
                NetworkOptions(TunStack.SYSTEM, 1500, Ipv6Mode.PREFER, "9.9.9.9", "8.8.8.8", sniff = false, bypassLan = true, preferredFingerprint = TlsFingerprint.CHROME)
            )
        )
    }

    @Test
    fun desktopConfigRoutesByProcessAndDefaultsToDirect() {
        val config = Json.parseToJsonElement(
            ConfigBuilder.build(
                proxies, "p1", listOf("discord.exe"), listOf("telegram.exe"), listOf("discord.exe", "telegram.exe"),
                1081, 2080, "user", "pass",
                routing = AppRouting.DesktopProcesses(listOf("ciadpi.exe"), ControlApi(9090, "secret"))
            )
        ).jsonObject

        val route = config.getValue("route").jsonObject
        val rules = route.getValue("rules").jsonArray.map { it.jsonObject }
        assertEquals("direct", route.getValue("final").jsonPrimitive.content)
        assertFalse(rules.any { it["action"]?.jsonPrimitive?.content == "reject" })
        assertEquals(listOf("ciadpi.exe"), rules.processRule("direct"))
        assertEquals(listOf("discord.exe"), rules.processRule("byedpi"))
        assertEquals(listOf("telegram.exe"), rules.processRule("proxy"))
        assertTrue(rules.none { it.containsKey("package_name") })

        val tun = config.getValue("inbounds").jsonArray.first().jsonObject
        assertFalse(tun.containsKey("include_package"))
        assertEquals("true", tun.getValue("strict_route").jsonPrimitive.content)

        val clash = config.getValue("experimental").jsonObject.getValue("clash_api").jsonObject
        assertEquals("127.0.0.1:9090", clash.getValue("external_controller").jsonPrimitive.content)
    }

    @Test
    fun desktopRouteAllTrafficSendsUnassignedProcessesThroughTheProxy() {
        fun finalOf(routeAll: Boolean): String {
            val config = Json.parseToJsonElement(
                ConfigBuilder.build(
                    proxies, "p1", emptyList(), emptyList(), emptyList(), 1081, 2080, "user", "pass",
                    NetworkOptions(routeAllTraffic = routeAll),
                    routing = AppRouting.DesktopProcesses(listOf("ciadpi.exe"), ControlApi(9090, "secret"))
                )
            ).jsonObject
            val route = config.getValue("route").jsonObject
            assertEquals(listOf("ciadpi.exe"), route.getValue("rules").jsonArray.map { it.jsonObject }.processRule("direct"))
            return route.getValue("final").jsonPrimitive.content
        }

        assertEquals(ConfigBuilder.PROXY_GROUP_TAG, finalOf(routeAll = true))
        assertEquals("direct", finalOf(routeAll = false))
    }

    @Test
    fun preferredFingerprintSkipsQuicBasedHysteria2() {
        val hysteria = requireNotNull(LinkParser.parse("hysteria2://secret@hy.example.net:8443?sni=hy.example.net#H"))
        val config = Json.parseToJsonElement(
            ConfigBuilder.build(
                proxies = proxies + ProxyProtocol.HYSTERIA2.engineProxy("p3", hysteria.outboundJson()),
                activeProxyTag = "p3",
                byeDpiPackages = emptyList(),
                vpnPackages = listOf("org.telegram"),
                tunneledPackages = listOf("org.telegram"),
                byeDpiPort = 1081,
                probePort = 2080,
                probeUser = "user",
                probePass = "pass",
                options = NetworkOptions(preferredFingerprint = TlsFingerprint.CHROME)
            )
        ).jsonObject

        val outbounds = config.getValue("outbounds").jsonArray.map { it.jsonObject }
        fun tlsOf(tag: String) = outbounds.single { it["tag"]?.jsonPrimitive?.content == tag }.getValue("tls").jsonObject
        assertFalse(tlsOf("p3").containsKey("utls"))
        assertEquals("chrome", tlsOf("p1").getValue("utls").jsonObject.getValue("fingerprint").jsonPrimitive.content)
    }

    @Test
    fun amneziaWgProfileIsEmittedAsEndpoint() {
        val awg = requireNotNull(LinkParser.parse(AWG_CONFIG))
        val config = Json.parseToJsonElement(
            ConfigBuilder.build(
                proxies = proxies + ProxyProtocol.AMNEZIAWG.engineProxy("p3", awg.outboundJson()),
                activeProxyTag = "p3",
                byeDpiPackages = emptyList(),
                vpnPackages = listOf("org.telegram"),
                tunneledPackages = listOf("org.telegram"),
                byeDpiPort = 1081,
                probePort = 2080,
                probeUser = "user",
                probePass = "pass"
            )
        ).jsonObject

        val endpoint = config.getValue("endpoints").jsonArray.single().jsonObject
        assertEquals("awg", endpoint.getValue("type").jsonPrimitive.content)
        assertEquals("p3", endpoint.getValue("tag").jsonPrimitive.content)

        val outbounds = config.getValue("outbounds").jsonArray.map { it.jsonObject }
        assertTrue(outbounds.none { it["type"]?.jsonPrimitive?.content == "awg" })

        val selector = outbounds.single { it["tag"]?.jsonPrimitive?.content == ConfigBuilder.PROXY_GROUP_TAG }
        assertEquals(
            listOf("p1", "p2", "p3"),
            selector.getValue("outbounds").jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals("p3", selector.getValue("default").jsonPrimitive.content)

        assertEquals("ipv4_only", config.getValue("dns").jsonObject.getValue("strategy").jsonPrimitive.content)

        val rules = config.getValue("route").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
        val resolve = rules.single { it["action"]?.jsonPrimitive?.content == "resolve" }
        assertEquals("remote", resolve.getValue("server").jsonPrimitive.content)
        assertEquals("ipv4_only", resolve.getValue("strategy").jsonPrimitive.content)
        assertTrue(
            rules.indexOf(resolve) <
                rules.indexOfFirst { it["inbound"] != null }
        )
    }

    @Test
    fun keepsDomainsUnresolvedForRegularProxies() {
        val config = Json.parseToJsonElement(
            ConfigBuilder.build(
                proxies = proxies,
                activeProxyTag = "p1",
                byeDpiPackages = emptyList(),
                vpnPackages = listOf("org.telegram"),
                tunneledPackages = listOf("org.telegram"),
                byeDpiPort = 1081,
                probePort = 2080,
                probeUser = "user",
                probePass = "pass"
            )
        ).jsonObject

        val rules = config.getValue("route").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
        assertTrue(rules.none { it["action"]?.jsonPrimitive?.content == "resolve" })
    }

    private fun List<JsonObject>.processRule(outbound: String): List<String>? =
        firstOrNull { it["outbound"]?.jsonPrimitive?.content == outbound && it.containsKey("process_name") }
            ?.let { rule -> (rule.getValue("process_name") as JsonArray).map { it.jsonPrimitive.content } }

    private companion object {
        val AWG_CONFIG = """
            [Interface]
            Address = 10.8.1.2/32
            PrivateKey = aPrivateKey=
            Jc = 4
            Jmin = 50
            Jmax = 1000
            S1 = 15
            S2 = 36
            H1 = 1148364954
            H2 = 1813799810
            H3 = 1082378570
            H4 = 1151590459

            [Peer]
            PublicKey = aPublicKey=
            AllowedIPs = 0.0.0.0/0
            Endpoint = vpn.example.com:51820
            PersistentKeepalive = 25
        """.trimIndent()
    }

    private fun golden(name: String): String =
        requireNotNull(javaClass.getResource("/golden/$name")).readText()
}

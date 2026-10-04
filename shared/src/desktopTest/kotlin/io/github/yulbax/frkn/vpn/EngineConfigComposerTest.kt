package io.github.yulbax.frkn.vpn

import io.github.yulbax.frkn.data.App
import io.github.yulbax.frkn.data.ConnectionType
import io.github.yulbax.frkn.data.SettingsEntity
import io.github.yulbax.frkn.data.profile.ProfileEntity
import io.github.yulbax.frkn.proxy.LinkParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineConfigComposerTest {

    @Test
    fun routeAllTrafficStartsWithoutAnyAssignedApp() {
        val applied = EngineConfigComposer.compose(inputs(apps = emptyList(), routeAll = true), OWN, BYEDPI_PORT)

        assertTrue(applied.vpnActive)
        assertTrue(applied.engineConfig.network.routeAllTraffic)
    }

    @Test(expected = IllegalStateException::class)
    fun perAppRoutingStillNeedsAnAssignedApp() {
        EngineConfigComposer.compose(inputs(apps = emptyList(), routeAll = false), OWN, BYEDPI_PORT)
    }

    @Test
    fun byeDpiOnlyRoutingKeepsTheVpnChannelInactive() {
        val apps = listOf(App("org.example.dpi", "Dpi", isSystemApp = false, connectionType = ConnectionType.BYEDPI))
        val applied = EngineConfigComposer.compose(inputs(apps, routeAll = false), OWN, BYEDPI_PORT)

        assertFalse(applied.vpnActive)
        assertTrue(applied.needsByeDpi)
    }

    private fun inputs(apps: List<App>, routeAll: Boolean): SessionInputs {
        val parsed = requireNotNull(LinkParser.parse(VLESS))
        val profile = ProfileEntity(
            id = 1, name = parsed.name, type = parsed.protocol.wire, link = VLESS, outboundJson = parsed.outboundJson()
        )
        return SessionInputs(
            selected = profile,
            profiles = listOf(profile),
            settings = SettingsEntity(routeAllTraffic = routeAll),
            apps = apps
        )
    }

    private companion object {
        const val OWN = "io.github.yulbax.frkn"
        const val BYEDPI_PORT = 30001
        const val VLESS = "vless://id@example.com:443?security=tls#Amsterdam"
    }
}

package io.github.yulbax.frkn.util

import io.github.yulbax.frkn.data.AppConfigBackup
import io.github.yulbax.frkn.data.BackupProfile
import io.github.yulbax.frkn.data.BackupSettings
import java.io.ByteArrayInputStream
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsBackupTest {
    @Test
    fun acceptsLegacyV1AppsOnlyBackup() {
        val backup = Diagnostics.decodeBackup(
            """{"version":1,"apps":{"com.example.app":"VPN"}}"""
        )

        assertNotNull(backup)
        assertEquals("VPN", backup?.apps?.get("com.example.app"))
    }

    @Test
    fun readsLegacyEntityFieldsAndPreservesSelectedProfile() {
        val outbound = Json.encodeToString(
            """{"type":"vless","server":"example.com","server_port":443}"""
        )
        val legacy = """
            {
              "version": 3,
              "settings": {"id": 1, "mtu": 1500},
              "profiles": [{
                "id": 42,
                "name": "Example",
                "type": "vless",
                "link": "$VALID_LINK",
                "outboundJson": $outbound,
                "selected": true,
                "subscriptionUrl": ""
              }]
            }
        """.trimIndent()

        val backup = Diagnostics.decodeBackup(legacy)

        assertNotNull(backup)
        assertEquals(1500, backup?.settings?.mtu)
        assertTrue(requireNotNull(backup?.profiles).single().selected)
    }

    @Test
    fun rejectsUnsupportedVersionsAndInvalidSettings() {
        assertFalse(Diagnostics.isValidBackup(validBackup().copy(version = 0)))
        assertFalse(
            Diagnostics.isValidBackup(
                validBackup().copy(version = AppConfigBackup.CURRENT_VERSION + 1)
            )
        )
        assertFalse(
            Diagnostics.isValidBackup(
                validBackup().copy(settings = BackupSettings(mtu = 100))
            )
        )
    }

    @Test
    fun rejectsAmbiguousSelectionAndMismatchedProfileSchema() {
        val profile = requireNotNull(validBackup().profiles).single()
        assertFalse(
            Diagnostics.isValidBackup(
                validBackup().copy(profiles = listOf(profile, profile.copy(name = "Second")))
            )
        )
        assertFalse(
            Diagnostics.isValidBackup(
                validBackup().copy(profiles = listOf(profile.copy(type = "trojan")))
            )
        )
        assertFalse(
            Diagnostics.isValidBackup(
                validBackup().copy(
                    profiles = listOf(
                        profile.copy(
                            outboundJson =
                                """{"type":"vless","server":"other.example","server_port":443}"""
                        )
                    )
                )
            )
        )
    }

    @Test
    fun currentBackupFormatDoesNotExposeRoomIds() {
        val encoded = Json.encodeToString(validBackup())

        assertFalse(encoded.contains("\"id\""))
    }

    @Test
    fun boundedReadRejectsOversizedInput() {
        val input = ByteArrayInputStream(ByteArray(6))

        val error = runCatching {
            with(Diagnostics) { input.readBounded(maxBytes = 5) }
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun rejectsMalformedJson() {
        assertNull(Diagnostics.decodeBackup("{not-json"))
    }

    private fun validBackup(): AppConfigBackup = AppConfigBackup(
        version = AppConfigBackup.CURRENT_VERSION,
        apps = mapOf("com.example.app" to "VPN"),
        settings = BackupSettings(),
        profiles = listOf(
            BackupProfile(
                name = "Example",
                type = "vless",
                link = VALID_LINK,
                outboundJson = """{"type":"vless","server":"example.com","server_port":443}""",
                selected = true
            )
        )
    )

    private companion object {
        const val VALID_LINK =
            "vless://00000000-0000-0000-0000-000000000000@example.com:443?security=tls#Example"
    }
}

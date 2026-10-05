package io.github.yulbax.frkn.data.profile

import androidx.room.Room
import io.github.yulbax.frkn.data.AppDatabase
import io.github.yulbax.frkn.data.ConnectionType
import io.github.yulbax.frkn.data.App
import io.github.yulbax.frkn.proxy.ParsedProfile
import io.github.yulbax.frkn.proxy.ProxyProtocol
import io.github.yulbax.frkn.util.AppLog
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProfileRefreshTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: ProfileRepository
    private val log = RecordingLog()

    @Before
    fun setUp() {
        database = AppDatabase.build(Room.inMemoryDatabaseBuilder<AppDatabase>())
        repository = ProfileRepository(database, database.profileDao(), NoSubscriptions, log)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun rebuildsDescriptorsStoredBeforeASchemaChange() = runBlocking {
        database.profileDao().insert(
            ProfileEntity(
                name = "Amnezia",
                type = ProxyProtocol.AMNEZIAWG.wire,
                link = AWG_CONFIG,
                outboundJson = STALE_DESCRIPTOR
            )
        )

        repository.refreshDescriptors()

        val stored = database.profileDao().getAll().single()
        val endpoint = kotlinx.serialization.json.Json.parseToJsonElement(stored.outboundJson).jsonObject
        assertEquals("awg", endpoint.getValue("type").jsonPrimitive.content)
        val peer = endpoint.getValue("peers").jsonArray.single().jsonObject
        assertEquals(25, peer.getValue("persistent_keepalive_interval").jsonPrimitive.content.toInt())
        assertEquals("aPresharedKey=", peer.getValue("preshared_key").jsonPrimitive.content)
    }

    @Test
    fun movesAPlainConfStoredAsAmneziaWgOverToWireGuard() = runBlocking {
        database.profileDao().insert(
            ProfileEntity(
                name = "Home",
                type = ProxyProtocol.AMNEZIAWG.wire,
                link = PLAIN_CONF,
                outboundJson = """{"type":"awg","address":["10.8.1.2/32"],"private_key":"aPrivateKey=","peers":[]}"""
            )
        )

        repository.refreshDescriptors()

        val stored = database.profileDao().getAll().single()
        assertEquals(ProxyProtocol.WIREGUARD.wire, stored.type)
        val endpoint = kotlinx.serialization.json.Json.parseToJsonElement(stored.outboundJson).jsonObject
        assertEquals("wireguard", endpoint.getValue("type").jsonPrimitive.content)
        assertEquals("Home", stored.name)
    }

    @Test
    fun keepsUpToDateDescriptorsUntouched() = runBlocking {
        repository.add(AWG_CONFIG)
        val before = database.profileDao().getAll().single()

        repository.refreshDescriptors()

        assertEquals(before, database.profileDao().getAll().single())
    }

    @Test
    fun keepsProfilesReadableWhenTheStoredTypeIsUnknownToThisBuild() = runBlocking {
        database.profileDao().insert(
            ProfileEntity(
                name = "From a newer build",
                type = "quantumwg",
                link = "quantumwg://whatever",
                outboundJson = STALE_DESCRIPTOR
            )
        )

        val stored = database.profileDao().getAll().single()
        assertEquals("quantumwg", stored.type)
        assertNull(stored.protocol)

        repository.refreshDescriptors()

        assertEquals(stored, database.profileDao().getAll().single())
        assertTrue(log.warnings.single().contains("keep a stale descriptor"))
        assertTrue(repository.profileDiagnostics().single().contains("unknown to this build"))
    }

    @Test
    fun diagnosticsReportStaleAndFreshDescriptorsWithoutLeakingSecrets() = runBlocking {
        repository.add(AWG_CONFIG)
        database.profileDao().insert(
            ProfileEntity(name = "Stale", type = ProxyProtocol.AMNEZIAWG.wire, link = AWG_CONFIG, outboundJson = STALE_DESCRIPTOR)
        )

        val report = repository.profileDiagnostics()

        assertTrue(report.any { it.contains("fresh=true") })
        assertTrue(report.any { it.contains("fresh=false") })
        assertTrue(report.none { it.contains("aPrivateKey") || it.contains("vpn.example.com") })
    }

    private class RecordingLog : AppLog {
        val warnings = mutableListOf<String>()
        override fun i(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, t: Throwable?) {
            warnings += message
        }
        override fun e(tag: String, message: String, t: Throwable?) = Unit
    }

    private object NoSubscriptions : SubscriptionProfileSource {
        override suspend fun fetch(url: String): List<ParsedProfile> = emptyList()
    }

    @Test
    fun deletingTheLastServerMovesVpnAppsToDirect() = runBlocking {
        val id = database.profileDao().insert(
            ProfileEntity(name = "Only", type = ProxyProtocol.VLESS.wire, link = "vless://id@example.com:443", outboundJson = "{}")
        )
        database.appDao().upsertApps(
            listOf(
                App("org.vpn", "Vpn", isSystemApp = false, connectionType = ConnectionType.VPN),
                App("org.dpi", "Dpi", isSystemApp = false, connectionType = ConnectionType.BYEDPI)
            )
        )

        repository.delete(database.profileDao().getAll().single { it.id == id })

        assertEquals(ConnectionType.DIRECT, database.appDao().getApp("org.vpn")?.connectionType)
        assertEquals(ConnectionType.BYEDPI, database.appDao().getApp("org.dpi")?.connectionType)
    }

    private companion object {
        const val STALE_DESCRIPTOR = """{"type":"wireguard","address":["10.8.1.2/32"],"private_key":"aPrivateKey=",""" +
            """"peers":[{"address":"vpn.example.com","port":51820,"public_key":"aPublicKey=",""" +
            """"pre_shared_key":"aPresharedKey=","allowed_ips":["0.0.0.0/0"],"persistent_keepalive_interval":"25-35"}]}"""

        val PLAIN_CONF = """
            [Interface]
            Address = 10.8.1.2/32
            PrivateKey = aPrivateKey=

            [Peer]
            PublicKey = aPublicKey=
            AllowedIPs = 0.0.0.0/0
            Endpoint = vpn.example.com:51820
        """.trimIndent()

        val AWG_CONFIG = """
            [Interface]
            Address = 10.8.1.2/32
            PrivateKey = aPrivateKey=
            Jc = 4

            [Peer]
            PublicKey = aPublicKey=
            PresharedKey = aPresharedKey=
            AllowedIPs = 0.0.0.0/0
            Endpoint = vpn.example.com:51820
            PersistentKeepalive = 25-35
        """.trimIndent()
    }
}

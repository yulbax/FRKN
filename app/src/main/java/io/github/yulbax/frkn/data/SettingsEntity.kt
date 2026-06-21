package io.github.yulbax.frkn.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val showSystemApps: Boolean = false,
    val byeDpiArgs: String = "",
    val tunStack: String = "gvisor",
    val mtu: Int = 9000,
    val ipv6Mode: String = "disable",
    val dnsRemote: String = "1.1.1.1",
    val dnsDirect: String = "1.1.1.1",
    val sniff: Boolean = true,
    val bypassLan: Boolean = false,
    val autoConnect: Boolean = false,
    val preferredFingerprint: String = ""
)

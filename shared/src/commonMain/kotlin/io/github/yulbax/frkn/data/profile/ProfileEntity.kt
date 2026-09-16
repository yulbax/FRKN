package io.github.yulbax.frkn.data.profile

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import io.github.yulbax.frkn.util.ProxyProtocol

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: ProxyProtocol,
    val link: String,
    val outboundJson: String,
    val selected: Boolean = false,
    val subscriptionUrl: String = ""
)

class ProxyProtocolConverter {
    @TypeConverter
    fun toWire(protocol: ProxyProtocol): String = protocol.wire

    @TypeConverter
    fun fromWire(value: String): ProxyProtocol =
        requireNotNull(ProxyProtocol.fromWire(value)) { "unknown proxy protocol: $value" }
}

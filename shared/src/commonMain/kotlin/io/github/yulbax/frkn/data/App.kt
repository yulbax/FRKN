package io.github.yulbax.frkn.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "apps")
data class App(
    @PrimaryKey val packageName: String,
    val name: String,
    val isSystemApp: Boolean,
    val connectionType: ConnectionType = ConnectionType.VPN
)

enum class ConnectionType(val wire: String) {
    DIRECT("DIRECT"), BYEDPI("BYEDPI"), VPN("VPN");

    companion object {
        fun fromWire(value: String): ConnectionType? = entries.firstOrNull { it.wire == value }
    }
}

data class RoutedApps(
    val byeDpiPackages: List<String>,
    val vpnPackages: List<String>
) {
    val tunneledPackages: List<String> get() = byeDpiPackages + vpnPackages
    val hasByeDpi: Boolean get() = byeDpiPackages.isNotEmpty()
    val hasVpn: Boolean get() = vpnPackages.isNotEmpty()
    val isEmpty: Boolean get() = !hasByeDpi && !hasVpn

    companion object {
        fun from(apps: List<App>): RoutedApps = RoutedApps(
            byeDpiPackages = apps.packagesOf(ConnectionType.BYEDPI),
            vpnPackages = apps.packagesOf(ConnectionType.VPN)
        )

        private fun List<App>.packagesOf(type: ConnectionType): List<String> =
            filter { it.connectionType == type }.map { it.packageName }
    }
}

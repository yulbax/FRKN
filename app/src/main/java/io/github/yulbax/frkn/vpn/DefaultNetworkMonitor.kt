package io.github.yulbax.frkn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import io.github.yulbax.frkn.util.FrknLog
import io.github.yulbax.frkn.vpn.core.DefaultInterfaceListener
import org.koin.core.annotation.Single
import java.net.NetworkInterface

@Single
class DefaultNetworkMonitor(
    context: Context,
    private val log: FrknLog
) {

    private val connectivity: ConnectivityManager =
        context.getSystemService(ConnectivityManager::class.java)
    private var listener: DefaultInterfaceListener? = null
    private var lastInterfaceName = ""
    private var lastInterfaceIndex = Int.MIN_VALUE

    var onUnderlyingNetworkChanged: ((Network?) -> Unit)? = null
    private var reportedNetwork: Network? = null

    @Volatile
    private var underlyingNetwork: Network? = null
    private var registered = false

    private val handlerThread = HandlerThread("FrknNetMonitor").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        .build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            log.i(TAG, "network available (${transportOf(network)})")
            underlyingNetwork = network
            reportNetwork()
            pushUpdate()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            underlyingNetwork = network
            reportNetwork()
            pushUpdate()
        }

        override fun onLost(network: Network) {
            log.i(TAG, "network lost (${transportOf(network)})")
            if (network == underlyingNetwork) underlyingNetwork = null
            reportNetwork()
            pushUpdate()
        }
    }

    private fun reportNetwork() {
        val network = underlyingNetwork
        if (network != reportedNetwork) {
            reportedNetwork = network
            onUnderlyingNetworkChanged?.invoke(network)
        }
    }

    private fun transportOf(network: Network): String {
        val caps = connectivity.getNetworkCapabilities(network) ?: return "unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    }

    fun currentNetwork(): Network? = underlyingNetwork

    fun start() {
        if (registered) return
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                connectivity.registerBestMatchingNetworkCallback(request, callback, handler)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                connectivity.requestNetwork(request, callback, handler)
            else ->
                connectivity.registerDefaultNetworkCallback(callback)
        }
        registered = true
        underlyingNetwork = connectivity.activeNetwork
    }

    fun stop() {
        if (registered) {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            registered = false
        }
        underlyingNetwork = null
        reportedNetwork = null
    }

    fun setListener(newListener: DefaultInterfaceListener?) {
        listener = newListener
        if (newListener != null) pushUpdate()
    }

    private fun pushUpdate() {
        val l = listener ?: return
        val network = underlyingNetwork
        if (network == null) {
            emit(l, "", -1)
            return
        }

        repeat(10) {
            val name = connectivity.getLinkProperties(network)?.interfaceName
            if (name == null) {
                Thread.sleep(100)
                return@repeat
            }
            val index = runCatching { NetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
            if (index <= 0) {
                Thread.sleep(100)
                return@repeat
            }
            emit(l, name, index)
            return
        }
        log.w(TAG, "default interface unresolved — gave up")
        emit(l, "", -1)
    }

    private fun emit(l: DefaultInterfaceListener, name: String, index: Int) {
        if (name != lastInterfaceName || index != lastInterfaceIndex) {
            lastInterfaceName = name
            lastInterfaceIndex = index
            if (index > 0) log.i(TAG, "default interface -> $name (idx=$index)")
            else log.i(TAG, "default interface lost")
        }
        l.onDefaultInterfaceChanged(name, index)
    }

    private companion object {
        const val TAG = "NetworkMonitor"
    }
}

package com.blakeriding.upright

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * Recognises the home Wi-Fi without location access. Android hides the Wi-Fi name from apps that
 * don't have location permission, so instead we remember the network's shape: router address,
 * subnet, DNS servers, domain and (if there is one) the IPv6 prefix your ISP gave you.
 */
object HomeWifi {
    fun fingerprint(lp: LinkProperties): HomeNet? {
        val gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress ?: return null
        val v4 = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val subnet = "${networkPart(v4.address.address, v4.prefixLength)}/${v4.prefixLength}"
        val v6 = lp.linkAddresses.map { it.address }.filterIsInstance<Inet6Address>()
            .firstOrNull { !it.isLinkLocalAddress && !it.isSiteLocalAddress && (it.address[0].toInt() and 0xE0) == 0x20 }
        return HomeNet(
            gateway = gateway,
            subnet = subnet,
            dns = lp.dnsServers.mapNotNull { it.hostAddress }.sorted(),
            domain = lp.domains.orEmpty(),
            // First 48 bits: the part of an ISP prefix that's yours and rarely changes.
            v6Prefix = v6?.address?.take(6)?.joinToString(":") { "%02x".format(it) }.orEmpty(),
        )
    }

    private fun networkPart(addr: ByteArray, prefix: Int): String =
        addr.mapIndexed { i, b ->
            val bits = (prefix - i * 8).coerceIn(0, 8)
            (b.toInt() and (0xFF shl (8 - bits)) and 0xFF)
        }.joinToString(".")

    /** Router, subnet, DNS and domain must match. The IPv6 prefix is compared only when both have one. */
    fun matches(home: HomeNet, now: HomeNet): Boolean =
        home.gateway == now.gateway && home.subnet == now.subnet && home.dns == now.dns && home.domain == now.domain &&
            (home.v6Prefix.isEmpty() || now.v6Prefix.isEmpty() || home.v6Prefix == now.v6Prefix)

    private fun wifiNetworks(context: Context): List<Network> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION") // allNetworks is the only way to list them all; fine for a quick check.
        return cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    /** The current Wi-Fi's fingerprint, for "Save as home". */
    fun current(context: Context): HomeNet? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return wifiNetworks(context).firstNotNullOfOrNull { cm.getLinkProperties(it)?.let(::fingerprint) }
    }

    fun atHome(context: Context): Boolean {
        val home = Store.state.value.homeNet ?: return false
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return wifiNetworks(context).any { n -> cm.getLinkProperties(n)?.let(::fingerprint)?.let { matches(home, it) } == true }
    }

    private fun watchIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0, Intent(context, HomeNetReceiver::class.java),
            // Mutable: Android adds the network to the intent.
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
        )

    /** Listen for Wi-Fi connections only while some reminder is waiting for you to get home. */
    fun sync(context: Context) {
        val st = Store.state.value
        val cm = context.getSystemService(ConnectivityManager::class.java)
        try {
            if (st.homeSnoozes.isNotEmpty() && st.homeNet != null) {
                if (atHome(context)) {
                    arrived(context)
                    return
                }
                val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
                cm.registerNetworkCallback(request, watchIntent(context))
            } else {
                cm.unregisterNetworkCallback(watchIntent(context))
            }
        } catch (_: IllegalArgumentException) {
            // Unregistering something that wasn't registered.
        } catch (e: SecurityException) {
            android.util.Log.w("Upright", "Can't watch Wi-Fi", e)
        }
    }

    fun arrived(context: Context) {
        if (Store.state.value.homeSnoozes.isEmpty()) return
        Store.update { it.arriveHome(nowMs()) }
        Notifs.postMedAlerts(context)
        Scheduler.scheduleMeds(context)
    }
}

/** Woken by Android when a Wi-Fi network connects (only registered while a reminder waits for home). */
class HomeNetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        if (HomeWifi.atHome(context)) HomeWifi.arrived(context)
        HomeWifi.sync(context)
    }
}

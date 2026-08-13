package com.opencam.util

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    /** Returns a LAN-reachable IPv4 address, preferring Wi-Fi and Ethernet. */
    fun getLocalIpv4(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
            val ranked = interfaces.sortedBy { nif ->
                val name = nif.name.orEmpty().lowercase()
                when {
                    name.startsWith("wlan") || name.startsWith("wifi") -> 0
                    name.startsWith("eth") || name.startsWith("en") -> 1
                    name.startsWith("usb") || name.startsWith("rndis") -> 2
                    name.startsWith("rmnet") || name.startsWith("wwan") -> 4
                    else -> 3
                }
            }
            ranked.asSequence().flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull {
                    !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isLinkLocalAddress
                }?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}

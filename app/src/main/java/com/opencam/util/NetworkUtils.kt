package com.opencam.util

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    fun interfaceRank(name: String): Int {
        val lower = name.lowercase()
        return when {
            lower.startsWith("wlan") || lower.startsWith("wifi") -> 0
            lower.startsWith("eth") || lower.startsWith("en") -> 1
            lower.startsWith("usb") || lower.startsWith("rndis") -> 2
            lower.startsWith("tun") || lower.startsWith("tap") -> 3
            lower.startsWith("rmnet") || lower.startsWith("wwan") -> 4
            else -> 5
        }
    }

    /** Returns a LAN-reachable IPv4 address, preferring Wi-Fi and Ethernet. */
    fun getLocalIpv4(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
            val ranked = interfaces.sortedBy { interfaceRank(it.name.orEmpty()) }
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

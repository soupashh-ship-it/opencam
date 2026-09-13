package com.opencam.util

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    fun interfaceRank(name: String): Int {
        val lower = name.lowercase()
        return when {
            lower.startsWith("wlan") || lower.startsWith("wifi") ||
                lower.startsWith("ap") || lower.startsWith("softap") ||
                lower.startsWith("swlan") -> 0
            lower.startsWith("eth") || lower.startsWith("en") -> 1
            lower.startsWith("usb") || lower.startsWith("rndis") ||
                lower.startsWith("ncm") || lower.startsWith("geth") -> 2
            lower.startsWith("tun") || lower.startsWith("tap") -> 3
            lower.startsWith("rmnet") || lower.startsWith("wwan") ||
                lower.startsWith("ccmni") || lower.startsWith("pdp") -> 4
            else -> 5
        }
    }

    /** Returns a LAN-reachable IPv4 address, preferring Wi-Fi and Ethernet. */
    fun getLocalIpv4(): String? = getAllLocalIpv4().firstOrNull()

    /** Returns all LAN-reachable IPv4 addresses sorted by interface priority. */
    fun getAllLocalIpv4(): List<String> {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
            val ranked = interfaces.sortedBy { interfaceRank(it.name.orEmpty()) }
            ranked.flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter {
                    !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isLinkLocalAddress
                }
                .mapNotNull { it.hostAddress }
                .distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }
}

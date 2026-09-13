package com.opencam
 
import com.opencam.util.NetworkUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
 
class NetworkUtilsTest {
 
    @Test
    fun testInterfaceRankOrdering() {
        val wlanRank = NetworkUtils.interfaceRank("wlan0")
        val apRank = NetworkUtils.interfaceRank("ap0")
        val softApRank = NetworkUtils.interfaceRank("softap0")
        val swlanRank = NetworkUtils.interfaceRank("swlan0")
        val ethRank = NetworkUtils.interfaceRank("eth0")
        val usbRank = NetworkUtils.interfaceRank("rndis0")
        val ncmRank = NetworkUtils.interfaceRank("ncm0")
        val gethRank = NetworkUtils.interfaceRank("geth0")
        val vpnRank = NetworkUtils.interfaceRank("tun0")
        val cellularRank = NetworkUtils.interfaceRank("rmnet0")
        val ccmniRank = NetworkUtils.interfaceRank("ccmni0")
        val pdpRank = NetworkUtils.interfaceRank("pdp0")
        val unknownRank = NetworkUtils.interfaceRank("dummy0")

        assertEquals(0, wlanRank)
        assertEquals(0, apRank)
        assertEquals(0, softApRank)
        assertEquals(0, swlanRank)
        assertEquals(1, ethRank)
        assertEquals(2, usbRank)
        assertEquals(2, ncmRank)
        assertEquals(2, gethRank)
        assertEquals(3, vpnRank)
        assertEquals(4, cellularRank)
        assertEquals(4, ccmniRank)
        assertEquals(4, pdpRank)
        assertEquals(5, unknownRank)

        assertTrue(wlanRank < ethRank)
        assertTrue(ethRank < usbRank)
        assertTrue(usbRank < vpnRank)
        assertTrue(vpnRank < cellularRank)
        assertTrue(cellularRank < unknownRank)
    }

    @Test
    fun testInterfaceRankCaseInsensitive() {
        assertEquals(0, NetworkUtils.interfaceRank("WLAN1"))
        assertEquals(0, NetworkUtils.interfaceRank("WiFi_Direct"))
        assertEquals(0, NetworkUtils.interfaceRank("SOFTAP0"))
        assertEquals(1, NetworkUtils.interfaceRank("EN0"))
        assertEquals(2, NetworkUtils.interfaceRank("NCM0"))
        assertEquals(2, NetworkUtils.interfaceRank("GETH_USB"))
        assertEquals(3, NetworkUtils.interfaceRank("TAP_ADAPTER"))
        assertEquals(4, NetworkUtils.interfaceRank("CCMNI0"))
    }

    @Test
    fun testGetAllLocalIpv4DoesNotCrash() {
        val allIps = NetworkUtils.getAllLocalIpv4()
        val primary = NetworkUtils.getLocalIpv4()
        if (allIps.isNotEmpty()) {
            assertEquals(allIps.first(), primary)
        }
    }
}

package com.opencam
 
import com.opencam.util.NetworkUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
 
class NetworkUtilsTest {
 
    @Test
    fun testInterfaceRankOrdering() {
        val wlanRank = NetworkUtils.interfaceRank("wlan0")
        val ethRank = NetworkUtils.interfaceRank("eth0")
        val usbRank = NetworkUtils.interfaceRank("rndis0")
        val vpnRank = NetworkUtils.interfaceRank("tun0")
        val cellularRank = NetworkUtils.interfaceRank("rmnet0")
        val unknownRank = NetworkUtils.interfaceRank("dummy0")

        assertEquals(0, wlanRank)
        assertEquals(1, ethRank)
        assertEquals(2, usbRank)
        assertEquals(3, vpnRank)
        assertEquals(4, cellularRank)
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
        assertEquals(1, NetworkUtils.interfaceRank("EN0"))
        assertEquals(3, NetworkUtils.interfaceRank("TAP_ADAPTER"))
    }
}

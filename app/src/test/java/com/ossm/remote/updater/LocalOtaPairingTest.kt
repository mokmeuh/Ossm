package com.ossm.remote.updater

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalOtaPairingTest {
    @Test
    fun `ignores its own receiver by instance id`() {
        val own = peer(instanceId = "abc123")

        assertTrue(isOwnLocalOtaReceiver(own, "abc123"))
        assertFalse(isOwnLocalOtaReceiver(own, "other"))
        assertFalse(isOwnLocalOtaReceiver(peer(instanceId = null), "abc123"))
    }

    @Test
    fun `parses the TXT record of a receiver`() {
        val parsed = localOtaPeerFromTxt(
            mapOf("package" to "com.ossm.remote.debug", "vc" to "172", "vn" to "2.3.24", "dev" to "moto_g", "inst" to "id1"),
        )

        assertEquals(peer(versionCode = 172, versionName = "2.3.24", device = "moto_g", instanceId = "id1"), parsed)
        assertNull(localOtaPeerFromTxt(mapOf("vc" to "1")))
    }

    @Test
    fun `TXT record of a 2_3_32 receiver carries the protocol and the public key, no token`() {
        val modern = localOtaPeerFromTxt(mapOf("package" to PACKAGE, "vc" to "180", "proto" to "2", "pk" to "abc"))!!
        val legacy = localOtaPeerFromTxt(mapOf("package" to PACKAGE, "vc" to "179", "token" to "secret"))!!

        assertTrue(modern.supportsPairing())
        assertFalse(legacy.supportsPairing())
        assertFalse(modern.copy(publicKey = null).supportsPairing())
    }

    @Test
    fun `sends only a strictly newer version`() {
        assertEquals(LocalOtaSendDecision.Send, localOtaSendDecision(peer(versionCode = 172), PACKAGE, 173))
        assertEquals(LocalOtaSendDecision.PeerUpToDate, localOtaSendDecision(peer(versionCode = 173), PACKAGE, 173))
        assertEquals(LocalOtaSendDecision.PeerUpToDate, localOtaSendDecision(peer(versionCode = 174), PACKAGE, 173))
        assertEquals(LocalOtaSendDecision.WrongPackage, localOtaSendDecision(peer(versionCode = 1).copy(packageName = "x"), PACKAGE, 173))
        // Legacy receivers do not announce a version: the receiver keeps validating.
        assertEquals(LocalOtaSendDecision.Send, localOtaSendDecision(peer(versionCode = null), PACKAGE, 173))
    }

    @Test
    fun `parses typed addresses`() {
        assertEquals(LocalOtaTarget("10.0.0.232", 47474), parseLocalOtaAddress("10.0.0.232"))
        assertEquals(LocalOtaTarget("10.0.0.232", 41234), parseLocalOtaAddress(" 10.0.0.232:41234 "))
        assertEquals(LocalOtaTarget("100.101.102.103", 47474), parseLocalOtaAddress("http://100.101.102.103:47474/"))
        assertEquals(LocalOtaTarget("moto.tailnet.ts.net", 47474), parseLocalOtaAddress("moto.tailnet.ts.net"))
        assertEquals(LocalOtaTarget("fd7a:115c::1", 5000), parseLocalOtaAddress("[fd7a:115c::1]:5000"))
        assertEquals("[fd7a:115c::1]:5000", parseLocalOtaAddress("[fd7a:115c::1]:5000")?.label)
    }

    @Test
    fun `rejects malformed addresses`() {
        listOf("", "  ", "10.0.0.256", "10.0.0", "10.0.0.1:0", "10.0.0.1:70000", "10.0.0.1:abc",
            "a b", "user@10.0.0.1", "10.0.0.1/path", "fd7a::1", "[fd7a::1", "-bad.host")
            .forEach { input -> assertNull(input, parseLocalOtaAddress(input)) }
    }

    @Test
    fun `shows Wi-Fi and Tailscale IPv4 but not link-local, loopback or cellular`() {
        val selected = selectLocalOtaAddresses(
            listOf(
                "lo" to ip("127.0.0.1"),
                "wlan0" to ip("10.0.0.232"),
                "wlan0" to ip("fe80::1"),
                "wlan0" to ip("169.254.10.2"),
                "tun0" to ip("100.101.102.103"),
                "tun0" to ip("fd7a:115c:a1e0::1"),
                "rmnet_data0" to ip("100.70.1.2"),
                "rmnet_data1" to ip("10.200.1.1"),
                "eth0" to ip("192.168.1.20"),
                "wlan1" to ip("8.8.8.8"),
                "tun0" to ip("100.128.0.1"),
            ),
        )

        assertEquals(
            listOf(
                LocalOtaAddress("10.0.0.232", LocalOtaAddressKind.WiFi),
                LocalOtaAddress("192.168.1.20", LocalOtaAddressKind.WiFi),
                LocalOtaAddress("100.101.102.103", LocalOtaAddressKind.Tailscale),
            ),
            selected,
        )
    }

    @Test
    fun `instance id is random and stable for the process`() {
        assertEquals(LocalOtaProtocol.INSTANCE_ID, LocalOtaProtocol.INSTANCE_ID)
        assertEquals(16, LocalOtaProtocol.INSTANCE_ID.length)
        assertNotEquals("", LocalOtaProtocol.INSTANCE_ID)
    }

    private fun ip(literal: String): InetAddress = InetAddress.getByName(literal)

    private fun wrongCode(right: String): String =
        right.map { digit -> '0' + ((digit - '0' + 1) % 10) }.joinToString("")

    private fun peer(
        versionCode: Long? = 172,
        versionName: String? = null,
        device: String? = null,
        instanceId: String? = null,
    ) = LocalOtaPeerInfo(PACKAGE, versionCode, versionName, device, instanceId)

    private companion object {
        const val PACKAGE = "com.ossm.remote.debug"
    }
}

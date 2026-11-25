package com.naos.sursound

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class Ipv4SubnetTest {
    private fun ip(text: String) = InetAddress.getByName(text)
    @Test fun matchesExactAndPartialBytePrefixes() {
        val local = ip("192.168.2.10")
        assertTrue(Ipv4Subnet.contains(local, 24, ip("192.168.2.254")))
        assertFalse(Ipv4Subnet.contains(local, 24, ip("192.168.3.10")))
        assertTrue(Ipv4Subnet.contains(local, 23, ip("192.168.3.10")))
        assertFalse(Ipv4Subnet.contains(local, 23, ip("192.168.4.10")))
        assertTrue(Ipv4Subnet.contains(local, 31, ip("192.168.2.11")))
        assertFalse(Ipv4Subnet.contains(local, 32, ip("192.168.2.11")))
    }

    @Test fun rejectsUnrestrictedInvalidAndNonIpv4Prefixes() {
        val local = ip("10.0.0.2")
        for (prefix in listOf(-1, 0, 33)) {
            assertFalse(Ipv4Subnet.contains(local, prefix, ip("10.0.0.3")))
        }
        assertFalse(Ipv4Subnet.contains(local, 24, ip("::1")))
        assertFalse(Ipv4Subnet.contains(ip("::1"), 24, local))
    }
}

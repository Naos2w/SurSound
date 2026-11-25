package com.naos.sursound

import java.net.Inet4Address
import java.net.InetAddress

/** Pure IPv4 on-link check compatible with API 29; /0 is not an explicit local subnet. */
internal object Ipv4Subnet {
    fun contains(local: InetAddress, prefix: Int, peer: InetAddress): Boolean {
        if (local !is Inet4Address || peer !is Inet4Address || prefix !in 1..32) return false
        val a = local.address
        val b = peer.address
        var remaining = prefix
        for (i in 0..3) {
            val bits = remaining.coerceIn(0, 8)
            if (bits == 0) break
            val mask = (0xff shl (8-bits)) and 0xff
            if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
            remaining -= bits
        }
        return true
    }
}

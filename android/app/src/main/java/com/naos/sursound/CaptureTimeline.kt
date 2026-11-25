package com.naos.sursound

import java.net.InetAddress

/** Assign a presentation slot before any queue admission decision. */
internal class CaptureTimeline {
    data class Slot(val sequence: Int, val origin: Long) {
        val deadline: Long get() = origin + sequence.toLong() * ReceiverPlayoutBuffer.FRAME_NANOS
        fun expired(now: Long): Boolean = now - deadline >= 0
    }
    private var origin: Long? = null
    private var next = 0
    fun reserve(now: Long): Slot? {
        if (next >= SyncWire.MAX_PACKETS) return null
        val epoch = origin ?: (now + SyncWire.LEAD_NANOS).also { origin = it }
        return Slot(next++, epoch)
    }
}

/** Group membership is explicit; never broadcast or accept a public address. */
internal fun capturePeers(text: String, group: Boolean): List<InetAddress> {
    val peers = if (group) text.split(',').map(LanPacketCodec::localPeer)
        else listOf(LanPacketCodec.localPeer(text))
    require(peers.size in 1..4 && peers.distinct().size == peers.size) {
        "Enter one to four distinct private IPv4 addresses."
    }
    return peers
}

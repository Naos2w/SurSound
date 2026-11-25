package com.naos.sursound

import org.junit.Assert.*
import org.junit.Test

class CaptureTimelineTest {
    @Test fun reservationsPreserveDroppedSlotsAndCommonEpoch() {
        val timeline = CaptureTimeline()
        val first = timeline.reserve(1_000_000_000L)!!
        val discarded = timeline.reserve(1_010_000_000L)!!
        val third = timeline.reserve(1_020_000_000L)!!
        assertEquals(0, first.sequence)
        assertEquals(1, discarded.sequence)
        assertEquals(2, third.sequence)
        assertEquals(1_240_000_000L, first.origin)
        assertEquals(first.origin, third.origin)
        assertEquals(10_000_000L, third.deadline - first.deadline)
        assertFalse(third.expired(third.deadline - 1))
        assertTrue(third.expired(third.deadline))
    }

    @Test fun timelineNeverReusesASequenceOrExceedsWireLimit() {
        val timeline = CaptureTimeline()
        repeat(SyncWire.MAX_PACKETS) { sequence ->
            val slot = timeline.reserve(0)!!
            assertEquals(sequence, slot.sequence)
        }
        assertNull(timeline.reserve(0))
        assertNull(timeline.reserve(500_000_000))
    }

    @Test fun packetizerReservesSlotsBeforeBoundedQueueAdmission() {
        val timeline = CaptureTimeline()
        val admitted = ArrayList<CaptureTimeline.Slot>()
        var total = 0
        val packetizer = PcmPacketizer { pcm ->
            assertEquals(LanPacketCodec.PCM_BYTES, pcm.size)
            val slot = timeline.reserve(0)!!
            if (total != 1) admitted.add(slot)
            total++
            pcm.fill(0)
        }
        packetizer.offer(ShortArray(240 * 2 * 3), 240 * 2 * 3)
        assertEquals(listOf(0, 2), admitted.map { it.sequence })
        assertEquals(10_000_000L, admitted[1].deadline - admitted[0].deadline)
    }

    @Test fun explicitPrivateDistinctGroupOnly() {
        assertEquals(4, capturePeers("192.168.1.2, 192.168.1.3, 10.0.0.4, 172.16.1.5", true).size)
        assertEquals(1, capturePeers("192.168.1.2", false).size)
        for (input in listOf("192.168.1.2,192.168.1.2", "192.168.1.2,8.8.8.8",
            "192.168.1.2,", "192.168.1.2,192.168.1.3,192.168.1.4,192.168.1.5,192.168.1.6", "")) {
            assertTrue(runCatching { capturePeers(input, true) }.isFailure)
        }
        assertTrue(runCatching { capturePeers("192.168.1.2,192.168.1.3", false) }.isFailure)
    }
}

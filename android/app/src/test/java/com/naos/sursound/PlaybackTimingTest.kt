package com.naos.sursound

import org.junit.Assert.*
import org.junit.Test

class PlaybackTimingTest {
    private fun pcm(value: Int = 0) = ByteArray(LanPacketCodec.PCM_BYTES) { value.toByte() }

    @Test fun clockOffsetAndMinimumRttWindow() {
        val clock = ClockOffsetEstimator(capacity = 2)
        assertNull(clock.estimate())
        // Sender is 100 ms ahead; symmetric 2 ms network paths, 1 ms processing.
        assertTrue(clock.add(ClockExchange(0, 102_000_000, 103_000_000, 5_000_000)))
        val first = clock.estimate()!!
        assertEquals(100_000_000.0, first.senderMinusReceiverNanos, 0.01)
        assertEquals(4_000_000.0, first.roundTripNanos, 0.01)
        assertEquals(2_000_000.0, first.pathUncertaintyNanos, 0.01)
        assertEquals(50_000_000.0, first.receiverTimeFor(150_000_000), 0.01)
        // Higher/asymmetric RTT must not replace the best sample.
        assertTrue(clock.add(ClockExchange(10_000_000, 112_000_000, 113_000_000, 21_000_000)))
        assertEquals(first, clock.estimate())
        assertFalse(clock.add(ClockExchange(22_000_000, 125_000_000, 135_000_000, 23_000_000)))
        assertFalse(clock.add(ClockExchange(1, 2, 3, 4)))
        assertTrue(clock.add(ClockExchange(30_000_000, 133_000_000, 134_000_000, 37_000_000)))
        assertEquals(6_000_000.0, clock.estimate()!!.roundTripNanos, 0.01)
    }

    @Test fun reorderBeforeDeadlineAndConcealMissing() {
        val buffer = ReceiverPlayoutBuffer()
        assertTrue(buffer.offer(7, 100, pcm(1), 0))
        assertTrue(buffer.offer(7, 102, pcm(3), 1_000_000))
        assertTrue(buffer.offer(7, 101, pcm(2), 2_000_000))
        assertFalse(buffer.offer(7, 101, pcm(), 3_000_000))
        assertFalse(buffer.offer(8, 103, pcm(), 3_000_000))
        assertNull(buffer.poll(39_999_999))
        assertArrayEquals(pcm(1), buffer.poll(40_000_000)!!.pcm)
        assertArrayEquals(pcm(2), buffer.poll(45_000_000)!!.pcm)
        assertArrayEquals(pcm(3), buffer.poll(50_000_000)!!.pcm)
        assertNull(buffer.poll(55_000_000)!!.pcm)
        assertEquals(1L, buffer.missing)
        assertFalse(buffer.offer(7, 103, pcm(), 56_000_000))
    }

    @Test fun boundedQueueAndSchedulerStallNeverBurst() {
        val buffer = ReceiverPlayoutBuffer(windowPackets = 4)
        val discarded = pcm(7)
        assertTrue(buffer.offer(5, 0, discarded, 0))
        for (i in 1..3) assertTrue(buffer.offer(5, i, pcm(i), 0))
        assertFalse(buffer.offer(5, 4, pcm(), 0))
        assertEquals(4, buffer.queued)
        val block = buffer.poll(55_000_000)!!
        assertEquals(3L, block.sequence)
        assertEquals(3L, buffer.schedulerSkipped)
        assertArrayEquals(pcm(3), block.pcm)
        assertTrue(discarded.all { it == 0.toByte() })
        assertNull(buffer.poll(55_000_000))
        assertTrue(buffer.offer(5, 4, pcm(), 56_000_000))
        buffer.discardPending()
        assertEquals(0, buffer.queued)
        assertFalse(buffer.offer(5, 2, pcm(), 57_000_000))
    }
}

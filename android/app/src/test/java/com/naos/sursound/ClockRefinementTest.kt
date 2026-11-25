package com.naos.sursound

import org.junit.Assert.*
import org.junit.Test

class ClockRefinementTest {
    private val wire = SyncWire(ByteArray(16) { it.toByte() })
    private val session = 27L
    private val arrival = 1_000_000_000L
    private val baseOffset = 100_000_000L
    private val origin = 1_340_000_000L
    private val localStart = origin - baseOffset

    private fun reply(request: SyncWire.Clock, offset: Long, path: Long = 2_000_000L) =
        request.copy(kind = SyncWire.REPLY, t2 = request.t1 + offset + path,
            t3 = request.t1 + offset + path + 1_000_000L)

    private fun initial(): GroupPlayout {
        val group = GroupPlayout(wire)
        assertTrue(group.offer(SyncWire.Audio(session, 0, origin, ByteArray(LanPacketCodec.PCM_BYTES)), arrival))
        repeat(3) { i ->
            val t = arrival + i * 30_000_000L
            val request = wire.openClock(group.nextProbe(t)!!)!!
            assertTrue(group.acceptReply(reply(request, baseOffset), t + 5_000_000L))
        }
        assertTrue(group.ready)
        return group
    }

    @Test fun positiveAndNegativeClockCorrectionIsBoundedAndPreservesSlots() {
        for (change in listOf(1_000_000L, -1_000_000L)) {
            val group = initial()
            val probeTime = arrival + 1_060_000_000L
            assertNull(group.nextProbe(probeTime - 1))
            val request = wire.openClock(group.nextProbe(probeTime)!!)!!
            val updated = reply(request, baseOffset + change)
            val replyTime = probeTime + 5_000_000L
            var t = localStart
            var sequence = 0L
            while (t < replyTime) {
                assertEquals(sequence++, group.poll(t)!!.sequence)
                t += ReceiverPlayoutBuffer.FRAME_NANOS
            }
            assertTrue(group.acceptReply(updated, replyTime))
            assertFalse(group.acceptReply(updated, replyTime + 1))
            assertEquals(1, group.refinementCount)
            assertEquals(change.toDouble(), group.residualOffsetNanos, 0.01)
            val step = if (change > 0) 10_000L else -10_000L
            repeat(100) {
                assertNull(group.poll(t - 1))
                assertEquals(sequence++, group.poll(t)!!.sequence)
                assertNull(group.poll(t))
                t += ReceiverPlayoutBuffer.FRAME_NANOS - step
            }
            assertEquals(0.0, group.residualOffsetNanos, 0.01)
            assertEquals(0L, group.schedulerSkipped)
            assertEquals(0L, group.maxDispatchLatenessNanos)
            assertEquals(sequence, group.poll(t)!!.sequence)
        }
    }

    @Test fun highRttAndOutlierDoNotResetClockAgeOrTarget() {
        val group = initial()
        val t = arrival + 1_060_000_000L
        val slow = wire.openClock(group.nextProbe(t)!!)!!
        assertFalse(group.acceptReply(reply(slow, baseOffset, 15_000_000L), t + 31_000_000L))
        val t2 = t + 1_000_000_000L
        val bad = wire.openClock(group.nextProbe(t2)!!)!!
        assertFalse(group.acceptReply(reply(bad, baseOffset + 30_000_000L), t2 + 5_000_000L))
        assertEquals(0, group.refinementCount)
        assertEquals(0.0, group.residualOffsetNanos, 0.01)
        assertEquals(t2 + 5_000_000L - (arrival + 65_000_000L), group.clockAgeNanos(t2 + 5_000_000L))
    }

    @Test fun dispatchStallIsReportedWithoutBurst() {
        val group = initial()
        assertEquals(0L, group.poll(localStart)!!.sequence)
        assertEquals(5L, group.poll(localStart + 27_000_000L)!!.sequence)
        assertEquals(22_000_000L, group.maxDispatchLatenessNanos)
        assertEquals(4L, group.schedulerSkipped)
        assertNull(group.poll(localStart + 27_000_000L))
    }
}

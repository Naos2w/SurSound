package com.naos.sursound

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class SyncWireTest {
    private val key = LanPacketCodec.parseKey("00112233445566778899aabbccddeeff")
    private val wire = SyncWire(key)
    private val session = 0x0102030405060708L
    private val pcm = ByteArray(LanPacketCodec.PCM_BYTES) { (it % 251).toByte() }
    private fun hex(data: ByteArray) = data.joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun crossPlatformVectorsAndAuthentication() {
        val data = wire.sealAudio(session, 42, 250_000_000, pcm)
        assertEquals(1001, data.size)
        assertEquals("c9281166d6b1a89940f2c910905e5cf6bec094a5d1903b9825900938a2eb9f83",
            hex(MessageDigest.getInstance("SHA-256").digest(data)))
        val decoded = wire.openAudio(data)!!
        assertEquals(session, decoded.session)
        assertEquals(42, decoded.sequence)
        assertEquals(250_000_000L, decoded.origin)
        assertArrayEquals(pcm, decoded.pcm)
        assertNull(LanPacketCodec.Decoder(key).decode(data))
        assertNull(SyncWire(ByteArray(16)).openAudio(data))
        for (index in listOf(5, 16, 17, 25, data.lastIndex)) {
            val changed = data.copyOf()
            changed[index] = (changed[index].toInt() xor 1).toByte()
            assertNull(wire.openAudio(changed))
        }
        val request = SyncWire.Clock(SyncWire.REQUEST, session, 0x1112131415161718L, 1_000_000_000, 0, 0)
        val clock = wire.sealClock(request)
        assertEquals(77, clock.size)
        assertEquals("535343320101020304050607081112131415161718000000003b9aca0000000000000000000000000000000000e2895c6ec4161c010a76eb566e0ea48ee9c83cc456d67d9297c93ee7a0daadec", hex(clock))
        assertEquals(request, wire.openClock(clock))
        assertNull(SyncWire(ByteArray(16)).openClock(clock))
        assertNull(wire.openClock(clock.copyOf().apply { this[29] = 1 }))
    }

    @Test fun twoReceiverClocksMapSameSenderPresentationAndRejectReplay() {
        val first = GroupPlayout(wire)
        val second = GroupPlayout(wire)
        val origin = 1_340_000_000L
        for ((group, arrival, offset) in listOf(
            Triple(first, 1_000_000_000L, 100_000_000L),
            Triple(second, 500_000_000L, 600_000_000L)
        )) {
            assertTrue(group.offer(SyncWire.Audio(session, 0, origin, pcm.copyOf()), arrival))
            assertTrue(group.offer(SyncWire.Audio(session, 2, origin, pcm.copyOf()), arrival + 1_000_000))
            assertFalse(group.offer(SyncWire.Audio(session, 2, origin, pcm.copyOf()), arrival + 1_000_000))
            assertFalse(group.offer(SyncWire.Audio(session, 1, origin + 1, pcm.copyOf()), arrival + 1_000_000))
            assertNull(group.poll(arrival + 2_000_000))
            repeat(3) { index ->
                val t1 = arrival + index * 30_000_000L
                val request = wire.openClock(group.nextProbe(t1)!!)!!
                val reply = request.copy(kind = SyncWire.REPLY, t2 = t1 + offset + 2_000_000,
                    t3 = t1 + offset + 3_000_000)
                assertFalse(group.acceptReply(reply.copy(nonce = reply.nonce xor 1), t1 + 5_000_000))
                assertTrue(group.acceptReply(reply, t1 + 5_000_000))
                assertFalse(group.acceptReply(reply, t1 + 6_000_000))
            }
            assertTrue(group.ready)
            assertEquals(4_000_000.0, group.roundTripNanos!!, 0.01)
            val local = origin - offset
            assertNull(group.poll(local - 1))
            assertArrayEquals(pcm, group.poll(local)!!.pcm)
            assertNull(group.poll(local + 5_000_000)!!.pcm)
            assertArrayEquals(pcm, group.poll(local + 10_000_000)!!.pcm)
            assertEquals(1L, group.missing)
        }
    }

    @Test fun noClockDoesNotPlayAndPreClockWindowIsBounded() {
        val group = GroupPlayout(wire)
        for (sequence in 0 until 64) {
            assertTrue(group.offer(SyncWire.Audio(session, sequence, 240_000_000, pcm.copyOf()), 0))
        }
        assertFalse(group.offer(SyncWire.Audio(session, 64, 240_000_000, pcm.copyOf()), 0))
        repeat(12) { assertNotNull(group.nextProbe(it * 100_000_000L)) }
        assertNull(group.poll(1_300_000_000))
        assertFalse(group.ready)
        assertNull(group.nextProbe(1_400_000_000))
    }

    @Test fun syntheticPulseFinalSlotStopsWithoutInfiniteSilence() {
        val group = GroupPlayout(wire)
        val seq = SyncWire.MAX_PACKETS - 1
        val origin = 240_000_000L - seq * 5_000_000L
        assertTrue(group.offer(SyncWire.Audio(session, seq, origin, pcm.copyOf()), 0))
        repeat(3) {
            val t1 = it * 30_000_000L
            val req = wire.openClock(group.nextProbe(t1)!!)!!
            assertTrue(group.acceptReply(req.copy(kind = SyncWire.REPLY, t2 = t1 + 2_000_000, t3 = t1 + 3_000_000), t1 + 5_000_000))
        }
        assertEquals(seq.toLong(), group.poll(240_000_000)!!.sequence)
        assertNull(group.poll(245_000_000))
        assertNull(group.poll(250_000_000))
    }
}

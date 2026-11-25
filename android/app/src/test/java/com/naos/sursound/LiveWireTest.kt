package com.naos.sursound

import org.junit.Assert.*
import org.junit.Test

class LiveWireTest {
    private val key = LanPacketCodec.parseKey("00112233445566778899aabbccddeeff")
    private val session = 0x0102030405060708L
    private val wire = LiveWire(key)

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test fun sharedIndependentVectorAndAudioKeySeparation() {
        val c = LiveWire.Control(LiveWire.PROBE, session, 2, 7)
        val packet = wire.seal(c)
        assertEquals("534c4331010102030405060708000000020000000000000007a1d2c3ffbc562c5c37a4ca983e7c29d4d05a2d4ec04c9acf348d5fc8eda384c9", hex(packet))
        assertEquals("1d50ec1c429d167fbbd0237c1c94859e", hex(wire.audioKey(session, 2)))
        assertEquals(c, wire.open(packet))
        assertFalse(wire.audioKey(session, 2).contentEquals(wire.audioKey(session, 3)))
        assertFalse(wire.audioKey(session, 2).contentEquals(wire.audioKey(session + 1, 2)))
        assertNull(LiveWire(ByteArray(16)).open(packet))
        for (i in listOf(0, 4, 12, 16, 24, 25, packet.lastIndex)) {
            assertNull(wire.open(packet.copyOf().apply { this[i] = (this[i].toInt() xor 1).toByte() }))
        }
        assertNull(wire.open(packet.copyOf(packet.size - 1)))
    }

    @Test fun gateRequiresFreshMatchingAckAndRekeysOnLoss() {
        val gate = LiveSenderGate(session, 0)
        val first = requireNotNull(gate.probe(0))
        assertEquals(LiveSenderGate.Phase.WAITING, gate.phase)
        assertFalse(gate.acknowledge(first.copy(kind = LiveWire.ACK, session = session+1), 10))
        assertFalse(gate.acknowledge(first.copy(kind = LiveWire.ACK, counter = first.counter+1), 10))
        assertTrue(gate.acknowledge(first.copy(kind = LiveWire.ACK), 100))
        assertFalse(gate.acknowledge(first.copy(kind = LiveWire.ACK), 101))
        assertEquals(LiveSenderGate.Phase.ACTIVE, gate.phase)
        gate.tick(LiveSenderGate.ACK_LOSS_NS + 100)
        assertEquals(LiveSenderGate.Phase.WAITING, gate.phase)
        assertEquals(1, gate.generation)
        val next = requireNotNull(gate.probe(LiveSenderGate.ACK_LOSS_NS + 100))
        assertTrue(next.counter > first.counter)
        assertFalse(gate.acknowledge(first.copy(kind = LiveWire.ACK), LiveSenderGate.ACK_LOSS_NS + 101))
        assertTrue(gate.acknowledge(next.copy(kind = LiveWire.ACK), LiveSenderGate.ACK_LOSS_NS + 200))
        assertEquals(1, gate.recoveries)
        val stop = gate.stop()
        assertEquals(LiveWire.STOP, stop.kind)
        assertTrue(stop.counter > next.counter)
        assertEquals(LiveSenderGate.Phase.CLOSED, gate.phase)
        assertNull(gate.probe(20_000_000_000))
    }

    @Test fun lateAckCannotOpenGateAndRecoveryIsBounded() {
        val gate = LiveSenderGate(session, 0)
        val first = requireNotNull(gate.probe(0))
        assertFalse(gate.acknowledge(first.copy(kind = LiveWire.ACK), LiveSenderGate.PROBE_NS + 1))
        gate.tick(LiveSenderGate.RETRY_GENERATION_NS)
        assertEquals(1, gate.generation)
        assertEquals(LiveSenderGate.Phase.WAITING, gate.phase)
        gate.tick(LiveSenderGate.RECOVERY_NS)
        assertEquals(LiveSenderGate.Phase.CLOSED, gate.phase)
        assertNull(gate.probe(LiveSenderGate.RECOVERY_NS + 1))
    }
}

package com.naos.sursound

import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class LanPacketCodecTest {
    private val key = LanPacketCodec.parseKey("00112233445566778899aabbccddeeff")
    private val pcm = ByteArray(LanPacketCodec.PCM_BYTES) { (it % 251).toByte() }

    @Test fun authenticatedRoundTripAndUniqueNonce() {
        val encoder = LanPacketCodec.Encoder(key)
        val decoder = LanPacketCodec.Decoder(key)
        val first = encoder.encode(pcm)
        val second = encoder.encode(pcm)
        assertTrue(first.size < 1200)
        assertFalse(first.contentEquals(second))
        val a = decoder.decode(first)!!
        val b = decoder.decode(second)!!
        assertEquals(a.session, b.session)
        assertEquals(0, a.sequence)
        assertEquals(1, b.sequence)
        assertArrayEquals(pcm, a.pcm)
        assertArrayEquals(pcm, b.pcm)
    }

    @Test fun rejectTamperingWrongKeyAndTruncation() {
        val bytes = LanPacketCodec.Encoder(key).encode(pcm)
        val decoder = LanPacketCodec.Decoder(key)
        assertNull(decoder.decode(bytes.copyOf(bytes.size - 1)))
        assertNull(decoder.decode(bytes.copyOf(bytes.size + 1)))
        assertNull(LanPacketCodec.Decoder(ByteArray(16)).decode(bytes))
        for (index in listOf(5, 16, 19, bytes.lastIndex)) {
            val tampered = bytes.copyOf()
            tampered[index] = (tampered[index].toInt() xor 1).toByte()
            assertNull(decoder.decode(tampered))
        }
    }

    @Test fun rejectReplayForeignSessionAndCountGaps() {
        val encoder = LanPacketCodec.Encoder(key)
        val decoder = LanPacketCodec.Decoder(key)
        val guard = LanPacketCodec.ReplayGuard()
        val first = decoder.decode(encoder.encode(pcm))!!
        encoder.encode(pcm) // deliberately missing
        val third = decoder.decode(encoder.encode(pcm))!!
        assertTrue(guard.accept(first))
        assertFalse(guard.accept(first))
        assertTrue(guard.accept(third))
        assertEquals(1L, guard.missing)
        assertFalse(guard.accept(first))
        assertFalse(guard.accept(decoder.decode(LanPacketCodec.Encoder(key).encode(pcm))!!))
    }

    @Test fun udpLoopbackTransfersAuthenticatedPcm() {
        val loopback = InetAddress.getByName("127.0.0.1")
        DatagramSocket(0, loopback).use { receiver ->
            receiver.soTimeout = 2000
            DatagramSocket(0, loopback).use { sender ->
                val bytes = LanPacketCodec.Encoder(key).encode(pcm)
                sender.send(DatagramPacket(bytes, bytes.size, loopback, receiver.localPort))
                val buffer = ByteArray(1200)
                val packet = DatagramPacket(buffer, buffer.size)
                receiver.receive(packet)
                assertArrayEquals(pcm, LanPacketCodec.Decoder(key).decode(buffer.copyOf(packet.length))!!.pcm)
            }
        }
    }

    @Test fun validateLocalPeersAndPairingKeys() {
        assertEquals("192.168.1.20", LanPacketCodec.localPeer("192.168.1.20").hostAddress)
        assertEquals("172.16.1.1", LanPacketCodec.localPeer("172.16.1.1").hostAddress)
        for (invalid in listOf("8.8.8.8", "172.32.0.1", "example.com", "192.168.1.256", "127.0.0.1")) {
            assertTrue(runCatching { LanPacketCodec.localPeer(invalid) }.isFailure)
        }
        assertEquals(16, LanPacketCodec.parseKey(LanPacketCodec.generateKey()).size)
        assertTrue(runCatching { LanPacketCodec.parseKey("1234") }.isFailure)
    }
}

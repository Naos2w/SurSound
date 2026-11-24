package com.naos.sursound

import org.junit.Assert.*
import org.junit.Test

class PcmPacketizerTest {
    @Test fun arbitraryReadSizesPreserveLittleEndianStereoStream() {
        val output = mutableListOf<ByteArray>()
        val packetizer = PcmPacketizer { output.add(it) }
        val samples = ShortArray(960) { (it * 137 - 32768).toShort() }
        packetizer.offer(samples.copyOfRange(0, 17), 17)
        packetizer.offer(samples.copyOfRange(17, 479), 462)
        assertTrue(output.isEmpty())
        packetizer.offer(samples.copyOfRange(479, 960), 481)
        assertEquals(2, output.size)
        val bytes = output[0] + output[1]
        for (i in samples.indices) {
            val value = (bytes[i * 2].toInt() and 255) or ((bytes[i * 2 + 1].toInt() and 255) shl 8)
            assertEquals(samples[i], value.toShort())
        }
    }

    @Test fun clearDiscardsPartialAudio() {
        val output = mutableListOf<ByteArray>()
        val packetizer = PcmPacketizer { output.add(it) }
        packetizer.offer(ShortArray(479) { 42 }, 479)
        packetizer.clear()
        packetizer.offer(ShortArray(480), 480)
        assertEquals(1, output.size)
        assertTrue(output.single().all { it == 0.toByte() })
    }
}

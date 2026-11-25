package com.naos.sursound

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Live control is separate from v1 audio and the existing v2 group protocol. */
internal class LiveWire(key: ByteArray) {
    private val root = SecretKeySpec(key.copyOf().also { require(it.size == 16) }, "HmacSHA256")
    private val controlKey = SecretKeySpec(mac(root, CONTROL_LABEL), "HmacSHA256")

    data class Control(val kind: Int, val session: Long, val generation: Int, val counter: Long)

    fun seal(c: Control): ByteArray {
        require(c.kind in PROBE..STOP && c.generation in 0..MAX_GENERATION && c.counter > 0)
        val header = ByteBuffer.allocate(25).order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC).put(c.kind.toByte()).putLong(c.session)
            .putInt(c.generation).putLong(c.counter).array()
        return header + mac(controlKey, header)
    }

    fun open(bytes: ByteArray): Control? {
        if (bytes.size != CONTROL_BYTES) return null
        val header = bytes.copyOfRange(0, 25)
        if (!MessageDigest.isEqual(mac(controlKey, header), bytes.copyOfRange(25, CONTROL_BYTES))) return null
        val b = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        if (b.int != MAGIC) return null
        val c = Control(b.get().toInt(), b.long, b.int, b.long)
        return c.takeIf { it.kind in PROBE..STOP && it.generation in 0..MAX_GENERATION && it.counter > 0 }
    }

    fun audioKey(session: Long, generation: Int): ByteArray {
        require(generation in 0..MAX_GENERATION)
        val suffix = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .putLong(session).putInt(generation).array()
        return mac(root, AUDIO_LABEL + suffix).copyOf(16)
    }

    private fun mac(key: SecretKeySpec, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(data)

    companion object {
        const val PROBE = 1
        const val ACK = 2
        const val STOP = 3
        const val MAX_GENERATION = 4095
        const val CONTROL_BYTES = 57
        private const val MAGIC = 0x534c4331 // SLC1
        private val CONTROL_LABEL = "SurSound/live/control/v1".toByteArray(Charsets.UTF_8)
        private val AUDIO_LABEL = "SurSound/live/audio/v1".toByteArray(Charsets.UTF_8)
        fun newSession(): Long = SecureRandom().nextLong()
    }
}

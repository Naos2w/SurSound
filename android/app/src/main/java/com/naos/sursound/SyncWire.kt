package com.naos.sursound

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Independent v2 keys, authenticated presentation epoch and fixed-size clock messages. */
internal class SyncWire(pairingKey: ByteArray) {
    init { require(pairingKey.size == 16) }
    private val audioKey = SecretKeySpec(derive(pairingKey, "SurSound/sync/audio/v2").copyOf(16), "AES")
    private val controlKey = SecretKeySpec(derive(pairingKey, "SurSound/sync/control/v2"), "HmacSHA256")

    data class Audio(val session: Long, val sequence: Int, val origin: Long, val pcm: ByteArray)
    data class Clock(val kind: Int, val session: Long, val nonce: Long, val t1: Long, val t2: Long, val t3: Long)

    /** Called once per sequence; fan out the resulting ciphertext, not a second encryption. */
    fun sealAudio(session: Long, sequence: Int, origin: Long, pcm: ByteArray): ByteArray {
        require(sequence in 0 until MAX_PACKETS && pcm.size == LanPacketCodec.PCM_BYTES)
        val header = ByteBuffer.allocate(AUDIO_HEADER).order(ByteOrder.BIG_ENDIAN)
            .putInt(0x53535032).put(2.toByte()).putLong(session).putInt(sequence).putLong(origin).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, audioKey, GCMParameterSpec(128, header.copyOfRange(5, 17)))
        cipher.updateAAD(header)
        return header + cipher.doFinal(pcm)
    }

    fun openAudio(bytes: ByteArray): Audio? {
        if (bytes.size != AUDIO_BYTES) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (buf.int != 0x53535032 || buf.get() != 2.toByte()) return null
        val session = buf.long
        val sequence = buf.int
        val origin = buf.long
        if (sequence !in 0 until MAX_PACKETS) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, audioKey, GCMParameterSpec(128, bytes.copyOfRange(5, 17)))
            cipher.updateAAD(bytes, 0, AUDIO_HEADER)
            Audio(session, sequence, origin, cipher.doFinal(bytes, AUDIO_HEADER, AUDIO_BYTES - AUDIO_HEADER))
        } catch (_: GeneralSecurityException) { null }
    }

    fun sealClock(clock: Clock): ByteArray {
        require(clock.kind == REQUEST || clock.kind == REPLY)
        require(clock.kind != REQUEST || (clock.t2 == 0L && clock.t3 == 0L))
        val header = ByteBuffer.allocate(CLOCK_HEADER).order(ByteOrder.BIG_ENDIAN)
            .putInt(0x53534332).put(clock.kind.toByte()).putLong(clock.session).putLong(clock.nonce)
            .putLong(clock.t1).putLong(clock.t2).putLong(clock.t3).array()
        return header + mac(header)
    }

    fun openClock(bytes: ByteArray): Clock? {
        if (bytes.size != CLOCK_BYTES) return null
        val header = bytes.copyOfRange(0, CLOCK_HEADER)
        if (!MessageDigest.isEqual(mac(header), bytes.copyOfRange(CLOCK_HEADER, CLOCK_BYTES))) return null
        val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        if (buf.int != 0x53534332) return null
        val kind = buf.get().toInt()
        if (kind != REQUEST && kind != REPLY) return null
        val clock = Clock(kind, buf.long, buf.long, buf.long, buf.long, buf.long)
        if (kind == REQUEST && (clock.t2 != 0L || clock.t3 != 0L)) return null
        return clock
    }

    private fun mac(bytes: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(controlKey) }.doFinal(bytes)

    companion object {
        const val REQUEST = 1
        const val REPLY = 2
        const val MAX_PACKETS = 6000
        const val LEAD_NANOS = 240_000_000L
        private const val AUDIO_HEADER = 25
        private const val CLOCK_HEADER = 45
        const val AUDIO_BYTES = AUDIO_HEADER + LanPacketCodec.PCM_BYTES + 16
        const val CLOCK_BYTES = CLOCK_HEADER + 32
        private fun derive(key: ByteArray, label: String): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
                .doFinal(label.toByteArray(Charsets.UTF_8))

        fun newNonce(): Long = SecureRandom().nextLong()
    }
}

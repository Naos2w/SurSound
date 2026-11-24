package com.naos.sursound

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Phase 0: fixed-size authenticated synthetic PCM packets; not RTP or a sync protocol. */
internal object LanPacketCodec {
    const val SAMPLE_RATE = 48_000
    const val FRAMES = 240 // 5 ms; stereo PCM16 stays below typical LAN MTU
    const val PCM_BYTES = FRAMES * 2 * 2
    private const val MAGIC = 0x53535030 // SSP0
    private const val VERSION: Byte = 1
    private const val HEADER = 17
    private const val TAG = 16
    const val PACKET_BYTES = HEADER + PCM_BYTES + TAG

    data class Packet(val session: Long, val sequence: Int, val pcm: ByteArray)

    fun generateKey(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun parseKey(text: String): ByteArray {
        val value = text.trim()
        require(value.length == 32 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "Use the same 32-character hexadecimal pairing key on both devices."
        }
        return ByteArray(16) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    class Encoder(key: ByteArray) {
        private val secret = SecretKeySpec(key.copyOf().also { require(it.size == 16) }, "AES")
        private val session = SecureRandom().nextLong()
        private var next = 0

        fun encode(pcm: ByteArray): ByteArray {
            require(pcm.size == PCM_BYTES)
            check(next < Int.MAX_VALUE) { "Session exhausted; create a new pairing key." }
            val sequence = next++
            val header = ByteBuffer.allocate(HEADER).order(ByteOrder.BIG_ENDIAN)
                .putInt(MAGIC).put(VERSION).putLong(session).putInt(sequence).array()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, header.copyOfRange(5, HEADER)))
            cipher.updateAAD(header)
            return header + cipher.doFinal(pcm)
        }
    }

    class Decoder(key: ByteArray) {
        private val secret = SecretKeySpec(key.copyOf().also { require(it.size == 16) }, "AES")

        fun decode(bytes: ByteArray): Packet? {
            if (bytes.size != PACKET_BYTES) return null
            val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            if (input.int != MAGIC || input.get() != VERSION) return null
            val session = input.long
            val sequence = input.int
            if (sequence < 0) return null
            val header = bytes.copyOfRange(0, HEADER)
            return try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, header.copyOfRange(5, HEADER)))
                cipher.updateAAD(header)
                val pcm = cipher.doFinal(bytes, HEADER, PCM_BYTES + TAG)
                if (pcm.size != PCM_BYTES) null else Packet(session, sequence, pcm)
            } catch (_: GeneralSecurityException) {
                null
            }
        }
    }

    /** Lock to the first authenticated session; discard duplicates, stale or foreign packets. */
    class ReplayGuard {
        private var session: Long? = null
        private var highest = -1
        var missing: Long = 0
            private set

        fun accept(packet: Packet): Boolean {
            if (session == null) {
                session = packet.session
                highest = packet.sequence
                return true
            }
            if (session != packet.session || packet.sequence <= highest) return false
            missing += packet.sequence.toLong() - highest.toLong() - 1
            highest = packet.sequence
            return true
        }
    }

    /** No DNS or public IPs in this PoC. Explicit local peer only. */
    fun localPeer(text: String): InetAddress {
        val parts = text.trim().split('.')
        require(parts.size == 4 && parts.all { it.length in 1..3 && it.all { ch -> ch in '0'..'9' } }) {
            "Enter the other device's local IPv4 address."
        }
        val a = parts.map { it.toInt() }
        require(a.all { it in 0..255 })
        require(a[0] == 10 || (a[0] == 172 && a[1] in 16..31) ||
            (a[0] == 192 && a[1] == 168) || (a[0] == 169 && a[1] == 254)) {
            "Only private or link-local IPv4 peers are supported in this PoC."
        }
        return InetAddress.getByAddress(a.map { it.toByte() }.toByteArray())
    }
}

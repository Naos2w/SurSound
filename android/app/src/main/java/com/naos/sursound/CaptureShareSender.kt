package com.naos.sursound

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Splits arbitrary stereo PCM16 reads into the existing 5 ms wire format. */
internal class PcmPacketizer(private val emit: (ByteArray) -> Unit) {
    private val buffer = ByteArray(LanPacketCodec.PCM_BYTES)
    private var position = 0

    fun offer(samples: ShortArray, count: Int) {
        require(count in 0..samples.size)
        for (i in 0 until count) {
            val sample = samples[i].toInt()
            buffer[position++] = sample.toByte()
            buffer[position++] = (sample shr 8).toByte()
            if (position == buffer.size) {
                val packet = buffer.copyOf()
                buffer.fill(0)
                position = 0
                emit(packet)
            }
        }
    }

    fun clear() {
        buffer.fill(0)
        position = 0
    }
}

/** One explicit, bounded, consent-driven LAN capture session; no disk or cloud output. */
internal class CaptureShareSender(peer: InetAddress, key: ByteArray) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val queue = ArrayBlockingQueue<ByteArray>(12)
    private val sentCount = AtomicLong()
    private val droppedCount = AtomicLong()
    @Volatile private var socket: DatagramSocket? = null
    @Volatile var failure: String? = null
        private set

    val sent: Long get() = sentCount.get()
    val dropped: Long get() = droppedCount.get()

    private val packetizer = PcmPacketizer { bytes ->
        if (!running.get() || !queue.offer(bytes)) {
            bytes.fill(0)
            droppedCount.incrementAndGet()
        }
    }

    private val worker = Thread({
        try {
            val encoder = LanPacketCodec.Encoder(key)
            key.fill(0)
            DatagramSocket().use { udp ->
                socket = udp
                if (!running.get()) return@Thread
                while (running.get()) {
                    val pcm = queue.take()
                    try {
                        if (!running.get()) break
                        val packet = encoder.encode(pcm)
                        if (!running.get()) break
                        udp.send(DatagramPacket(packet, packet.size, peer, LanTestSession.PORT))
                        sentCount.incrementAndGet()
                    } finally {
                        pcm.fill(0)
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) failure = "LAN send failed: " + e.javaClass.simpleName
        } finally {
            key.fill(0)
            running.set(false)
            socket = null
            clearQueue()
        }
    }, "SurSoundCaptureSender").apply { start() }

    @Synchronized fun offer(samples: ShortArray, count: Int) {
        if (running.get()) packetizer.offer(samples, count)
    }

    @Synchronized override fun close() {
        running.set(false)
        socket?.close()
        worker.interrupt()
        packetizer.clear()
        clearQueue()
    }

    private fun clearQueue() {
        while (true) (queue.poll() ?: break).fill(0)
    }
}

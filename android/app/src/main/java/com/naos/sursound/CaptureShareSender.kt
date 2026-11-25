package com.naos.sursound

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
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

/** Explicit, bounded consent-driven sharing; v1 single peer or v2 group. */
internal class CaptureShareSender(
    peers: List<InetAddress>, key: ByteArray, private val group: Boolean
) : AutoCloseable {
    private val targets = peers.toList().also {
        require(it.size in 1..4 && it.distinct().size == it.size && (group || it.size == 1))
    }
    private data class Captured(val pcm: ByteArray, val slot: CaptureTimeline.Slot?)
    private val running = AtomicBoolean(true)
    private val queue = ArrayBlockingQueue<Captured>(12)
    private val timeline = CaptureTimeline()
    private val sentCount = AtomicLong()
    private val droppedCount = AtomicLong()
    private val discardedQueuedCount = AtomicLong()
    @Volatile private var socket: DatagramSocket? = null
    @Volatile var failure: String? = null
        private set

    val sent: Long get() = sentCount.get()
    val dropped: Long get() = droppedCount.get()

    private val packetizer = PcmPacketizer { bytes ->
        val slot = if (group) timeline.reserve(System.nanoTime()) else null
        if (!running.get() || (group && slot == null) || !queue.offer(Captured(bytes, slot))) {
            bytes.fill(0)
            droppedCount.addAndGet(targets.size.toLong())
        }
    }

    private val worker = Thread({
        try {
            val encoder = if (group) null else LanPacketCodec.Encoder(key)
            val wire = if (group) SyncWire(key) else null
            val session = if (group) SyncWire.newNonce() else 0L
            key.fill(0)
            DatagramSocket().use { udp ->
                socket = udp
                if (!running.get()) return@Thread
                if (wire == null) {
                    checkNotNull(encoder)
                    while (running.get()) {
                        val item = queue.take()
                        try {
                            if (!running.get()) break
                            val packet = encoder.encode(item.pcm)
                            if (!running.get()) break
                            udp.send(DatagramPacket(packet, packet.size, targets.single(), LanTestSession.PORT))
                            sentCount.incrementAndGet()
                        } finally {
                            item.pcm.fill(0)
                        }
                    }
                } else {
                    sendGroup(udp, wire, session)
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

    private fun sendGroup(udp: DatagramSocket, wire: SyncWire, session: Long) {
        udp.soTimeout = 1
        val input = ByteArray(SyncWire.CLOCK_BYTES + 1)
        val requestPacket = DatagramPacket(input, input.size)
        val lastReply = HashMap<InetAddress, Long>()
        while (running.get()) {
            queue.poll()?.let { item ->
                try {
                    val slot = checkNotNull(item.slot)
                    if (!running.get() || slot.expired(System.nanoTime())) {
                        droppedCount.addAndGet(targets.size.toLong())
                    } else {
                        // A slot is sealed once, then the same ciphertext is sent to each selected receiver.
                        val encrypted = wire.sealAudio(session, slot.sequence, slot.origin, item.pcm)
                        for (peer in targets) {
                            if (!running.get()) break
                            if (slot.expired(System.nanoTime())) {
                                droppedCount.incrementAndGet()
                                continue
                            }
                            try {
                                udp.send(DatagramPacket(encrypted, encrypted.size, peer, LanTestSession.PORT))
                                sentCount.incrementAndGet()
                            } catch (_: java.io.IOException) {
                                if (running.get()) droppedCount.incrementAndGet()
                            }
                        }
                    }
                } finally { item.pcm.fill(0) }
            }
            if (!running.get()) break
            requestPacket.length = input.size
            try {
                udp.receive(requestPacket)
                val t2 = System.nanoTime()
                val peer = requestPacket.address
                if (!running.get() || peer !in targets) continue
                val request = wire.openClock(input.copyOf(requestPacket.length)) ?: continue
                if (request.kind != SyncWire.REQUEST || request.session != session) continue
                if (lastReply[peer]?.let { t2 - it < 10_000_000L } == true) continue
                lastReply[peer] = t2
                val reply = wire.sealClock(request.copy(kind = SyncWire.REPLY, t2 = t2, t3 = System.nanoTime()))
                if (running.get()) udp.send(DatagramPacket(reply, reply.size, peer, requestPacket.port))
            } catch (_: SocketTimeoutException) {
                // Poll continuously to service both the bounded capture queue and clock exchange.
            }
        }
    }

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

    /** Wait only briefly after socket closure; never block capture teardown indefinitely. */
    fun awaitStopped(timeoutMillis: Long = 250) {
        require(timeoutMillis in 1..500)
        try {
            worker.join(timeoutMillis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun stats(): CaptureState.ShareStats = CaptureState.ShareStats(
        group, targets.size, sentCount.get(), droppedCount.get(), discardedQueuedCount.get(), !worker.isAlive
    )

    private fun clearQueue() {
        while (true) {
            val item = queue.poll() ?: break
            item.pcm.fill(0)
            discardedQueuedCount.addAndGet(targets.size.toLong())
        }
    }
}

package com.naos.sursound

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Single-receiver live capture. A fresh authenticated generation is required after loss. */
internal class LiveCaptureSender(
    private val peer: InetAddress,
    key: ByteArray,
    private val path: LiveNetwork
) : CaptureSender {
    private data class Captured(val generation: Int, val pcm: ByteArray)
    private val running = AtomicBoolean(true)
    private val queue = ArrayBlockingQueue<Captured>(12)
    private val sent = AtomicLong()
    private val dropped = AtomicLong()
    private val discarded = AtomicLong()
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var activeGeneration: Int? = null
    @Volatile private var connection = "Waiting for authenticated receiver"
    @Volatile private var recoveries = 0
    @Volatile override var failure: String? = null
        private set

    private val packetizer = PcmPacketizer { bytes ->
        val generation = activeGeneration
        if (!running.get() || generation == null || !queue.offer(Captured(generation, bytes))) {
            bytes.fill(0)
            dropped.incrementAndGet()
        }
    }

    private val worker = Thread({
        try {
            val wire = LiveWire(key)
            key.fill(0)
            val session = LiveWire.newSession()
            val gate = LiveSenderGate(session, System.nanoTime())
            DatagramSocket().use { udp ->
                socket = udp
                path.bind(udp)
                udp.connect(peer, LanTestSession.PORT)
                udp.soTimeout = 2
                val input = ByteArray(LiveWire.CONTROL_BYTES + 1)
                val datagram = DatagramPacket(input, input.size)
                var encoder: LanPacketCodec.Encoder? = null
                var encoderGeneration = -1
                var lastPathCheck = System.nanoTime()
                try {
                    while (running.get()) {
                        val now = System.nanoTime()
                        if (now - lastPathCheck >= 100_000_000L) {
                            check(path.stillValid()) { "Selected Wi-Fi changed. Start a fresh pairing." }
                            lastPathCheck = now
                        }
                        gate.tick(now)
                        if (gate.phase == LiveSenderGate.Phase.CLOSED) {
                            failure = "Receiver unavailable. Sharing stopped; start a fresh pairing."
                            break
                        }
                        updateGate(gate)
                        gate.probe(now)?.let {
                            try {
                                val bytes = wire.seal(it)
                                if (running.get()) udp.send(DatagramPacket(bytes, bytes.size))
                            } catch (_: IOException) { /* ACK timeout determines liveness. */ }
                        }
                        if (gate.phase == LiveSenderGate.Phase.ACTIVE && encoderGeneration != gate.generation) {
                            val audioKey = wire.audioKey(session, gate.generation)
                            try { encoder = LanPacketCodec.Encoder(audioKey) }
                            finally { audioKey.fill(0) }
                            encoderGeneration = gate.generation
                        }
                        val item = queue.poll()
                        if (item != null) {
                            try {
                                val current = encoder
                                if (running.get() && gate.phase == LiveSenderGate.Phase.ACTIVE &&
                                    item.generation == gate.generation && current != null) {
                                    val bytes = current.encode(item.pcm)
                                    try {
                                        if (running.get()) {
                                            udp.send(DatagramPacket(bytes, bytes.size))
                                            sent.incrementAndGet()
                                        } else discarded.incrementAndGet()
                                    } catch (_: IOException) { dropped.incrementAndGet() }
                                } else discarded.incrementAndGet()
                            } finally { item.pcm.fill(0) }
                        }
                        datagram.length = input.size
                        try {
                            udp.receive(datagram)
                            val reply = wire.open(input.copyOf(datagram.length))
                            if (reply != null && running.get()) {
                                gate.acknowledge(reply, System.nanoTime())
                                updateGate(gate)
                            }
                        } catch (_: SocketTimeoutException) {
                        } catch (_: IOException) { /* No fresh ACK means pause/expiry. */ }
                    }
                } finally {
                    // No media after the stop flag; authenticated STOP is best effort only.
                    if (path.stillValid()) runCatching {
                        val bytes = wire.seal(gate.stop())
                        udp.send(DatagramPacket(bytes, bytes.size))
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) failure = "Live sharing stopped: " + (e.message ?: e.javaClass.simpleName)
        } finally {
            key.fill(0)
            running.set(false)
            socket = null
            synchronized(this) {
                activeGeneration = null
                packetizer.clear()
                clearQueue()
            }
            connection = "Stopped"
        }
    }, "SurSoundLiveSender").apply { start() }

    @Synchronized private fun updateGate(gate: LiveSenderGate) {
        val next = if (running.get() && gate.phase == LiveSenderGate.Phase.ACTIVE) gate.generation else null
        if (next != activeGeneration) {
            // New generation never uses audio captured while waiting or in an older epoch.
            activeGeneration = null
            packetizer.clear()
            clearQueue()
            activeGeneration = next
        }
        recoveries = gate.recoveries
        connection = if (next == null) "Waiting / recovering — audio paused" else "Authenticated live receiver"
    }

    @Synchronized override fun offer(samples: ShortArray, count: Int) {
        if (running.get()) packetizer.offer(samples, count)
    }

    @Synchronized override fun close() {
        running.set(false)
        activeGeneration = null
        packetizer.clear()
        clearQueue()
        // The network worker exits its 2 ms read and sends best-effort authenticated STOP.
    }

    override fun awaitStopped(timeoutMillis: Long) {
        require(timeoutMillis in 1..500)
        try {
            worker.join(timeoutMillis)
            if (worker.isAlive) socket?.close()
        } catch (_: InterruptedException) {
            socket?.close()
            Thread.currentThread().interrupt()
        }
    }

    override fun stats(): CaptureState.ShareStats = CaptureState.ShareStats(
        false, 1, sent.get(), dropped.get(), discarded.get(), !worker.isAlive,
        live = true, connection = connection, recoveries = recoveries
    )

    private fun clearQueue() {
        while (true) {
            val item = queue.poll() ?: break
            item.pcm.fill(0)
            discarded.incrementAndGet()
        }
    }
}

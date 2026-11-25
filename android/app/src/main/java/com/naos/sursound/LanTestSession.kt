package com.naos.sursound

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

/** Foreground-only, bounded test signal. Never reads microphone or captured app audio. */
internal class LanTestSession private constructor() {
    data class Status(
        val message: String = "Starting",
        val packets: Long = 0,
        val dropped: Long = 0,
        val running: Boolean = true
    )

    @Volatile var status = Status()
        private set
    private val stopped = AtomicBoolean(false)
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var worker: Thread? = null

    fun stop() {
        stopped.set(true)
        socket?.close()
        worker?.interrupt()
    }

    private fun launch(name: String, key: ByteArray, block: (ByteArray) -> Unit) {
        worker = Thread({
            try {
                block(key)
                status = status.copy(message = if (stopped.get()) "Stopped" else "Test ended", running = false)
            } catch (e: Exception) {
                status = status.copy(
                    message = if (stopped.get()) "Stopped" else "Test failed: " + e.javaClass.simpleName,
                    running = false
                )
            } finally {
                stopped.set(true)
                socket?.close()
                socket = null
                key.fill(0)
            }
        }, name).also { it.start() }
    }

    private fun attach(value: DatagramSocket): DatagramSocket {
        socket = value
        if (stopped.get()) value.close()
        return value
    }

    private fun send(peer: InetAddress, key: ByteArray) {
        val encoder = LanPacketCodec.Encoder(key)
        attach(DatagramSocket()).use { udp ->
            val pcm = ByteBuffer.allocate(LanPacketCodec.PCM_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            var frame = 0L
            var count = 0L
            val start = System.nanoTime()
            var next = start
            while (!stopped.get() && System.nanoTime() - start < 30_000_000_000L) {
                pcm.clear()
                repeat(LanPacketCodec.FRAMES) {
                    val sample = (sin(2.0 * PI * 440.0 * frame / LanPacketCodec.SAMPLE_RATE) * 2400).toInt().toShort()
                    pcm.putShort(sample).putShort(sample)
                    frame++
                }
                val data = encoder.encode(pcm.array())
                udp.send(DatagramPacket(data, data.size, peer, PORT))
                count++
                status = Status("Sending 440 Hz test tone", count)
                next += 5_000_000L
                val delay = next - System.nanoTime()
                if (delay > 0) Thread.sleep(delay / 1_000_000, (delay % 1_000_000).toInt())
                else if (delay < -5_000_000L) next = System.nanoTime()
            }
            pcm.array().fill(0)
        }
    }

    private fun receive(peer: InetAddress, key: ByteArray) {
        val decoder = LanPacketCodec.Decoder(key)
        val playout = ReceiverPlayoutBuffer()
        attach(DatagramSocket(PORT)).use { udp ->
            udp.soTimeout = 100
            val min = AudioTrack.getMinBufferSize(LanPacketCodec.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT)
            check(min > 0) { "Unsupported output format" }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(LanPacketCodec.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min, LanPacketCodec.PCM_BYTES * 8))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            try {
                check(track.state == AudioTrack.STATE_INITIALIZED)
                track.setVolume(0.25f)
                val storage = ByteArray(LanPacketCodec.PACKET_BYTES + 1)
                val datagram = DatagramPacket(storage, storage.size)
                val start = System.nanoTime()
                var count = 0L
                var rejected = 0L
                var outputDrops = 0L
                var playing = false
                val silence = ByteArray(LanPacketCodec.PCM_BYTES)
                status = Status("Listening for authenticated audio packets")
                while (!stopped.get() && System.nanoTime() - start < 45_000_000_000L) {
                    // Once anchored, poll on timeout too so a lost datagram cannot stall audio.
                    udp.soTimeout = if (playout.started) 2 else 100
                    datagram.length = storage.size
                    var changed = false
                    try {
                        udp.receive(datagram)
                        if (stopped.get()) break
                        val packet = if (datagram.address == peer) decoder.decode(storage.copyOf(datagram.length)) else null
                        if (packet == null) {
                            rejected++
                        } else if (playout.offer(packet.session, packet.sequence, packet.pcm, System.nanoTime())) {
                            count++
                        } else {
                            packet.pcm.fill(0)
                            rejected++
                        }
                        changed = true
                    } catch (_: SocketTimeoutException) {
                        // The local monotonic playout clock still advances.
                    }
                    if (stopped.get()) break
                    val block = playout.poll(System.nanoTime())
                    if (block != null) {
                        if (!playing) {
                            track.play()
                            playing = true
                        }
                        val pcm = block.pcm ?: silence
                        val written = try {
                            track.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
                        } finally {
                            block.pcm?.fill(0)
                        }
                        check(written >= 0) { "AudioTrack write error" }
                        if (written != pcm.size) outputDrops++
                        changed = true
                    }
                    if (changed) {
                        val message = if (playing) "Playing buffered authenticated LAN audio"
                            else if (playout.started) "Listening — buffering authenticated audio"
                            else "Listening for authenticated audio packets"
                        status = Status(message, count, rejected + outputDrops + playout.missing + playout.schedulerSkipped)
                    }
                }
            } finally {
                playout.discardPending()
                runCatching {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                    track.flush()
                }
                runCatching { track.release() }
            }
        }
    }

    companion object {
        const val PORT = 39771
        fun sender(peer: InetAddress, key: ByteArray): LanTestSession = LanTestSession().apply {
            launch("SurSoundLanSender", key.copyOf()) { send(peer, it) }
        }
        fun receiver(peer: InetAddress, key: ByteArray): LanTestSession = LanTestSession().apply {
            launch("SurSoundLanReceiver", key.copyOf()) { receive(peer, it) }
        }
    }
}

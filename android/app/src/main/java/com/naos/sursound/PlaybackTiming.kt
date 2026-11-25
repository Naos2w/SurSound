package com.naos.sursound

/** One four-timestamp exchange; t1/t4 use the receiver clock, t2/t3 the sender clock. */
internal data class ClockExchange(val t1: Long, val t2: Long, val t3: Long, val t4: Long)

internal data class ClockEstimate(val senderMinusReceiverNanos: Double, val roundTripNanos: Double) {
    val pathUncertaintyNanos: Double get() = roundTripNanos / 2.0
    fun receiverTimeFor(senderTimeNanos: Long): Double =
        senderTimeNanos.toDouble() - senderMinusReceiverNanos
}

/** Bounded minimum-RTT filter. This estimates offset, not output latency or acoustic sync. */
internal class ClockOffsetEstimator(private val capacity: Int = 12) {
    init { require(capacity in 1..64) }
    private val samples = ArrayDeque<ClockEstimate>()
    private var lastReceive: Long? = null

    fun add(exchange: ClockExchange): Boolean {
        val (t1, t2, t3, t4) = exchange
        if (t4 < t1 || t3 < t2 || lastReceive?.let { t4 <= it } == true) return false
        val local = t4.toDouble() - t1.toDouble()
        val remote = t3.toDouble() - t2.toDouble()
        val rtt = local - remote
        if (local > 1_000_000_000.0 || rtt < 0 || rtt > 1_000_000_000.0) return false
        val offset = ((t2.toDouble() - t1.toDouble()) + (t3.toDouble() - t4.toDouble())) / 2
        if (samples.size == capacity) samples.removeFirst()
        samples.addLast(ClockEstimate(offset, rtt))
        lastReceive = t4
        return true
    }

    fun estimate(): ClockEstimate? = samples.toList().asReversed().minByOrNull { it.roundTripNanos }
}

/**
 * Local-clock playout of already authenticated, fixed-size 5 ms PCM blocks.
 * Owns accepted PCM buffers. No unbounded queue, replay or catch-up burst.
 * The first observed sequence is the anchor; earlier blocks are not replayed.
 */
internal class ReceiverPlayoutBuffer(
    private val initialDelayNanos: Long = 40_000_000L,
    private val windowPackets: Int = 64
) {
    init {
        require(initialDelayNanos in 0L..1_000_000_000L)
        require(windowPackets in 1..256)
    }

    data class Block(val sequence: Long, val pcm: ByteArray?)

    private var session: Long? = null
    private var expected = 0L
    private var deadline = 0L
    private val pending = HashMap<Long, ByteArray>()
    val started: Boolean get() = session != null
    val queued: Int get() = pending.size
    var missing = 0L
        private set
    var schedulerSkipped = 0L
        private set
    var maxDispatchLatenessNanos = 0L
        private set

    fun offer(sessionId: Long, sequence: Int, pcm: ByteArray, nowNanos: Long, firstDeadlineNanos: Long? = null): Boolean {
        if (sequence < 0 || sequence == Int.MAX_VALUE || pcm.size != LanPacketCodec.PCM_BYTES) return false
        val index = sequence.toLong()
        if (session == null) {
            session = sessionId
            expected = index
            deadline = firstDeadlineNanos ?: (nowNanos + initialDelayNanos)
        }
        if (session != sessionId || index < expected || index - expected >= windowPackets ||
            pending.containsKey(index)) return false
        pending[index] = pcm
        return true
    }

    /** Return one due block; null PCM means a missing frame must be rendered as silence. */
    fun poll(nowNanos: Long, nextIntervalAdjustmentNanos: Long = 0): Block? {
        require(nextIntervalAdjustmentNanos in -MAX_SLEW_NANOS..MAX_SLEW_NANOS)
        if (!started || expected >= Int.MAX_VALUE || nowNanos - deadline < 0) return null
        maxDispatchLatenessNanos = maxOf(maxDispatchLatenessNanos, nowNanos - deadline)
        val skipped = (nowNanos - deadline) / FRAME_NANOS
        if (skipped > 0) {
            expected += skipped
            deadline += skipped * FRAME_NANOS
            schedulerSkipped += skipped
            val iterator = pending.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key < expected) {
                    entry.value.fill(0)
                    iterator.remove()
                }
            }
        }
        if (expected >= Int.MAX_VALUE) {
            discardPending()
            return null
        }
        val sequence = expected++
        deadline += FRAME_NANOS + nextIntervalAdjustmentNanos
        val pcm = pending.remove(sequence)
        if (pcm == null) missing++
        return Block(sequence, pcm)
    }

    fun discardPending() {
        pending.values.forEach { it.fill(0) }
        pending.clear()
    }

    companion object {
        const val FRAME_NANOS = 5_000_000L
        const val MAX_SLEW_NANOS = 10_000L // at most 2 ms per second of emitted slots
    }
}

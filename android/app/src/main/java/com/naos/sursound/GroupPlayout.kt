package com.naos.sursound

/** Receiver-only shared-clock state. A valid v2 stream never falls back to unsynced playout. */
internal class GroupPlayout(private val wire: SyncWire) {
    private var session: Long? = null
    private var origin = 0L
    private var firstSequence = 0
    private var firstArrival = 0L
    private var lastProbe: Long? = null
    private var outstanding: SyncWire.Clock? = null
    private var attempts = 0
    private var sampleCount = 0
    private var failed = false
    private var ended = false
    private val estimator = ClockOffsetEstimator()
    private val refinement = ClockOffsetEstimator(capacity = 4)
    private var targetOffset = 0.0
    private var appliedOffset = 0.0
    private var lastGoodReply: Long? = null
    var refinementCount = 0
        private set
    private val pending = java.util.TreeMap<Int, ByteArray>()
    private val buffer = ReceiverPlayoutBuffer()
    val started: Boolean get() = session != null
    val ready: Boolean get() = buffer.started
    val missing: Long get() = buffer.missing
    val schedulerSkipped: Long get() = buffer.schedulerSkipped
    val maxDispatchLatenessNanos: Long get() = buffer.maxDispatchLatenessNanos
    val residualOffsetNanos: Double get() = targetOffset - appliedOffset
    fun clockAgeNanos(now: Long): Long? = lastGoodReply?.let { (now - it).coerceAtLeast(0L) }
    var roundTripNanos: Double? = null
        private set

    fun offer(packet: SyncWire.Audio, now: Long): Boolean {
        if (failed || ended || packet.sequence !in 0 until SyncWire.MAX_PACKETS ||
            packet.pcm.size != LanPacketCodec.PCM_BYTES) return false
        if (session == null) {
            session = packet.session
            origin = packet.origin
            firstSequence = packet.sequence
            firstArrival = now
        }
        if (session != packet.session || origin != packet.origin) return false
        if (ready) return buffer.offer(packet.session, packet.sequence, packet.pcm, now)
        if (packet.sequence < firstSequence || packet.sequence - firstSequence >= 64 ||
            pending.containsKey(packet.sequence)) return false
        pending[packet.sequence] = packet.pcm
        return true
    }

    fun nextProbe(now: Long): ByteArray? {
        val id = session ?: return null
        settle(now)
        if (failed || ended || (!ready && attempts >= 12)) return null
        val interval = if (ready) 1_000_000_000L else 25_000_000L
        if (lastProbe?.let { now - it < interval } == true) return null
        if (outstanding?.let { now - it.t1 < 80_000_000L } == true) return null
        val request = SyncWire.Clock(SyncWire.REQUEST, id, SyncWire.newNonce(), now, 0, 0)
        lastProbe = now
        outstanding = request
        if (!ready) attempts++
        return wire.sealClock(request)
    }

    fun acceptReply(reply: SyncWire.Clock, now: Long): Boolean {
        val request = outstanding ?: return false
        if (failed || ended || reply.kind != SyncWire.REPLY || reply.session != session ||
            reply.session != request.session || reply.nonce != request.nonce || reply.t1 != request.t1) return false
        outstanding = null
        val exchange = ClockExchange(reply.t1, reply.t2, reply.t3, now)
        val rtt = (now.toDouble() - reply.t1) - (reply.t3.toDouble() - reply.t2)
        if (now - request.t1 !in 0L..100_000_000L || rtt !in 0.0..100_000_000.0) return false
        if (ready) {
            // Reject high-delay and implausibly large changes rather than abruptly jumping the timeline.
            val observed = ((reply.t2.toDouble() - reply.t1) + (reply.t3.toDouble() - now)) / 2
            if (rtt > 20_000_000.0 || kotlin.math.abs(observed - targetOffset) > 10_000_000.0 ||
                !refinement.add(exchange)) return false
            val estimate = refinement.estimate() ?: return false
            targetOffset = estimate.senderMinusReceiverNanos
            roundTripNanos = estimate.roundTripNanos
            refinementCount++
        } else {
            if (!estimator.add(exchange)) return false
            sampleCount++
        }
        lastGoodReply = now
        settle(now)
        return true
    }

    private fun settle(now: Long) {
        if (!started || ready || failed) return
        if (sampleCount == 0) {
            if (attempts >= 12 && (outstanding == null || outstanding?.let { now - it.t1 >= 80_000_000L } == true)) {
                failed = true
                clear()
            }
            return
        }
        if (sampleCount < 3 && now - firstArrival < 120_000_000L) return
        val estimate = estimator.estimate() ?: return
        val deadline = estimate.receiverTimeFor(origin) + firstSequence.toDouble() * ReceiverPlayoutBuffer.FRAME_NANOS
        val lead = deadline - now.toDouble()
        if (!deadline.isFinite() || lead !in -500_000_000.0..1_000_000_000.0 ||
            deadline <= Long.MIN_VALUE.toDouble() || deadline >= Long.MAX_VALUE.toDouble()) {
            failed = true
            clear()
            return
        }
        val id = session ?: return
        for ((sequence, pcm) in pending) {
            if (!buffer.offer(id, sequence, pcm, now, firstDeadlineNanos = deadline.toLong())) pcm.fill(0)
        }
        pending.clear()
        outstanding = null
        roundTripNanos = estimate.roundTripNanos
        targetOffset = estimate.senderMinusReceiverNanos
        appliedOffset = targetOffset
    }

    fun poll(now: Long): ReceiverPlayoutBuffer.Block? {
        if (ended) return null
        settle(now)
        // No catch-up beyond the bounded 30-second source timeline.
        val step = residualOffsetNanos.coerceIn(-ReceiverPlayoutBuffer.MAX_SLEW_NANOS.toDouble(),
            ReceiverPlayoutBuffer.MAX_SLEW_NANOS.toDouble()).toLong()
        val block = buffer.poll(now, nextIntervalAdjustmentNanos = -step) ?: return null
        if (block.sequence >= SyncWire.MAX_PACKETS) {
            block.pcm?.fill(0)
            buffer.discardPending()
            ended = true
            return null
        }
        appliedOffset += step.toDouble()
        return block
    }

    fun clear() {
        pending.values.forEach { it.fill(0) }
        pending.clear()
        buffer.discardPending()
    }
}

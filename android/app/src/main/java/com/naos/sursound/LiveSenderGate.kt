package com.naos.sursound

/** Pure monotonic-time liveness state. Only an outstanding authenticated ACK opens the gate. */
internal class LiveSenderGate(private val session: Long, now: Long) {
    enum class Phase { WAITING, ACTIVE, CLOSED }

    var phase = Phase.WAITING
        private set
    var generation = 0
        private set
    var recoveries = 0
        private set
    private var wasActive = false
    private var waitingSince = now
    private var generationSince = now
    private var lastAck = now
    private var counter = 0L
    private var lastProbe: Long? = null
    private var outstanding: LiveWire.Control? = null

    fun tick(now: Long) {
        if (phase == Phase.CLOSED) return
        if (phase == Phase.ACTIVE && now - lastAck >= ACK_LOSS_NS) {
            phase = Phase.WAITING
            waitingSince = now
            advance(now)
        } else if (phase == Phase.WAITING) {
            if (now - waitingSince >= RECOVERY_NS) close()
            else if (now - generationSince >= RETRY_GENERATION_NS) advance(now)
        }
    }

    private fun advance(now: Long) {
        if (generation == LiveWire.MAX_GENERATION) { close(); return }
        generation++
        generationSince = now
        outstanding = null
        lastProbe = null
    }

    fun probe(now: Long): LiveWire.Control? {
        tick(now)
        if (phase == Phase.CLOSED || lastProbe?.let { now - it < PROBE_NS } == true) return null
        val c = LiveWire.Control(LiveWire.PROBE, session, generation, ++counter)
        lastProbe = now
        outstanding = c
        return c
    }

    fun acknowledge(c: LiveWire.Control, now: Long): Boolean {
        tick(now)
        val pending = outstanding ?: return false
        val at = lastProbe ?: return false
        if (phase == Phase.CLOSED || now < at || now - at > PROBE_NS ||
            c != pending.copy(kind = LiveWire.ACK)) return false
        outstanding = null
        lastAck = now
        if (phase != Phase.ACTIVE) {
            if (wasActive) recoveries++
            wasActive = true
            phase = Phase.ACTIVE
        }
        return true
    }

    fun stop(): LiveWire.Control {
        close()
        return LiveWire.Control(LiveWire.STOP, session, generation, ++counter)
    }

    private fun close() {
        phase = Phase.CLOSED
        outstanding = null
    }

    companion object {
        const val PROBE_NS = 500_000_000L
        const val ACK_LOSS_NS = 1_500_000_000L
        const val RETRY_GENERATION_NS = 2_000_000_000L
        const val RECOVERY_NS = 10_000_000_000L
    }
}

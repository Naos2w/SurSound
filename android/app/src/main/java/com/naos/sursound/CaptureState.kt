package com.naos.sursound

internal object CaptureState {
    enum class Phase { IDLE, STARTING, CAPTURING, STOPPING, STOPPED, ERROR }

    data class Snapshot(
        val phase: Phase = Phase.IDLE,
        val message: String = "Ready",
        val reading: PcmMeter.Reading = PcmMeter.Reading(),
        val lastReadNanos: Long = 0
    ) {
        val active: Boolean
            get() = phase == Phase.STARTING || phase == Phase.CAPTURING || phase == Phase.STOPPING
    }

    private var generation = 0L
    @Volatile var snapshot = Snapshot()
        private set

    @Synchronized fun begin(): Long {
        generation++
        snapshot = Snapshot(Phase.STARTING, "Starting audio capture")
        return generation
    }

    @Synchronized fun update(owner: Long, value: Snapshot) {
        if (owner == generation) snapshot = value
    }

    @Synchronized fun reportConsentFailure(message: String) {
        if (!snapshot.active) snapshot = Snapshot(Phase.ERROR, message)
    }
}

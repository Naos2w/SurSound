package com.naos.sursound

import org.junit.Assert.*
import org.junit.Test

class CaptureShareStatsTest {
    @Test fun terminalSnapshotPreservesCountersAndNewSessionClearsThem() {
        val owner = CaptureState.begin()
        val stats = CaptureState.ShareStats(true, 2, 120, 4, 6, true)
        CaptureState.update(owner, CaptureState.Snapshot(CaptureState.Phase.CAPTURING, "Sharing", share = stats))
        CaptureState.update(owner, CaptureState.Snapshot(CaptureState.Phase.STOPPED, "Timed out", share = stats))
        assertFalse(CaptureState.snapshot.active)
        assertEquals(stats, CaptureState.snapshot.share)
        assertEquals(120L, CaptureState.snapshot.share?.sent)
        val next = CaptureState.begin()
        assertNull(CaptureState.snapshot.share)
        // A stale sender must never overwrite a new session's metrics.
        CaptureState.update(owner, CaptureState.Snapshot(CaptureState.Phase.ERROR, "Old", share = stats))
        assertEquals(CaptureState.Phase.STARTING, CaptureState.snapshot.phase)
        assertNull(CaptureState.snapshot.share)
        CaptureState.update(next, CaptureState.Snapshot(CaptureState.Phase.STOPPED))
    }
}

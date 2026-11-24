package com.naos.sursound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStateTest {
    @Test fun staleSessionCannotOverwriteNewSession() {
        val first = CaptureState.begin()
        val second = CaptureState.begin()
        CaptureState.update(second, CaptureState.Snapshot(CaptureState.Phase.CAPTURING, "new"))
        CaptureState.update(first, CaptureState.Snapshot(CaptureState.Phase.STOPPED, "old"))
        assertEquals("new", CaptureState.snapshot.message)
        assertTrue(CaptureState.snapshot.active)
        CaptureState.update(second, CaptureState.Snapshot(CaptureState.Phase.STOPPED, "done"))
        assertFalse(CaptureState.snapshot.active)
    }
}

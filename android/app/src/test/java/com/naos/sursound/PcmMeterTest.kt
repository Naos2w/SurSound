package com.naos.sursound

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmMeterTest {
    @Test fun silenceDoesNotImplySignal() {
        assertEquals(PcmMeter.Reading(2, 0, 0), PcmMeter(2).consume(shortArrayOf(0, 0, 0, 0), 4))
    }

    @Test fun handlesSignedMinimumAndPartialReads() {
        val meter = PcmMeter(2)
        assertEquals(PcmMeter.Reading(1, 2, 32768), meter.consume(shortArrayOf(-32768, 100, 0, 900), 3))
        assertEquals(PcmMeter.Reading(2, 3, 2), meter.consume(shortArrayOf(-2, 300), 1))
    }

    @Test fun countsFramesAcrossReadsAndPeakPerBlock() {
        val meter = PcmMeter(2)
        assertEquals(PcmMeter.Reading(0, 1, 400), meter.consume(shortArrayOf(400), 1))
        assertEquals(PcmMeter.Reading(1, 1, 0), meter.consume(shortArrayOf(0), 1))
        assertEquals(PcmMeter.Reading(1, 1, 0), meter.consume(shortArrayOf(999), 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidReadSize() {
        PcmMeter(2).consume(shortArrayOf(1), 2)
    }
}

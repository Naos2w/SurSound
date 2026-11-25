package com.naos.sursound

/** Counts interleaved PCM16 samples without retaining audio. */
internal class PcmMeter(private val channels: Int) {
    init { require(channels > 0) }

    private var samples = 0L
    private var nonZero = 0L

    data class Reading(
        val frames: Long = 0,
        val nonZeroSamples: Long = 0,
        val peak: Int = 0
    )

    fun consume(buffer: ShortArray, count: Int): Reading {
        require(count in 0..buffer.size)
        var peak = 0
        for (i in 0 until count) {
            val magnitude = kotlin.math.abs(buffer[i].toInt())
            if (magnitude != 0) nonZero++
            if (magnitude > peak) peak = magnitude
        }
        samples += count.toLong()
        return Reading(samples / channels, nonZero, peak)
    }
}

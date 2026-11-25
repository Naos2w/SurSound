package com.naos.sursound

/** Transport lifecycle for consented capture; stopping must discard queued PCM. */
internal interface CaptureSender : AutoCloseable {
    val failure: String?
    fun offer(samples: ShortArray, count: Int)
    fun awaitStopped(timeoutMillis: Long = 250)
    fun stats(): CaptureState.ShareStats
}

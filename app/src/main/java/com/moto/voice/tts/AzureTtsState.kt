package com.moto.voice.tts

/**
 * Process-wide record of the last Azure TTS attempt. Consumed by:
 *   - SystemStatus page → to draw the Azure TTS row in the real state
 *   - TtsRouter → to enrich the current DebugEntry with per-utterance timing
 *
 * Fields are @Volatile because writes come from the Azure engine thread and reads
 * come from the UI thread + router thread. No cross-field consistency is required
 * (each field is read individually).
 */
object AzureTtsState {

    enum class LastResult { Never, Ok, Failed }

    @Volatile private var last: LastResult = LastResult.Never
    @Volatile private var lastError: String? = null
    @Volatile private var lastSynthMs: Long = -1L
    @Volatile private var lastPlayMs: Long = -1L
    @Volatile private var lastCacheHit: Boolean = false

    fun recordSuccess() { last = LastResult.Ok; lastError = null }
    fun recordFailure(error: String) { last = LastResult.Failed; lastError = error }

    fun setSynthTiming(synthMs: Long, cacheHit: Boolean) {
        lastSynthMs = synthMs
        lastCacheHit = cacheHit
    }
    fun setPlayTiming(playMs: Long) { lastPlayMs = playMs }

    fun result(): LastResult = last
    fun error(): String? = lastError
    fun synthMs(): Long = lastSynthMs
    fun playMs(): Long = lastPlayMs
    fun cacheHit(): Boolean = lastCacheHit
}

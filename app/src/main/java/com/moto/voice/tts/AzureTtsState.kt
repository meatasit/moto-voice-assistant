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

    /**
     * v1.3.38 — the subscription key is being rejected, so stop asking.
     *
     * Field log 1786688875809 came back with the detail v1.3.37 restored, and every single
     * failure is the same: `IllegalStateException: HTTP 401`. That is not a flaky network,
     * it is a dead key — and it explains the rider's *"AI 2 เสียง"*: lines already in the
     * on-disk cache still play in the Azure voice while every NEW line 401s and falls back
     * to the Android voice. Same ride, two voices, alternating by whether the sentence had
     * been said before.
     *
     * Once we've seen a 401 we route everything to Android for the rest of the process, so
     * the rider hears ONE voice. Cleared by [recordSuccess] — which happens as soon as a
     * working key is saved and the next line synthesises.
     */
    @Volatile private var authRejected: Boolean = false

    fun recordSuccess() { last = LastResult.Ok; lastError = null; authRejected = false }
    fun recordFailure(error: String) { last = LastResult.Failed; lastError = error }

    /** Record a 401 from the synth endpoint. See [authRejected]. */
    fun recordAuthRejected(error: String) {
        last = LastResult.Failed
        lastError = error
        authRejected = true
    }

    /** True while Azure is answering 401 — the router should not keep trying. */
    fun authRejected(): Boolean = authRejected

    /** Test hook + called when the rider saves new Azure credentials. */
    fun clearAuthRejected() { authRejected = false }

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

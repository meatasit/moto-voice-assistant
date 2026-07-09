package com.moto.voice.pipeline

/**
 * Process-wide dedupe for outbound actions — spec v1.3.8 A5.
 *
 * Field evidence pattern: the rider fires a command, the STT interpretation shows
 * up on-screen a beat late, they think it didn't take, and they say it again. Or
 * the helmet BVRA button double-taps. Either way the pipeline previously executed
 * the action twice — a duplicate phone call, two YouTube tabs, or an FM re-buffer.
 *
 * This guard tracks the most-recent (action, payload-hash) pair with a millisecond
 * timestamp. A second call with the same key inside [WINDOW_MS] returns `true`
 * from [isRecentDuplicate]; the pipeline swallows it and speaks the short
 * [com.moto.voice.nlu.ErrorSpeech.ACTION_IN_PROGRESS] instead of re-executing.
 *
 * Keyed by action + payload so "โทรหาแม่" then "โทรหาพ่อ" are NOT deduped —
 * only the exact same request twice back-to-back.
 *
 * Singleton because it must survive pipeline instance boundaries: v1.3.6 wired
 * a fresh [VoiceCommandPipeline] per BVRA press, so per-instance state would
 * miss the duplicate across-presses case (which is the ONE we care about).
 */
object DedupeGuard {

    /**
     * Two commands are considered "the same intent, the rider stuttered" when
     * they arrive this close together. Longer would prevent legitimate follow-ups
     * ("โทรหาแม่" → she doesn't answer → "โทรหาแม่" 5s later would be intentional);
     * shorter wouldn't catch the double-tap pattern that motivated the guard.
     */
    const val WINDOW_MS = 3_000L

    @Volatile private var lastKey: String? = null
    @Volatile private var lastAtMs: Long = 0L

    /**
     * @return true iff [key] matches the previously-marked key AND the previous
     *   mark landed within [WINDOW_MS] ago. Consumes nothing — call [markExecuted]
     *   separately once the action is confirmed to be running.
     */
    fun isRecentDuplicate(key: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val prev = lastKey ?: return false
        if (prev != key) return false
        return (nowMs - lastAtMs) in 0..WINDOW_MS
    }

    /** Record that [key] has just been executed. Called immediately before the action fires. */
    fun markExecuted(key: String, nowMs: Long = System.currentTimeMillis()) {
        lastKey = key
        lastAtMs = nowMs
    }

    /**
     * Build a stable key from an action name and its payload. The pipeline uses the
     * webhook action + primary payload (contact for call, video_id for youtube_play,
     * frequency/stream_url for fm) so identical requests hash the same regardless of
     * insignificant fields (e.g. the alt-videos array).
     */
    fun keyOf(action: String, payload: String?): String {
        val p = payload?.trim().orEmpty()
        return "$action|$p"
    }

    /** Test hook — resets state between tests so they don't leak into each other. */
    internal fun resetForTest() {
        lastKey = null
        lastAtMs = 0L
    }
}

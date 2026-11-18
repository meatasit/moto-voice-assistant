package com.moto.voice.tts

import com.moto.voice.debug.DebugEntry

/**
 * v1.4.12 — the pure half of "a speak that fails must not be silent" (field log
 * 1794968515443: four days of confirmations that returned in ~50 ms and were never heard).
 *
 * Kept free of Android types so `TtsRecoveryTest` can pin it on the JVM; the instrumented
 * half (rebuilding the [android.speech.tts.TextToSpeech] binding) lives in [AndroidTtsEngine].
 */
object TtsRecovery {

    /** A line that "finished" faster than this cannot have been played. */
    const val TOO_FAST_MS = 200L
    /** Below this many characters a line can legitimately be that short. */
    const val TOO_FAST_MIN_CHARS = 6

    /**
     * Rebuild the engine and say the line again? Once per line, and only while it is still
     * the newest line — a superseded one must not surface late on top of its successor.
     */
    fun shouldRebind(alreadyRetried: Boolean, isLatest: Boolean): Boolean =
        !alreadyRetried && isLatest

    /** The engine reported "done" without enough time to have played [text]. */
    fun doneTooFast(text: String, elapsedMs: Long): Boolean =
        text.trim().length >= TOO_FAST_MIN_CHARS && elapsedMs < TOO_FAST_MS

    /** One `ttsError` token: what failed, whether we rebuilt, and how the retry ended. */
    fun describe(reason: String, rebound: Boolean, recovered: Boolean, finalReason: String? = null): String =
        when {
            !rebound -> reason
            recovered -> "$reason→rebind→ok"
            else -> "$reason→rebind→failed(${finalReason ?: "unknown"})"
        }

    /** `TextToSpeech.setLanguage` result → a problem name, or null when Thai is usable. */
    fun languageProblem(setLanguageResult: Int): String? = when (setLanguageResult) {
        -1 -> "thaiVoiceMissingData"   // TextToSpeech.LANG_MISSING_DATA
        -2 -> "thaiNotSupported"       // TextToSpeech.LANG_NOT_SUPPORTED
        else -> null
    }

    /** Append [token] to [entry]'s `ttsError` (semicolon-separated, like mediaOperations). */
    fun stamp(entry: DebugEntry, token: String) {
        val prior = entry.ttsError
        entry.ttsError = if (prior.isNullOrEmpty()) token else "$prior;$token"
    }
}

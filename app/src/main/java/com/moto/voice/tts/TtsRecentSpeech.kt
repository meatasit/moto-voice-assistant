package com.moto.voice.tts

import android.os.SystemClock

/**
 * Process-wide record of the most recent TTS utterance the assistant produced.
 *
 * The per-pipeline `AppMemory.lastSpoken` echo guard we added in the previous
 * round only remembered utterances spoken by the pipeline itself. Field-test bug:
 * FmPlayerService speaks "เปิดสถานีไม่สำเร็จ..." via its own short-lived ThaiTTS,
 * which never touches AppMemory. Any new interaction triggered while the sound was
 * still in the phone speaker heard its own error message and treated it as a
 * user command.
 *
 * This holder is updated from [ThaiTTS] — every speak() writes here — so ALL TTS
 * sources (pipeline, FmPlayerService retry-exhaust, HelmetGreeter, VoiceCommandService
 * preflight, Settings preview) share a single source of truth for "what did the
 * assistant just say".
 */
object TtsRecentSpeech {

    /** How long after the utterance ends we still treat matching STT as echo. */
    const val LINGER_AFTER_END_MS = 1_000L

    @Volatile private var speaking: String? = null
    @Volatile private var lastEndedText: String? = null
    @Volatile private var lastEndedAt: Long = 0L

    /** Called by ThaiTTS just before the router.speak call. */
    fun markSpeaking(text: String) {
        speaking = text
    }

    /** Called from onDone / onError so the linger window can start counting. */
    fun markEnded() {
        val t = speaking
        speaking = null
        if (t != null) {
            lastEndedText = t
            lastEndedAt = SystemClock.elapsedRealtime()
        }
    }

    /**
     * @return the text currently being spoken, OR the most-recent utterance if it
     *   ended less than [LINGER_AFTER_END_MS] ago. null if quiet for longer.
     */
    fun currentOrRecent(): String? {
        val now = speaking
        if (now != null) return now
        val last = lastEndedText ?: return null
        val ago = SystemClock.elapsedRealtime() - lastEndedAt
        return if (ago in 0..LINGER_AFTER_END_MS) last else null
    }

    /** Test hook — resets state between tests so they don't leak into each other. */
    internal fun resetForTest() {
        speaking = null
        lastEndedText = null
        lastEndedAt = 0L
    }
}

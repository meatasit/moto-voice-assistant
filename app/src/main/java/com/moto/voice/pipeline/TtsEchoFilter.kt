package com.moto.voice.pipeline

import com.moto.voice.nlu.ThaiNormalizer
import com.moto.voice.tts.TtsRecentSpeech

/**
 * Guards against the pipeline picking up its own TTS as user speech — real problem
 * on phone-mic mode where the speaker and mic aren't acoustically isolated.
 *
 * Field evidence (round 1): debug log 1783432847869 sttPartial=
 *   "ยังไม่ได้ยินแค่ลองใหม่อีกครั้งนะคะ" ≈ our NOT_HEARD_GIVING_UP line.
 *
 * Field evidence (round 2): sttFinal "เปิดสถานีไม่สำเร็จค่าสถานีอาจมีปัญหาชั่วคราว"
 *   captured during a barge_in_cancel interaction — the user's next-command mic
 *   picked up the FmPlayerService retry-exhaust TTS. That path speaks via its own
 *   short-lived ThaiTTS which never touched AppMemory.lastSpoken, so the previous
 *   per-pipeline echo filter didn't recognise it.
 *
 * Round 2 fix: [isSelfEcho] consults the PROCESS-WIDE [TtsRecentSpeech] singleton,
 * not per-pipeline memory. Every call to ThaiTTS updates that singleton, so echoes
 * from FmPlayerService / HelmetGreeter / VoiceCommandService preflight / Settings
 * preview all get caught by any pipeline that starts within the 1-second linger
 * window.
 */
object TtsEchoFilter {

    /** Anything above this similarity is treated as echo of the last spoken line. */
    const val ECHO_SIMILARITY_THRESHOLD = 0.75f

    /**
     * Global check — pulls the currently-speaking or just-ended utterance from
     * [TtsRecentSpeech]. Use this from every listen-result site.
     */
    fun isSelfEcho(sttResult: String): Boolean {
        if (sttResult.isBlank()) return false
        val recent = TtsRecentSpeech.currentOrRecent() ?: return false
        return similarity(sttResult, recent) >= ECHO_SIMILARITY_THRESHOLD
    }

    /**
     * Legacy per-pipeline check kept so existing call sites don't rewire. Callers
     * that already track a specific prompt still work; the global one catches
     * external TTS sources.
     */
    fun isEcho(sttResult: String, lastTtsText: String?): Boolean {
        if (lastTtsText.isNullOrBlank()) return false
        if (sttResult.isBlank()) return false
        return similarity(sttResult, lastTtsText) >= ECHO_SIMILARITY_THRESHOLD
    }

    /**
     * Spec v1.3.9 §2.2.ข — classification used by the barge-in listener while TTS
     * is still speaking. Returns:
     *   * [BargeInClass.ECHO] if the partial STT result is similar enough to the
     *     text currently being spoken — drop it and keep listening.
     *   * [BargeInClass.REAL_ANSWER] if it's clearly different — the rider spoke
     *     during the question, so we stop the TTS and use this as the answer.
     *   * [BargeInClass.UNKNOWN] if the partial is too short to classify with
     *     confidence (< 2 chars) — keep listening but don't cut TTS.
     *
     * The similarity threshold is intentionally the same as [ECHO_SIMILARITY_THRESHOLD]
     * so a rider whose answer happens to overlap with the prompt phonetically doesn't
     * spuriously trigger barge-in.
     */
    fun classifyDuringTts(sttPartial: String, currentTtsText: String?): BargeInClass {
        val partial = sttPartial.trim()
        if (partial.length < 2) return BargeInClass.UNKNOWN
        if (currentTtsText.isNullOrBlank()) {
            // TTS not tracking — treat as real (no way to know if it's echo).
            return BargeInClass.REAL_ANSWER
        }
        return if (similarity(partial, currentTtsText) >= ECHO_SIMILARITY_THRESHOLD)
            BargeInClass.ECHO
        else
            BargeInClass.REAL_ANSWER
    }

    /** Partial-result classification for the barge-in listener. See [classifyDuringTts]. */
    enum class BargeInClass { ECHO, REAL_ANSWER, UNKNOWN }

    private fun similarity(sttResult: String, lastTtsText: String): Float {
        val a = ThaiNormalizer.normalize(sttResult)
        val b = ThaiNormalizer.normalize(lastTtsText)
        return ThaiNormalizer.similarity(a, b)
    }
}

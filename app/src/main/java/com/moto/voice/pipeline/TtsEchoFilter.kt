package com.moto.voice.pipeline

import com.moto.voice.nlu.ThaiNormalizer

/**
 * Guards against the pipeline picking up its own TTS as user speech — real problem
 * on phone-mic mode where the speaker and mic aren't acoustically isolated (spec §2.4).
 *
 * Field evidence: debug log 1783432847869 shows sttPartial=
 *   "ยังไม่ได้ยินแค่ลองใหม่อีกครั้งนะคะ"
 * which is Google STT hearing the assistant's own NOT_HEARD_GIVING_UP line
 * ("ยังไม่ได้ยินค่ะ ลองใหม่อีกครั้งนะคะ") back through the phone mic. "ค่ะ" got
 * misheard as "แค่" but the rest matches almost verbatim.
 *
 * Detection: normalized similarity ≥ [ECHO_SIMILARITY_THRESHOLD] means we treat the
 * result as echo. Threshold chosen empirically from the observed evidence: the
 * NOT_HEARD_GIVING_UP echo pair scores ~0.90 on ThaiNormalizer.similarity, well
 * above any legitimate user command that would sound like an assistant prompt.
 */
object TtsEchoFilter {

    /** Anything above this similarity is treated as echo of the last spoken line. */
    const val ECHO_SIMILARITY_THRESHOLD = 0.75f

    /**
     * @return true if [sttResult] should be discarded as an echo of [lastTtsText].
     *   Empty inputs, or no last-TTS at all, always return false (nothing to filter).
     */
    fun isEcho(sttResult: String, lastTtsText: String?): Boolean {
        if (lastTtsText.isNullOrBlank()) return false
        if (sttResult.isBlank()) return false
        val a = ThaiNormalizer.normalize(sttResult)
        val b = ThaiNormalizer.normalize(lastTtsText)
        val sim = ThaiNormalizer.similarity(a, b)
        return sim >= ECHO_SIMILARITY_THRESHOLD
    }
}

package com.moto.voice.nlu

/**
 * Small random pre-action opener — spec v1.3.8 B3.
 *
 * The webhook's `speak` field for action-taking replies almost always begins with
 * "กำลัง..." ("opening...", "calling...", "playing..."). Reading the exact same
 * "กำลังเปิด..." on every command makes the assistant sound robotic. This helper
 * sometimes prepends a short warm word so consecutive interactions don't sound
 * identical.
 *
 * Constraints from the rider-on-motorcycle rule:
 *  - Total prepended audio ≤ 1s (the two candidates are 2–3 syllables each).
 *  - Weighted so "no prefix" is the most common outcome (60%). Variety, not chatter.
 *  - Only fires when `speak` starts with "กำลัง" — status/error replies stay verbatim.
 *  - Both variants are persona-aware and pre-synthesized in [ErrorSpeech.allSystemLines]
 *    so the prepend is a cache hit; no extra Azure latency on the hot path.
 *
 * Pure Kotlin: takes a random source so JVM tests can lock the weight distribution.
 */
object RandomOpener {

    /**
     * @return prefix string to prepend to [speak], or "" for the no-prefix case.
     *   Only decides YES/NO based on [speak.startsWith] — passing a non-"กำลัง" reply
     *   always returns "".
     */
    fun pickPrefixFor(speak: String, random: Double = Math.random()): String {
        if (!speak.trimStart().startsWith("กำลัง")) return ""
        return when {
            random < NO_PREFIX_WEIGHT -> ""
            random < NO_PREFIX_WEIGHT + OPENER_DAI_LEUY_WEIGHT -> ErrorSpeech.OPENER_DAI_LEUY
            else -> ErrorSpeech.OPENER_JAT_HAI
        }
    }

    /** Weight of the "no prefix" outcome — must be the majority so we don't chatter. */
    const val NO_PREFIX_WEIGHT = 0.60
    /** Weight of "ได้เลย{ค่ะ|ครับ} " prefix. */
    const val OPENER_DAI_LEUY_WEIGHT = 0.20
    /** Remaining share (~0.20) goes to "จัดให้{ค่ะ|ครับ} " — implied by NO + DAI_LEUY. */
}

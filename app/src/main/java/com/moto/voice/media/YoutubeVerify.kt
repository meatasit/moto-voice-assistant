package com.moto.voice.media

/**
 * v1.3.21 — pure, testable title-based verification for the YouTube nudge.
 *
 * Field log 1784074856214 proved the old mediaId check (`MediaOrchestrator.isCorrectVideo`)
 * was defeated: YouTube's MediaSession does NOT populate `METADATA_KEY_MEDIA_ID` with the
 * deep-link video id, so the check escape-hatched to "true" and confirmed the WRONG video.
 * When the screen was locked, Background-Activity-Launch blocked the switch — YouTube kept
 * playing the PREVIOUS video — yet every entry logged `nudge→confirmed` / `finishReason=ok`.
 *
 * YouTube DOES expose `METADATA_KEY_TITLE` reliably, so we verify by title. The key insight:
 * compare against the title that was playing BEFORE we fired the intent. If the title never
 * changes away from that prior one (and isn't what we asked for), the switch never happened —
 * that's the locked/BAL case, and we must speak the honest error instead of resuming the old
 * video.
 *
 * This object holds only string logic so it runs in pure-JVM tests with no Android deps.
 */
internal object YoutubeVerify {

    /** A prefix shorter than this can never stand in for a full title. */
    const val MIN_PREFIX = 6

    /**
     * A truncated title must still cover at least this fraction of the full one. Field log
     * 1784078976959 (entry ts …843956): "…เรื่องเล่าเช้านี้ วันที่ 9…" vs "…วันที่ 15…" share
     * a huge boilerplate prefix but are DIFFERENT episodes — a bare "long shared prefix" rule
     * confirmed the wrong video. Requiring the prefix to cover most of the longer string, and
     * matching only a true leading prefix (not a mid-string divergence), rejects them.
     */
    const val MIN_PREFIX_RATIO = 0.7

    fun normalize(s: String?): String = s?.trim()?.lowercase() ?: ""

    /**
     * True when two titles plausibly refer to the SAME video. Two ways to match:
     *   * exact (after normalize), or
     *   * one is a leading prefix of the other AND covers ≥ [MIN_PREFIX_RATIO] of it —
     *     this tolerates webhook titles truncated at the end, without letting two episodes
     *     of the same series (which diverge at a date/number, not the length) match.
     * Blank vs anything is never a match.
     */
    fun titlesMatch(a: String?, b: String?): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return false
        if (na == nb) return true
        val shorter = if (na.length <= nb.length) na else nb
        val longer = if (na.length <= nb.length) nb else na
        return shorter.length >= MIN_PREFIX &&
            longer.startsWith(shorter) &&
            shorter.length >= longer.length * MIN_PREFIX_RATIO
    }

    enum class Verdict {
        /** Session is playing the exact title we asked for. */
        CONFIRMED_TARGET,
        /** Title changed away from the prior one — a switch happened (new video, target unverifiable). */
        SWITCHED,
        /** Session still shows the prior title and it's not the target — no switch (likely BAL-blocked). */
        STILL_PRIOR,
        /** Session has no title yet — undecided, caller should keep polling. */
        UNKNOWN,
    }

    /**
     * @param currentTitle  title the session reports right now
     * @param priorTitle    title playing just before we fired the intent (null/blank = none)
     * @param expectedTitle title we asked to open (null/blank when unknown, e.g. "อันต่อไป")
     */
    fun classify(currentTitle: String?, priorTitle: String?, expectedTitle: String?): Verdict {
        if (normalize(expectedTitle).isNotEmpty() && titlesMatch(currentTitle, expectedTitle)) {
            return Verdict.CONFIRMED_TARGET
        }
        if (normalize(currentTitle).isEmpty()) return Verdict.UNKNOWN
        if (titlesMatch(currentTitle, priorTitle)) return Verdict.STILL_PRIOR
        return Verdict.SWITCHED
    }
}

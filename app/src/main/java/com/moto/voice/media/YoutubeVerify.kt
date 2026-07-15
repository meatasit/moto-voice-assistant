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

    /** Minimum shared-substring length before a containment match counts. */
    const val MIN_SUBSTRING = 6

    /** Minimum shared-prefix length before a prefix match counts (handles truncated titles). */
    const val MIN_PREFIX = 12

    fun normalize(s: String?): String = s?.trim()?.lowercase() ?: ""

    /**
     * True when two titles plausibly refer to the same video. Handles exact equality,
     * one being a substring of the other (webhook titles are sometimes truncated), and a
     * long shared prefix (truncation mid-string). Blank vs anything is never a match.
     */
    fun titlesMatch(a: String?, b: String?): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return false
        if (na == nb) return true
        val shorter = if (na.length <= nb.length) na else nb
        val longer = if (na.length <= nb.length) nb else na
        if (shorter.length >= MIN_SUBSTRING && longer.contains(shorter)) return true
        return na.commonPrefixWith(nb).length >= MIN_PREFIX
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

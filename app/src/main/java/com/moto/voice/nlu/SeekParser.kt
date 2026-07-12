package com.moto.voice.nlu

/**
 * v1.3.11 §2.3 — parse a Thai seek command into a signed delta in seconds.
 *
 * Forward:  `(เลื่อน|ข้าม|skip|กรอ) (ไป)? (ข้างหน้า|หน้า)? N (วิ|วินาที|นาที)?`
 * Backward: `(ถอย|ย้อน) (กลับ|หลัง)? N (วิ|วินาที|นาที)?`
 * Default:  N omitted → 10 seconds. Unit omitted → seconds. นาที → multiplied by 60.
 *
 * Runs as a [LocalIntercept] pattern so the seek command works offline (no
 * webhook round-trip required for something the pipeline already knows how to
 * dispatch via [android.media.session.MediaController.getTransportControls]
 * or KEYCODE_MEDIA_FAST_FORWARD).
 */
object SeekParser {

    /** Positive delta = seek forward N seconds; negative = seek backward. */
    data class SeekIntent(val deltaSeconds: Int)

    /**
     * @return a [SeekIntent] if [text] matches either the forward or backward
     *   pattern, else null. Whitespace-normalised + lowercased before matching so
     *   "เลื่อน  30วิ" and "เลื่อน 30 วิ" both work.
     */
    fun parse(text: String): SeekIntent? {
        val t = text.trim().replace(Regex("\\s+"), " ").lowercase()
        if (t.isEmpty()) return null

        BACKWARD_REGEX.find(t)?.let { m ->
            // Match backward FIRST so verbs like "ย้อนกลับ" don't get caught by
            // a broader forward regex if we ever add a "กลับไป" prefix there.
            return SeekIntent(-extractSeconds(m))
        }
        FORWARD_REGEX.find(t)?.let { m ->
            return SeekIntent(+extractSeconds(m))
        }
        // v1.3.15 STT-mishearing alias (STRICTER — requires directional or
        // number+unit context, so "เดือน กรกฎาคม" doesn't false-positive).
        FORWARD_MISHEARING_REGEX.find(t)?.let { m ->
            return SeekIntent(+extractMishearingSeconds(m))
        }
        return null
    }

    /**
     * Group 1 = number (optional), Group 2 = unit (optional). Absent number →
     * [DEFAULT_SECONDS]; unit == นาที → multiply by 60.
     */
    private fun extractSeconds(m: MatchResult): Int {
        val nStr = m.groupValues.getOrNull(1).orEmpty().trim()
        val n = nStr.toIntOrNull() ?: DEFAULT_SECONDS
        val unit = m.groupValues.getOrNull(2).orEmpty().trim()
        return if (unit == "นาที") n * 60 else n
    }

    /**
     * The mishearing regex has 4 capture groups because of its alternation:
     *   Path 1 (directional): (\d+)? (unit)?  → groups 1 + 2
     *   Path 2 (number-only): (\d+)  (unit)   → groups 3 + 4
     * Take whichever pair actually matched (non-blank).
     */
    private fun extractMishearingSeconds(m: MatchResult): Int {
        val n1 = m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
        val u1 = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
        val n2 = m.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }
        val u2 = m.groupValues.getOrNull(4)?.takeIf { it.isNotBlank() }
        val nStr = n1 ?: n2
        val unit = u1 ?: u2
        val n = nStr?.toIntOrNull() ?: DEFAULT_SECONDS
        return if (unit == "นาที") n * 60 else n
    }

    /**
     * Spec default when the rider says just "เลื่อนหน้า" without a number:
     * 10 seconds is short enough to feel like a precise skip and long enough to
     * matter — matches YouTube's own default double-tap-to-skip range.
     */
    const val DEFAULT_SECONDS = 10

    // Group ordering: (\d+)? then (วิ|วินาที|นาที)?
    //
    // Loose forward verb — original spec: any of these on its own maps to forward,
    // no additional context required (a rider saying "เลื่อน" alone still means
    // forward the default N seconds).
    private val FORWARD_REGEX = Regex(
        "(?:เลื่อน|ข้าม|skip|กรอ)\\s*(?:ไป)?\\s*(?:ข้างหน้า|หน้า)?\\s*(\\d+)?\\s*(วินาที|วิ|นาที)?"
    )

    /**
     * v1.3.15 — "เดือน" as an STT-mishearing alias for "เลื่อน". Field log
     * 1783876501024 showed Google STT capturing "เดือนหน้า 3 นาที" when the rider
     * said "เลื่อนหน้า 3 นาที" (พ.เลื่อน/พ.เดือน is a common phonetic confusion).
     * Pre-v1.3.15 that fell through to the webhook, which asked the LLM to make
     * sense of "เดือนหน้า 3 นาที" (literally "next month 3 minutes") and it
     * guessed backward — user got a reverse seek. Catching this locally means the
     * right forward seek fires from the intercept, no webhook round-trip.
     *
     * STRICTER than the main forward pattern: "เดือน" REQUIRES a directional word
     * (หน้า / ข้างหน้า) OR a number + unit right after, so bare "เดือน กรกฎาคม"
     * or other legitimate uses of the word month don't false-positive into a seek.
     */
    private val FORWARD_MISHEARING_REGEX = Regex(
        "เดือน\\s*(?:" +
            "(?:ไป)?\\s*(?:ข้างหน้า|หน้า)\\s*(\\d+)?\\s*(วินาที|วิ|นาที)?" +  // "เดือนหน้า", "เดือนไปหน้า", w/ optional number
            "|(\\d+)\\s*(วินาที|วิ|นาที)" +                                    // "เดือน 30 วิ" (no direction, number+unit required)
        ")"
    )

    private val BACKWARD_REGEX = Regex(
        "(?:ถอย|ย้อน)\\s*(?:กลับ|หลัง)?\\s*(\\d+)?\\s*(วินาที|วิ|นาที)?"
    )
}

package com.moto.voice.nlu

/**
 * v1.3.11 §2.3 — parse a Thai seek command into a signed delta in seconds.
 *
 * Forward:  `(เลื่อน|ข้าม|skip|กรอ) (ไป)? (ข้างหน้า|หน้า)? [filler] N (วิ|วินาที|นาที)?`
 *           or a bare directional `(ไป)? ข้างหน้า [filler] N (วิ|วินาที|นาที)` (no verb)
 * Backward: `(ถอย|ย้อน) (กลับ|หลัง)? [filler] N (วิ|วินาที|นาที)?`
 * Default:  N omitted → 10 seconds. Unit omitted → seconds. นาที → multiplied by 60.
 *
 * v1.3.28 — field log 1784256366258 exposed three direction/amount bugs (all fired
 * from the local intercept, never the webhook):
 *   1. "…ไปข้างหน้า**ไม่ใช่ถอยหลัง** 3 นาที" → −180. Backward was matched FIRST with an
 *      un-anchored regex, so the "ถอย" inside a NEGATION ("ไม่ใช่ถอย") forced a reverse
 *      seek even though the rider explicitly said forward. Fixed by stripping negated
 *      backward phrases before matching.
 *   2. "…ไปข้างหน้า 3 นาที**ไม่ใช่ถอย**" → −10. Same negation trap, no number after "ถอย".
 *   3. "เลื่อนไปข้างหน้า**อีก** 5 นาที" → 10. A filler word ("อีก") between the direction
 *      and the number broke number capture, dropping to the default. Fixed by allowing
 *      filler words (อีก / สัก / ประมาณ) before the number.
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
     * @return a [SeekIntent] if [text] matches a forward or backward pattern, else
     *   null. Whitespace-normalised + lowercased before matching, and negated
     *   backward phrases ("ไม่ใช่ถอยหลัง") are stripped so they can't force a reverse.
     */
    fun parse(text: String): SeekIntent? {
        val raw = text.trim().replace(Regex("\\s+"), " ").lowercase()
        if (raw.isEmpty()) return null
        // Remove negated backward phrases FIRST so a "ถอย/ย้อน" that the rider explicitly
        // negated ("ไม่ใช่ถอยหลัง", "ไม่ถอย") can't be caught by BACKWARD_REGEX below.
        val t = raw.replace(NEGATED_BACKWARD_REGEX, " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null

        BACKWARD_REGEX.find(t)?.let { m ->
            // Match backward FIRST so verbs like "ย้อนกลับ" don't get caught by
            // a broader forward regex if we ever add a "กลับไป" prefix there.
            return SeekIntent(-extractSeconds(m))
        }
        FORWARD_REGEX.find(t)?.let { m ->
            return SeekIntent(+extractSeconds(m))
        }
        // Bare directional forward — "…ไปข้างหน้า 3 นาที" with no seek verb. STRICT:
        // requires a number + unit so ordinary "ข้างหน้า" talk doesn't false-positive.
        FORWARD_DIRECTIONAL_REGEX.find(t)?.let { m ->
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
     * [DEFAULT_SECONDS]; unit == นาที → multiply by 60. The filler group is
     * non-capturing so these indices are stable across all patterns.
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

    /**
     * v1.3.28 — filler words the rider drops between the direction and the number
     * ("ไปข้างหน้า**อีก** 5 นาที", "เลื่อน**สัก** 30 วิ"). Non-capturing + repeatable so
     * they're skipped without shifting the number/unit capture-group indices.
     */
    private const val FILLER = "(?:(?:อีก|สัก|ประมาณ)\\s*)*"

    /**
     * v1.3.28 — negated backward phrase, stripped before matching. Covers "ไม่ถอย",
     * "ไม่ใช่ถอย", "ไม่ใช่ถอยหลัง", "ไม่ย้อนกลับ" (the "ไม่ … ถอย/ย้อน" the rider uses to
     * CORRECT a wrong reverse). Kept tight (adjacent) so it doesn't swallow real backward
     * commands.
     */
    private val NEGATED_BACKWARD_REGEX = Regex("ไม่\\s*(?:ใช่)?\\s*(?:ถอย|ย้อน)(?:\\s*(?:กลับ|หลัง))?")

    // Group ordering: (\d+)? then (วิ|วินาที|นาที)?
    //
    // Loose forward verb — any of these on its own maps to forward, no additional
    // context required (a rider saying "เลื่อน" alone still means forward the default N).
    private val FORWARD_REGEX = Regex(
        "(?:เลื่อน|ข้าม|skip|กรอ)\\s*(?:ไป)?\\s*(?:ข้างหน้า|หน้า)?\\s*$FILLER(\\d+)?\\s*(วินาที|วิ|นาที)?"
    )

    /**
     * v1.3.28 — bare directional forward with NO seek verb, e.g. "วีดีโอไปข้างหน้า 3 นาที".
     * STRICT: a number AND unit are required so ordinary speech mentioning "ข้างหน้า"
     * (e.g. "รถข้างหน้า") can't false-positive into a seek.
     */
    private val FORWARD_DIRECTIONAL_REGEX = Regex(
        "(?:ไป)?\\s*ข้างหน้า\\s*$FILLER(\\d+)\\s*(วินาที|วิ|นาที)"
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
            "(?:ไป)?\\s*(?:ข้างหน้า|หน้า)\\s*$FILLER(\\d+)?\\s*(วินาที|วิ|นาที)?" +  // "เดือนหน้า", "เดือนไปหน้า", w/ optional number
            "|$FILLER(\\d+)\\s*(วินาที|วิ|นาที)" +                                    // "เดือน 30 วิ" (no direction, number+unit required)
        ")"
    )

    private val BACKWARD_REGEX = Regex(
        "(?:ถอย|ย้อน)\\s*(?:กลับ|หลัง)?\\s*$FILLER(\\d+)?\\s*(วินาที|วิ|นาที)?"
    )
}

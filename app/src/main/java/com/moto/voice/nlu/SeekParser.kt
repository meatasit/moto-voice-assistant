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
     * Spec default when the rider says just "เลื่อนหน้า" without a number:
     * 10 seconds is short enough to feel like a precise skip and long enough to
     * matter — matches YouTube's own default double-tap-to-skip range.
     */
    const val DEFAULT_SECONDS = 10

    // Group ordering: (\d+)? then (วิ|วินาที|นาที)?
    private val FORWARD_REGEX = Regex(
        "(?:เลื่อน|ข้าม|skip|กรอ)\\s*(?:ไป)?\\s*(?:ข้างหน้า|หน้า)?\\s*(\\d+)?\\s*(วินาที|วิ|นาที)?"
    )
    private val BACKWARD_REGEX = Regex(
        "(?:ถอย|ย้อน)\\s*(?:กลับ|หลัง)?\\s*(\\d+)?\\s*(วินาที|วิ|นาที)?"
    )
}

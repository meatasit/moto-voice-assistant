package com.moto.voice.nlu

/**
 * Bare-opener detection + follow-up sentence synthesis for spec v1.3.6 §2.
 *
 * Riders on the highway routinely say the first word of a command, take a breath,
 * then say the payload. If the recognizer cuts them off during that breath — which
 * happens whenever the vendor STT engine ignores our
 * `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` hint (see AppSettings comment) —
 * we end up with a "โทรหา" / "เปิด youtube" / "เปิดวิทยุ" final result and NO payload.
 * Pre-v1.3.6 this went straight to the webhook which returned action=none. Now the
 * pipeline asks one focused follow-up, combines the answer into a full sentence, and
 * feeds it back through the SAME entry point that a one-shot command would have used
 * (per spec §3 — no separate listen path).
 *
 * Pure Kotlin: no Context, no coroutines, testable via JVM unit test.
 */
object SlotFiller {

    /** What the rider left off. [Need.None] = the STT text is already actionable. */
    sealed class Need {
        object None : Need()
        object CallTarget : Need()      // "โทร" / "โทรหา" / "โทรออก" — ask for the name
        object YoutubeQuery : Need()    // "เปิดยูทูป" / "เปิด youtube" — ask for the video
        object RadioStation : Need()    // "เปิดวิทยุ" / "เปิดคลื่น" — ask for the station name/frequency
    }

    /**
     * @return which slot the rider left blank, or [Need.None] when the sentence
     *   already has a payload (or isn't a recognisable opener at all).
     *
     * Anchored to the whole string on both ends — a sentence that HAS a payload
     * ("โทรหาแม่") is longer than the opener and won't match. That's the entire
     * mechanism keeping this from stealing normal commands.
     */
    fun detect(normalized: String): Need {
        val t = normalized.trim()
        return when {
            BARE_CALL_REGEX.matches(t) -> Need.CallTarget
            BARE_YOUTUBE_REGEX.matches(t) -> Need.YoutubeQuery
            BARE_RADIO_REGEX.matches(t) -> Need.RadioStation
            else -> Need.None
        }
    }

    /** The one-line question the pipeline speaks and listens to a reply to. */
    fun promptFor(need: Need): String = when (need) {
        Need.CallTarget -> "โทรหาใครคะ"
        Need.YoutubeQuery -> "เปิดอะไรดีคะ"
        Need.RadioStation -> "คลื่นอะไรคะ"
        Need.None -> ""
    }

    /**
     * Combine the opener + follow-up answer into the full sentence the pipeline
     * would have received if the rider had said it in one breath. Spec §2.6
     * example: `combine(YoutubeQuery, "กรรมกรข่าว") == "เปิด youtube กรรมกรข่าว"`.
     */
    fun combine(need: Need, answer: String): String {
        val a = answer.trim()
        return when (need) {
            Need.CallTarget -> "โทรหา$a"
            Need.YoutubeQuery -> "เปิด youtube $a"
            Need.RadioStation -> "เปิดวิทยุ $a"
            Need.None -> a
        }
    }

    /**
     * Whether [answer] is the rider bailing out of the follow-up.
     *
     * v1.3.6 used `answer.contains("ยกเลิก")` which matched too broadly — sentences
     * like "อย่ายกเลิกโทรหาแม่" or "โทรยกเลิกด่วน" got treated as cancellation and the
     * rider's real command was thrown away. This tightens the check to:
     *
     *   - whole-answer (normalised trim + lowercase) equals one of
     *     {"ยกเลิก", "ไม่เอา", "ไม่"}, OR
     *   - answer STARTS with one of those tokens AND total length ≤ 12 chars
     *     (catches "ยกเลิกครับ" / "ยกเลิกค่ะ" / "ไม่เอาแล้ว" — the common
     *     polite-particle suffixes — without swallowing longer sentences that
     *     happen to start with "ไม่", e.g. "ไม่เอาอะไรเลย" = 13 chars).
     *
     * The 12-char guard was chosen empirically after the CI showed "ยกเลิกครับ" and
     * "ไม่เอาแล้ว" are both exactly 10 UTF-16 code units — a stricter "< 10" boundary
     * from the initial spec would have missed both, since Thai vowel/tone marks are
     * separate code units. 12 is 2 above the natural length and still 1 below the
     * shortest known false-positive.
     *
     * Blank answers are handled by the caller as a separate case (they mean "no
     * reply at all", not "user said cancel"), so [isCancelAnswer] returns false
     * for blank input.
     */
    fun isCancelAnswer(answer: String): Boolean {
        val t = answer.trim().lowercase()
        if (t.isEmpty()) return false
        if (t in CANCEL_TOKENS) return true
        if (t.length <= CANCEL_STARTS_WITH_MAX_LEN && CANCEL_TOKENS.any { t.startsWith(it) }) return true
        return false
    }

    private val CANCEL_TOKENS = setOf("ยกเลิก", "ไม่เอา", "ไม่")
    private const val CANCEL_STARTS_WITH_MAX_LEN = 12

    // Match ONLY the bare openers — any additional token means the rider gave us a
    // payload already, and we shouldn't intercept. Whitespace inside is normalised
    // by LocalIntercept.normalize (which lowercases + collapses whitespace).
    private val BARE_CALL_REGEX = Regex(
        "^โทร(?:ออก)?(?:ไป)?(?:หา)?$"
    )
    // "youtube" comes lowercased through normalize; "ยูทูป" / "ยูทูบ" are the two Thai
    // transliterations the recognizer tends to produce.
    private val BARE_YOUTUBE_REGEX = Regex(
        "^(?:เปิด|ค้นหา)\\s?(?:ยูทูป|ยูทูบ|youtube)$"
    )
    private val BARE_RADIO_REGEX = Regex(
        "^เปิด\\s?(?:วิทยุ|คลื่น)$"
    )
}

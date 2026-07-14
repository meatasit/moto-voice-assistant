package com.moto.voice.nlu

/**
 * Fast, offline pattern check that runs BEFORE the webhook call.
 * Catches the handful of commands the user expects to be instant (<1s round-trip):
 * stop-audio, help, repeat, resume-last-radio, call-back-last-number.
 *
 * The pipeline treats [Intercept.None] as "no match — proceed to webhook".
 */
object LocalIntercept {

    sealed class Intercept {
        object None : Intercept()

        /** Halt current playback. If no local playback, the pipeline sends KEYCODE_MEDIA_PAUSE. */
        object Stop : Intercept()

        /** Resume last-played radio station. If never played, ask user for a frequency. */
        object ResumeLastRadio : Intercept()

        /** Call the last number the app dialled (goes through confirm flow). */
        object CallBackLast : Intercept()

        /** Read the built-in help list of what the assistant can do. */
        object Help : Intercept()

        /** Repeat the last TTS utterance. */
        object RepeatLast : Intercept()

        /** Call favorite by slot number (1-5). Zero-based [zeroBasedSlot] for lookup. */
        data class CallFavorite(val zeroBasedSlot: Int) : Intercept()

        /**
         * Spec v1.3.8 B5 — "อันต่อไป" / "เปลี่ยน" / "อันอื่น" / "ไม่เอาอันนี้". The
         * pipeline consults [com.moto.voice.media.MediaSessionMemory] to find the
         * next video in the current webhook's videos array and re-uses openYoutube.
         */
        object NextVideo : Intercept()

        /**
         * Spec v1.3.8 B5 — "เมื่อกี้อะไร" / "เล่นอะไรอยู่". Answered from
         * [com.moto.voice.media.MediaSessionMemory.currentTitle].
         */
        object WhatIsPlaying : Intercept()

        /**
         * Spec v1.3.11 §2.3 — "เลื่อน 30 วิ" / "ย้อน 1 นาที" / "skip". The
         * pipeline dispatches [deltaSeconds] through the active MediaController
         * (v1.3.11 §1 permission) or falls back to KEYCODE_MEDIA_FAST_FORWARD /
         * KEYCODE_MEDIA_REWIND. Positive = forward, negative = backward.
         */
        data class Seek(val deltaSeconds: Int) : Intercept()

        /**
         * v1.3.20 sprint rule #2 — "เล่นต่อ" / "เล่น YouTube ต่อ" / "เล่น Spotify ต่อ".
         * Caught locally BEFORE the webhook so the target-package decision is made by
         * our code (which knows what WE opened), not by the LLM (which can't tell which
         * app is active). appHint is the raw app name the rider said, or null if they
         * said "เล่นต่อ" alone — in which case MediaOrchestrator falls back to
         * MediaSessionMemory.lastOpenedApp.
         */
        data class PlayContinue(val appHint: String?) : Intercept()
    }

    fun match(text: String): Intercept {
        val t = normalize(text)
        if (t.isEmpty()) return Intercept.None

        // Favorites regex tried first — it's a specific two-token pattern that
        // shouldn't collide with anything below.
        favoriteSlotOrNull(t)?.let { return Intercept.CallFavorite(it) }

        // Exact resume-radio wins over Stop's substring "ปิดวิทยุ" collision with "เปิดวิทยุ".
        if (RESUME_RADIO_PATTERNS.any { it == t }) return Intercept.ResumeLastRadio
        if (matchesAsPhrase(t, STOP_PATTERNS)) return Intercept.Stop
        if (matchesAsPhrase(t, HELP_PATTERNS)) return Intercept.Help
        if (matchesAsPhrase(t, REPEAT_PATTERNS)) return Intercept.RepeatLast
        if (matchesAsPhrase(t, CALL_BACK_PATTERNS)) return Intercept.CallBackLast

        // v1.3.8 B5 — media-context intercepts. Order matters vs Stop: "เปลี่ยน" is
        // an odd match candidate for STOP-adjacent patterns, so we check the media
        // ones AFTER Stop to keep "หยุด/เปลี่ยน" resolution unambiguous even if a
        // rider says both in the same sentence.
        if (matchesAsPhrase(t, NEXT_VIDEO_PATTERNS)) return Intercept.NextVideo
        if (matchesAsPhrase(t, WHAT_IS_PLAYING_PATTERNS)) return Intercept.WhatIsPlaying

        // v1.3.20 sprint — "เล่นต่อ" / "เล่น youtube ต่อ" / "กดเล่นต่อ" — caught
        // locally so MediaOrchestrator picks the target from MediaSessionMemory
        // instead of the LLM guessing which app should resume.
        if (PLAY_CONTINUE_REGEX.containsMatchIn(t)) {
            val hint = when {
                YOUTUBE_HINT_REGEX.containsMatchIn(t) -> "youtube"
                SPOTIFY_HINT_REGEX.containsMatchIn(t) -> "spotify"
                else -> null
            }
            return Intercept.PlayContinue(hint)
        }

        // v1.3.11 §2.3 — seek intercept. Runs LAST so any of the earlier command
        // families win first (e.g. "หยุดเลื่อน" is Stop, not Seek).
        SeekParser.parse(t)?.let { return Intercept.Seek(it.deltaSeconds) }

        return Intercept.None
    }

    /**
     * @return zero-based slot (0..4) if [normalizedText] matches the favorite-call
     *   pattern, else null. Handles Thai + Arabic numerals and both English/Thai
     *   spellings of "favorite" per spec §2.1.
     */
    internal fun favoriteSlotOrNull(normalizedText: String): Int? {
        val m = FAVORITE_CALL_REGEX.find(normalizedText) ?: return null
        val number = m.groupValues.getOrNull(2)?.trim() ?: return null
        return NUMBER_TO_SLOT[number]?.let { it - 1 }
    }

    /**
     * Widened after field log 1783581952116 (v1.3.5) — both
     *   "โทรหารายการโปรดที่ 1"      (with ที่ separator)
     *   "โทรออกหารายการโปรดที่ 1"   (with the ออก verb prefix)
     * fell through to the webhook because the pre-v1.3.6 pattern only allowed
     * "โทร(หา)?" + favorite-word + digit — no ออก/ไป prefix and no separator words.
     *
     * Shape now:
     *   verb   = โทร (ออก)? (ไป)? (หา)?              ← "โทร" / "โทรหา" / "โทรออกหา" / "โทรไปหา"
     *   sep    = (?:ที่|หมายเลข|เบอร์|อันดับ|ลำดับ)?  ← optional connective the rider might insert
     *   number = one of Thai หนึ่ง..ห้า or Arabic 1..5
     */
    private val FAVORITE_CALL_REGEX = Regex(
        "โทร(?:ออก)?(?:ไป)?(?:หา)?\\s*(รายการโปรด|เบอร์โปรด|favorite|เฟเวอริท)\\s*(?:ที่|หมายเลข|เบอร์|อันดับ|ลำดับ)?\\s*(หนึ่ง|สอง|สาม|สี่|ห้า|1|2|3|4|5)"
    )

    private val NUMBER_TO_SLOT = mapOf(
        "หนึ่ง" to 1, "1" to 1,
        "สอง" to 2, "2" to 2,
        "สาม" to 3, "3" to 3,
        "สี่" to 4, "4" to 4,
        "ห้า" to 5, "5" to 5,
    )

    /** Collapse whitespace and lowercase; keeps Thai intact. */
    internal fun normalize(text: String): String =
        text.trim().replace(Regex("\\s+"), " ").lowercase()

    /**
     * Substring match, but the match must start at position 0 or immediately after a space.
     * Prevents "ปิดวิทยุ" (stop-radio) from firing inside "เปิดวิทยุ" (open-radio).
     */
    private fun matchesAsPhrase(t: String, patterns: List<String>) = patterns.any { p ->
        val idx = t.indexOf(p)
        idx >= 0 && (idx == 0 || t[idx - 1] == ' ')
    }

    // Substring matches — user may have padding like "เอ่อ หยุด".
    private val STOP_PATTERNS = listOf(
        "หยุด", "พอแล้ว", "ปิดเพลง", "ปิดวิทยุ", "เงียบ", "หยุดพูด", "หยุดเล่น"
    )
    private val HELP_PATTERNS = listOf(
        "ทำอะไรได้บ้าง", "ช่วยเหลือ", "สอนหน่อย", "ใช้ยังไง", "คำสั่งมีอะไร"
    )
    private val REPEAT_PATTERNS = listOf(
        "พูดอีกที", "ว่าไงนะ", "ทวนอีกที", "พูดใหม่"
    )
    private val CALL_BACK_PATTERNS = listOf(
        "โทรกลับ", "โทรเบอร์ล่าสุด", "โทรคนล่าสุด", "โทรอีกครั้ง"
    )
    // Exact match only — anything longer goes to the webhook where an LLM picks the station.
    private val RESUME_RADIO_PATTERNS = listOf(
        "เปิดวิทยุ", "เปิด วิทยุ", "เล่นวิทยุ", "เอาวิทยุ"
    )
    /** Spec v1.3.8 B5 — advance to next video in the current webhook's `videos` array. */
    private val NEXT_VIDEO_PATTERNS = listOf(
        "อันต่อไป", "เปลี่ยน", "อันอื่น", "ไม่เอาอันนี้", "ต่อไป", "ถัดไป"
    )
    /** Spec v1.3.8 B5 — read back what's currently playing (from MediaSessionMemory). */
    private val WHAT_IS_PLAYING_PATTERNS = listOf(
        "เมื่อกี้อะไร", "เล่นอะไรอยู่", "อะไรอยู่", "นี่เพลงอะไร"
    )

    /**
     * v1.3.20 sprint rule #2 — "เล่นต่อ" and its variants. Requires เล่น|เปิด
     * followed by (optional app name) then ต่อ so it does NOT collide with
     * "อันต่อไป" (next-video) or bare "ต่อไป" — the latter has no เล่น/เปิด.
     */
    private val PLAY_CONTINUE_REGEX = Regex(
        "(?:กด)?(?:เล่น|เปิด)\\s*(?:youtube|ยูทูป|ยูทูบ|spotify|สปอติฟาย)?\\s*ต่อ"
    )
    private val YOUTUBE_HINT_REGEX = Regex("(youtube|ยูทูป|ยูทูบ)")
    private val SPOTIFY_HINT_REGEX = Regex("(spotify|สปอติฟาย)")

    /** Short help text spoken by TTS on Help intercept. */
    const val HELP_TEXT =
        "พูดว่า โทรหา ตามด้วยชื่อ, เปิด YouTube ตามด้วยชื่อเพลง, เปิดวิทยุ ตามด้วยคลื่น, หรือหยุด เพื่อปิดเสียง"
}

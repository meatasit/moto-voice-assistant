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
    }

    fun match(text: String): Intercept {
        val t = normalize(text)
        if (t.isEmpty()) return Intercept.None

        // Exact resume-radio wins over Stop's substring "ปิดวิทยุ" collision with "เปิดวิทยุ".
        if (RESUME_RADIO_PATTERNS.any { it == t }) return Intercept.ResumeLastRadio
        if (matchesAsPhrase(t, STOP_PATTERNS)) return Intercept.Stop
        if (matchesAsPhrase(t, HELP_PATTERNS)) return Intercept.Help
        if (matchesAsPhrase(t, REPEAT_PATTERNS)) return Intercept.RepeatLast
        if (matchesAsPhrase(t, CALL_BACK_PATTERNS)) return Intercept.CallBackLast

        return Intercept.None
    }

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

    /** Short help text spoken by TTS on Help intercept. */
    const val HELP_TEXT =
        "พูดว่า โทรหา ตามด้วยชื่อ, เปิด YouTube ตามด้วยชื่อเพลง, เปิดวิทยุ ตามด้วยคลื่น, หรือหยุด เพื่อปิดเสียง"
}

package com.moto.voice.nlu

/**
 * Pure Thai/English text-similarity helpers, extracted from ContactMatcher so they
 * can be unit-tested without a Context.
 *
 * The design goal is "close enough" matching under Thai STT noise — vowel signs and
 * prefixes (คุณ / นาย / พี่ / น้อง) get stripped, case is folded, and Levenshtein
 * distance produces the final score. Not a real linguistic tokenizer.
 */
object ThaiNormalizer {

    /** Prefixes we strip before comparing — mostly Thai honorifics + English titles. */
    val PREFIXES: List<String> = listOf(
        "คุณ", "พี่", "น้อง",
        "นาย", "นางสาว", "นาง", "เด็กชาย", "เด็กหญิง",
        "ดร.", "ศ.", "รศ.", "ผศ.", "พล.", "พ.ต.", "ร.ต.", "ส.ต.",
        "Mr.", "Mrs.", "Ms.", "Dr.", "Prof.",
    )

    /** Strip a leading prefix (if any) then trim + lowercase. */
    fun normalize(name: String): String {
        var s = name.trim()
        for (prefix in PREFIXES) {
            if (s.startsWith(prefix, ignoreCase = true)) {
                s = s.removePrefix(prefix).removePrefix(prefix.lowercase())
                    .removePrefix(prefix.uppercase())
                    .trim()
                break  // only strip one — "คุณคุณ" is real (and rare, but valid input)
            }
        }
        return s.lowercase()
    }

    /**
     * Similarity in [0, 1]. Substring matches score 0.9 to reward "สม" ⊆ "สมชาย".
     * Otherwise: 1 - (levenshtein / longer_length).
     */
    fun similarity(a: String, b: String): Float {
        if (a == b) return 1.0f
        if (a.isEmpty() || b.isEmpty()) return 0.0f
        if (b.contains(a) || a.contains(b)) return 0.9f
        val dist = levenshtein(a, b)
        val max = maxOf(a.length, b.length)
        return (1.0f - dist.toFloat() / max).coerceAtLeast(0f)
    }

    /** Classic edit distance. Space is O(n * m); we don't optimize because names are tiny. */
    fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
        }
        return dp[a.length][b.length]
    }
}

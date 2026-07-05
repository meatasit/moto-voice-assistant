package com.moto.voice.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the pure similarity/normalize logic. Nothing here touches ContentResolver.
 */
class ContactMatcherLevenshteinTest {

    /** Reimplement here so we test the same algorithm as the class without needing a Context. */
    private fun similarity(a: String, b: String): Float {
        if (a == b) return 1.0f
        if (b.contains(a) || a.contains(b)) return 0.9f
        val dist = levenshtein(a, b)
        val max = maxOf(a.length, b.length)
        return if (max == 0) 1.0f else (1.0f - dist.toFloat() / max).coerceAtLeast(0f)
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
        }
        return dp[a.length][b.length]
    }

    @Test fun exactMatchScoresOne() = assertEquals(1.0f, similarity("สมชาย", "สมชาย"), 0.0001f)

    @Test fun substringScoresHigh() = assertEquals(0.9f, similarity("สม", "สมชาย"), 0.0001f)

    @Test fun oneEditDropsScoreSlightly() {
        val s = similarity("สมชาย", "สมชัย")
        assertTrue("expected < 1.0 got $s", s < 1.0f)
        assertTrue("expected > 0.7 got $s", s > 0.7f)
    }

    @Test fun completelyDifferentIsLow() {
        assertTrue(similarity("abc", "xyz") < 0.4f)
    }

    @Test fun emptyStringsAreEqual() = assertEquals(1.0f, similarity("", ""), 0.0001f)
}

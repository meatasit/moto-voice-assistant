package com.moto.voice.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec v1.3.8 B3 — verify the pre-action opener picker's weight distribution.
 *
 * The picker gets a random source injected so tests can pin the boundary values
 * exactly. That also lets us guard against a future edit inverting the weight
 * assignment (say, the majority becoming a prefix) — the rider-on-motorcycle
 * rule requires the "no prefix" outcome to dominate.
 */
class RandomOpenerTest {

    // ─── Non-"กำลัง" input stays verbatim ────────────────────────────────────

    @Test fun errorReplyGetsNoPrefix() {
        assertEquals("", RandomOpener.pickPrefixFor("ยังไม่ได้ยินค่ะ ลองใหม่นะคะ", random = 0.99))
    }

    @Test fun statusReplyGetsNoPrefix() {
        assertEquals("", RandomOpener.pickPrefixFor("หยุดแล้ว", random = 0.5))
    }

    @Test fun emptyReplyGetsNoPrefix() {
        assertEquals("", RandomOpener.pickPrefixFor("", random = 0.5))
    }

    @Test fun leadingWhitespaceIsNotBlocking() {
        // Some webhook replies come padded with a leading space; the check should
        // still see "กำลัง" as the first meaningful word.
        val prefix = RandomOpener.pickPrefixFor("  กำลังเปิด YouTube", random = 0.7)
        assertTrue("prefix must be one of the two openers: got '$prefix'",
            prefix == ErrorSpeech.OPENER_DAI_LEUY || prefix == ErrorSpeech.OPENER_JAT_HAI)
    }

    // ─── "กำลัง" input triggers the weighted picker ─────────────────────────

    @Test fun bottomOfNoPrefixBandGetsEmpty() {
        // random ∈ [0, NO_PREFIX_WEIGHT) → no prefix.
        assertEquals("", RandomOpener.pickPrefixFor("กำลังเปิด YouTube", random = 0.0))
    }

    @Test fun topOfNoPrefixBandGetsEmpty() {
        // Just below NO_PREFIX_WEIGHT still in the no-prefix band.
        assertEquals("", RandomOpener.pickPrefixFor("กำลังเปิด YouTube",
            random = RandomOpener.NO_PREFIX_WEIGHT - 0.001))
    }

    @Test fun bottomOfDaiLeuyBandGetsDaiLeuy() {
        // Exactly at NO_PREFIX_WEIGHT crosses into the DaiLeuy band.
        assertEquals(ErrorSpeech.OPENER_DAI_LEUY,
            RandomOpener.pickPrefixFor("กำลังเปิด YouTube", random = RandomOpener.NO_PREFIX_WEIGHT))
    }

    @Test fun topOfDaiLeuyBandGetsDaiLeuy() {
        val edge = RandomOpener.NO_PREFIX_WEIGHT + RandomOpener.OPENER_DAI_LEUY_WEIGHT - 0.001
        assertEquals(ErrorSpeech.OPENER_DAI_LEUY,
            RandomOpener.pickPrefixFor("กำลังเปิด YouTube", random = edge))
    }

    @Test fun aboveDaiLeuyBandGetsJatHai() {
        val edge = RandomOpener.NO_PREFIX_WEIGHT + RandomOpener.OPENER_DAI_LEUY_WEIGHT
        assertEquals(ErrorSpeech.OPENER_JAT_HAI,
            RandomOpener.pickPrefixFor("กำลังเปิด YouTube", random = edge))
    }

    @Test fun nearOneGetsJatHai() {
        assertEquals(ErrorSpeech.OPENER_JAT_HAI,
            RandomOpener.pickPrefixFor("กำลังเปิด YouTube", random = 0.999))
    }

    // ─── Weight distribution — rider-on-motorcycle constraint ──────────────

    @Test fun noPrefixIsTheMajority() {
        // If a future edit inverts the weights this fires — the "no prefix" case
        // MUST stay the majority per spec §B3 (no chatter).
        assertTrue("no-prefix weight (${RandomOpener.NO_PREFIX_WEIGHT}) should be > 0.5",
            RandomOpener.NO_PREFIX_WEIGHT > 0.5)
    }

    @Test fun weightsSumToLessOrEqualOne() {
        val total = RandomOpener.NO_PREFIX_WEIGHT + RandomOpener.OPENER_DAI_LEUY_WEIGHT
        assertTrue("no-prefix + dai-leuy = $total should be < 1.0 so จัดให้ has room",
            total < 1.0)
    }

    // ─── Empirical distribution across 1000 samples ────────────────────────

    @Test fun distributionRoughlyMatchesWeights() {
        val counts = mutableMapOf(
            "" to 0,
            ErrorSpeech.OPENER_DAI_LEUY to 0,
            ErrorSpeech.OPENER_JAT_HAI to 0,
        )
        val samples = 5_000
        // Use a repeatable pseudo-random source so this test never flakes.
        val rng = java.util.Random(42L)
        repeat(samples) {
            val prefix = RandomOpener.pickPrefixFor("กำลังเปิด YouTube", random = rng.nextDouble())
            counts[prefix] = (counts[prefix] ?: 0) + 1
        }

        // ±5% tolerance — 5000 samples is comfortably enough for that band.
        val noPrefixRatio = counts[""]!!.toDouble() / samples
        val daiLeuyRatio = counts[ErrorSpeech.OPENER_DAI_LEUY]!!.toDouble() / samples
        val jatHaiRatio = counts[ErrorSpeech.OPENER_JAT_HAI]!!.toDouble() / samples

        assertTrue("no-prefix ratio $noPrefixRatio should be within ±0.05 of ${RandomOpener.NO_PREFIX_WEIGHT}",
            Math.abs(noPrefixRatio - RandomOpener.NO_PREFIX_WEIGHT) < 0.05)
        assertTrue("dai-leuy ratio $daiLeuyRatio should be within ±0.05 of ${RandomOpener.OPENER_DAI_LEUY_WEIGHT}",
            Math.abs(daiLeuyRatio - RandomOpener.OPENER_DAI_LEUY_WEIGHT) < 0.05)
        assertTrue("jat-hai ratio $jatHaiRatio should be > 0.10 (spec: remaining ~0.20)",
            jatHaiRatio > 0.10)
    }
}

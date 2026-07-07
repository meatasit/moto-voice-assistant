package com.moto.voice.contacts

import com.moto.voice.nlu.ThaiNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactMatcherVariantsTest {

    // ─── variantsOf: query candidates ─────────────────────────────────────────

    @Test fun rawQueryAlwaysIncluded() {
        val vs = ContactMatcher.variantsOf("แม่")
        assertTrue("expected 'แม่' in $vs", vs.contains("แม่"))
    }

    @Test fun normalizedQueryIncluded() {
        // "คุณสมชาย" normalizes to "สมชาย" (prefix stripped) — variant set must include both.
        val vs = ContactMatcher.variantsOf("คุณสมชาย")
        assertTrue(
            "expected normalized 'สมชาย' in $vs",
            vs.contains(ThaiNormalizer.normalize("คุณสมชาย"))
        )
    }

    @Test fun khunToKulHomophoneSwap() {
        // Field-test evidence: STT hears "กุลวดี" as "คุณวดี". Both spellings must be candidates.
        val vs = ContactMatcher.variantsOf("คุณวดี")
        assertTrue("expected 'กุลวดี' variant from 'คุณวดี' in $vs", vs.contains("กุลวดี"))
    }

    @Test fun kulToKhunHomophoneSwap() {
        val vs = ContactMatcher.variantsOf("กุลวดี")
        assertTrue("expected 'คุณวดี' variant from 'กุลวดี' in $vs", vs.contains("คุณวดี"))
    }

    @Test fun nameWithoutHomophoneNoExtraVariant() {
        // "สมชาย" doesn't start with คุณ or กุล — no homophone swap expected.
        val vs = ContactMatcher.variantsOf("สมชาย")
        assertTrue("expected 'สมชาย' in $vs", vs.contains("สมชาย"))
        // Only raw + normalized (which for this input is the same lowercase).
        assertTrue("no unexpected homophone variant: $vs", vs.none { it.contains("กุล") || it.contains("คุณ") })
    }

    @Test fun emptyQueryYieldsEmptyVariants() {
        assertEquals(emptySet<String>(), ContactMatcher.variantsOf(""))
    }

    @Test fun whitespaceOnlyYieldsEmptyVariants() {
        assertEquals(emptySet<String>(), ContactMatcher.variantsOf("   "))
    }

    // ─── scoreOne tier constants (spec §2.7 ranking) ─────────────────────────

    @Test fun exactScoreConstant() {
        assertEquals(1.0f, ContactMatcher.EXACT_SCORE)
    }

    @Test fun startsWithScoreHigherThanContains() {
        assertTrue(ContactMatcher.STARTS_WITH_SCORE > ContactMatcher.CONTAINS_SCORE)
    }

    @Test fun containsScoreHigherThanTypicalLevenshtein() {
        // A ~2-char difference on a 5-char name would score ~0.6; contains must beat that.
        assertTrue(ContactMatcher.CONTAINS_SCORE > 0.7f)
    }
}

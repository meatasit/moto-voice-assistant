package com.moto.voice.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThaiNormalizerTest {

    // ── normalize() ───────────────────────────────────────────────────────────

    @Test fun stripsThaiHonorific() = assertEquals("สมชาย", ThaiNormalizer.normalize("คุณสมชาย"))
    @Test fun stripsPee() = assertEquals("นน", ThaiNormalizer.normalize("พี่นน"))
    @Test fun stripsNong() = assertEquals("แนน", ThaiNormalizer.normalize("น้องแนน"))
    @Test fun stripsWithSpace() = assertEquals("สมชาย", ThaiNormalizer.normalize("คุณ สมชาย"))
    @Test fun stripsEnglishTitle() = assertEquals("john", ThaiNormalizer.normalize("Mr. John"))
    @Test fun lowercasesEnglish() = assertEquals("john smith", ThaiNormalizer.normalize("John Smith"))
    @Test fun onlyStripsOnePrefix() = assertEquals("คุณสมชาย", ThaiNormalizer.normalize("คุณคุณสมชาย"))
    @Test fun trimsSpaces() = assertEquals("สมชาย", ThaiNormalizer.normalize("   สมชาย   "))
    @Test fun emptyStaysEmpty() = assertEquals("", ThaiNormalizer.normalize(""))
    @Test fun nonPrefixedNameUnchanged() = assertEquals("สมชาย", ThaiNormalizer.normalize("สมชาย"))

    // ── similarity() ──────────────────────────────────────────────────────────

    @Test fun identicalScoresOne() =
        assertEquals(1.0f, ThaiNormalizer.similarity("สมชาย", "สมชาย"), 0.0001f)

    @Test fun substringScoresNinetenths() =
        assertEquals(0.9f, ThaiNormalizer.similarity("สม", "สมชาย"), 0.0001f)

    @Test fun oneEditIsHigh() {
        val s = ThaiNormalizer.similarity("สมชาย", "สมชัย")
        assertTrue("expected 0.7 < s < 1.0, got $s", s > 0.7f && s < 1.0f)
    }

    @Test fun completelyDifferentIsLow() {
        assertTrue(ThaiNormalizer.similarity("abc", "xyz") < 0.4f)
    }

    @Test fun eitherEmptyIsZero() {
        assertEquals(0.0f, ThaiNormalizer.similarity("", "สมชาย"), 0.0001f)
        assertEquals(0.0f, ThaiNormalizer.similarity("สมชาย", ""), 0.0001f)
    }

    @Test fun bothEmptyIsOne() =
        assertEquals(1.0f, ThaiNormalizer.similarity("", ""), 0.0001f)

    // ── levenshtein() ─────────────────────────────────────────────────────────

    @Test fun distanceIdentical() = assertEquals(0, ThaiNormalizer.levenshtein("cat", "cat"))
    @Test fun distanceOneSub() = assertEquals(1, ThaiNormalizer.levenshtein("cat", "bat"))
    @Test fun distanceInsertion() = assertEquals(1, ThaiNormalizer.levenshtein("cat", "cats"))
    @Test fun distanceDeletion() = assertEquals(1, ThaiNormalizer.levenshtein("cats", "cat"))
    @Test fun distanceEmpties() = assertEquals(0, ThaiNormalizer.levenshtein("", ""))
    @Test fun distanceOneEmpty() = assertEquals(3, ThaiNormalizer.levenshtein("", "abc"))
}

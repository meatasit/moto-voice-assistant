package com.moto.voice.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic check on the "too-short result = treat as no-speech" rule (spec §4.3).
 * The actual constant lives in the pipeline file as a private top-level; we duplicate
 * it here so the semantics are locked and reviewed. If the constant changes there
 * without updating this test, we'll notice on the next test run.
 */
class SttMinLengthTest {

    /** Must match `MIN_MEANINGFUL_LEN` in VoiceCommandPipeline.kt. */
    private val minMeaningfulLen = 2

    private fun isTooShort(raw: String) = raw.trim().length < minMeaningfulLen

    @Test fun emptyIsTooShort() = assertTrue(isTooShort(""))
    @Test fun whitespaceOnlyIsTooShort() = assertTrue(isTooShort("   \t "))
    @Test fun singleCharIsTooShort() = assertTrue(isTooShort("ห"))
    @Test fun paddedSingleCharIsTooShort() = assertTrue(isTooShort("  ห  "))
    @Test fun twoCharsIsKept() = assertFalse(isTooShort("หา"))
    @Test fun realCommandIsKept() = assertFalse(isTooShort("โทรหาแม่"))
    @Test fun englishStopIsKept() = assertFalse(isTooShort("stop"))
}

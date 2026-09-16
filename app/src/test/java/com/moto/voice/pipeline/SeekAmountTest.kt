package com.moto.voice.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.4.4 — a missing seek amount must never be read as "resume". */
class SeekAmountTest {

    @Test fun missingAmountIsUnknownNotResume() {
        assertEquals(SeekAmount.Decision.Unknown, SeekAmount.decide(null))
        assertEquals(SeekAmount.Decision.Unknown, SeekAmount.decide(Double.NaN))
    }

    @Test fun explicitZeroIsResume() {
        assertEquals(SeekAmount.Decision.Resume, SeekAmount.decide(0.0))
        assertEquals(SeekAmount.Decision.Resume, SeekAmount.decide(-0.0))
    }

    @Test fun signedSecondsSeek() {
        assertEquals(SeekAmount.Decision.Seek(600), SeekAmount.decide(600.0))
        assertEquals(SeekAmount.Decision.Seek(-30), SeekAmount.decide(-30.0))
        // Rounds instead of truncating toward zero.
        assertEquals(SeekAmount.Decision.Seek(30), SeekAmount.decide(29.6))
        assertEquals(SeekAmount.Decision.Seek(-1), SeekAmount.decide(-0.6))
    }
}

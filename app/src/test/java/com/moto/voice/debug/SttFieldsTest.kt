package com.moto.voice.debug

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the Sprint C new fields on DebugEntry are wired with sensible defaults so
 * legacy JSON exports (that lack these fields) still deserialize.
 */
class SttFieldsTest {

    @Test fun defaultsWhenOmitted() {
        val e = DebugEntry()
        assertEquals(-1f, e.sttConfidence)
        assertEquals(0, e.sttRetryCount)
    }

    @Test fun confidenceCanBeSet() {
        val e = DebugEntry(sttConfidence = 0.83f)
        assertEquals(0.83f, e.sttConfidence)
    }

    @Test fun retryCountCanBeSet() {
        val e = DebugEntry(sttRetryCount = 1)
        assertEquals(1, e.sttRetryCount)
    }
}

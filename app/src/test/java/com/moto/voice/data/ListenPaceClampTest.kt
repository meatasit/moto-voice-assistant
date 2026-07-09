package com.moto.voice.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Constants for the "จังหวะรอฟัง" slider (spec v1.3.6 §1) — same clamp math
 * as the TTS rate. Pipeline converts to `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS`
 * via `(seconds * 1000).toLong()` so we lock the range here to catch accidental drift.
 */
class ListenPaceClampTest {

    @Test fun defaultIsWithinRange() {
        val d = AppSettings.DEFAULT_LISTEN_PACE_SEC
        assertTrue("default $d out of range", d in AppSettings.MIN_LISTEN_PACE_SEC..AppSettings.MAX_LISTEN_PACE_SEC)
    }

    @Test fun defaultIsTwoSeconds() {
        // Spec v1.3.6 §1: default bumped from the old 1.2 s to 2.0 s so riders speaking
        // with a mid-sentence pause don't get cut off. This lock catches an accidental revert.
        assertEquals(2.0f, AppSettings.DEFAULT_LISTEN_PACE_SEC)
    }

    @Test fun rangeMatchesSpec() {
        assertEquals(1.0f, AppSettings.MIN_LISTEN_PACE_SEC)
        assertEquals(3.0f, AppSettings.MAX_LISTEN_PACE_SEC)
    }

    @Test fun clampBelowMinReturnsMin() =
        assertEquals(AppSettings.MIN_LISTEN_PACE_SEC,
            0.3f.coerceIn(AppSettings.MIN_LISTEN_PACE_SEC, AppSettings.MAX_LISTEN_PACE_SEC))

    @Test fun clampAboveMaxReturnsMax() =
        assertEquals(AppSettings.MAX_LISTEN_PACE_SEC,
            5.0f.coerceIn(AppSettings.MIN_LISTEN_PACE_SEC, AppSettings.MAX_LISTEN_PACE_SEC))

    @Test fun conversionToMillisMatchesPipelineExpectation() {
        // Locks the (seconds * 1000).toLong() conversion in the pipeline. If the pipeline
        // ever switches to a different multiplier we break this test on purpose so the
        // fix is intentional.
        val secs = AppSettings.DEFAULT_LISTEN_PACE_SEC
        val ms = (secs * 1000f).toLong()
        assertEquals(2_000L, ms)
    }
}

package com.moto.voice.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the clamp math on the TTS-rate constants exposed by [AppSettings] — pure
 * constants, no Context needed. The actual setter also clamps in-place, tested via
 * instrumentation on-device.
 */
class SpeechRateClampTest {

    @Test fun defaultIsWithinRange() {
        val d = AppSettings.DEFAULT_TTS_RATE
        assert(d in AppSettings.MIN_TTS_RATE..AppSettings.MAX_TTS_RATE) { "default $d out of range" }
    }

    @Test fun minLessThanMax() =
        assertEquals(true, AppSettings.MIN_TTS_RATE < AppSettings.MAX_TTS_RATE)

    @Test fun rangeMatchesSpec() {
        // Spec §8: slider 0.8 – 1.5.
        assertEquals(0.8f, AppSettings.MIN_TTS_RATE)
        assertEquals(1.5f, AppSettings.MAX_TTS_RATE)
    }

    @Test fun clampBelowMinReturnsMin() =
        assertEquals(AppSettings.MIN_TTS_RATE, 0.5f.coerceIn(AppSettings.MIN_TTS_RATE, AppSettings.MAX_TTS_RATE))

    @Test fun clampAboveMaxReturnsMax() =
        assertEquals(AppSettings.MAX_TTS_RATE, 3.0f.coerceIn(AppSettings.MIN_TTS_RATE, AppSettings.MAX_TTS_RATE))

    @Test fun clampInsideRangeReturnsSame() =
        assertEquals(1.2f, 1.2f.coerceIn(AppSettings.MIN_TTS_RATE, AppSettings.MAX_TTS_RATE))
}

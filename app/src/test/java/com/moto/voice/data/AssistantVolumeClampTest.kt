package com.moto.voice.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantVolumeClampTest {

    @Test fun defaultInRange() {
        val v = AppSettings.DEFAULT_ASSIST_VOLUME
        assertTrue(v in AppSettings.MIN_ASSIST_VOLUME..AppSettings.MAX_ASSIST_VOLUME)
    }

    @Test fun rangeMatchesSpec() {
        // §2.4: volume boost slider; 0.5 (attenuated) to 1.5 (boosted).
        assertEquals(0.5f, AppSettings.MIN_ASSIST_VOLUME)
        assertEquals(1.5f, AppSettings.MAX_ASSIST_VOLUME)
    }

    @Test fun clampBelowMin() =
        assertEquals(AppSettings.MIN_ASSIST_VOLUME, 0.1f.coerceIn(AppSettings.MIN_ASSIST_VOLUME, AppSettings.MAX_ASSIST_VOLUME))

    @Test fun clampAboveMax() =
        assertEquals(AppSettings.MAX_ASSIST_VOLUME, 2.5f.coerceIn(AppSettings.MIN_ASSIST_VOLUME, AppSettings.MAX_ASSIST_VOLUME))

    @Test fun clampInRangeStays() =
        assertEquals(1.2f, 1.2f.coerceIn(AppSettings.MIN_ASSIST_VOLUME, AppSettings.MAX_ASSIST_VOLUME))
}

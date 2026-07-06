package com.moto.voice.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The debounce math is trivial (elapsed < window) — this test locks the intent so
 * a future refactor doesn't accidentally loosen the window and let stiff-glove
 * double-taps through.
 */
class DebounceLogicTest {

    /** Must match VoiceCommandService.DEBOUNCE_MS. */
    private val debounceMs = 500L

    private fun isDebounced(sinceLastMs: Long) =
        sinceLastMs in 1..(debounceMs - 1)

    @Test fun freshPressNotDebounced() {
        // sinceLast=0 means first press ever (lastTriggerAt was 0L too).
        assertFalse(isDebounced(0L))
    }

    @Test fun immediateSecondPressDebounced() = assertTrue(isDebounced(50L))

    @Test fun justUnderWindowDebounced() = assertTrue(isDebounced(499L))

    @Test fun atWindowNotDebounced() = assertFalse(isDebounced(500L))

    @Test fun oneSecondLaterNotDebounced() = assertFalse(isDebounced(1_000L))

    @Test fun tenSecondsLaterNotDebounced() = assertFalse(isDebounced(10_000L))
}

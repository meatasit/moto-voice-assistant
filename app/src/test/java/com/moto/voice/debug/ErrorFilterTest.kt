package com.moto.voice.debug

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The predicate used by DebugLogActivity's "Errors only" chip. Kept here so the
 * definition of "worth surfacing to the rider" is testable and locked.
 */
class ErrorFilterTest {

    private fun isErrorlike(e: DebugEntry) =
        e.error != null ||
        (e.finishReason != null && e.finishReason != FinishReason.OK && e.finishReason != FinishReason.INTERCEPTED)

    @Test fun okEntryIsNotError() {
        assertFalse(isErrorlike(DebugEntry().apply { finishReason = FinishReason.OK }))
    }

    @Test fun interceptedEntryIsNotError() {
        // Local intercept commands (stop / help / repeat) succeeded; not a diagnostic surface.
        assertFalse(isErrorlike(DebugEntry().apply { finishReason = FinishReason.INTERCEPTED }))
    }

    @Test fun timeoutEntryIsError() {
        assertTrue(isErrorlike(DebugEntry().apply { finishReason = FinishReason.TIMEOUT_FALLBACK }))
    }

    @Test fun httpErrorEntryIsError() {
        assertTrue(isErrorlike(DebugEntry().apply { finishReason = FinishReason.HTTP_401 }))
    }

    @Test fun noSpeechEntryIsError() {
        assertTrue(isErrorlike(DebugEntry().apply { finishReason = FinishReason.NO_SPEECH }))
    }

    @Test fun explicitErrorFieldIsError() {
        assertTrue(isErrorlike(DebugEntry(error = "STT 7")))
    }

    @Test fun bargeInIsError() {
        // Barge-in is a rider-initiated cancel — surface it in the errors filter so
        // riders / debuggers can see how often they had to interrupt the flow.
        assertTrue(isErrorlike(DebugEntry().apply { finishReason = FinishReason.BARGE_IN }))
    }

    @Test fun emptyEntryIsNotError() {
        // A fresh DebugEntry that never got populated (edge case) shouldn't be surfaced.
        assertFalse(isErrorlike(DebugEntry()))
    }
}

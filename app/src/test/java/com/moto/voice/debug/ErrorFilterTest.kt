package com.moto.voice.debug

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The predicate used by DebugLogActivity's "Errors only" chip. Kept here so the
 * definition of "worth surfacing to the rider" is testable and locked.
 *
 * v1.4.5 — this file used to re-implement the predicate, which meant the rule the tests
 * locked and the rule the screen shipped were two different pieces of code. It now calls
 * [DebugEntry.isErrorlike], the one definition.
 */
class ErrorFilterTest {

    private fun isErrorlike(e: DebugEntry) = e.isErrorlike()

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

    // ─── v1.4.5: a dropped follow-up listen is not the interaction's failure ──

    @Test fun followupSttDropIsNotError() {
        // Field log 1789561893967 shape: chat answered fine, rider said nothing more,
        // the 4s follow-up recognizer dropped with 11.
        assertFalse(
            "a rider with nothing more to say did not make this interaction fail",
            isErrorlike(DebugEntry(error = "followup_stt 11").apply { finishReason = FinishReason.OK }),
        )
    }

    @Test fun mainSttDropIsStillAnError() {
        assertTrue(isErrorlike(DebugEntry(error = "STT 11")))
    }

    @Test fun followupLabelDoesNotMaskARealFinishReason() {
        assertTrue(
            "the error label is not a licence to hide a bad finishReason",
            isErrorlike(
                DebugEntry(error = "followup_stt 11").apply { finishReason = FinishReason.LAUNCH_BLOCKED }
            ),
        )
    }
}

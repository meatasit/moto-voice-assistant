package com.moto.voice.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.6 — the STT failure stamp carries how long the listen lasted before it dropped.
 *
 * Across field logs 1789561893967 and 1789637279880 the follow-up window dropped with
 * error 11 on all eight chats that opened one, `followupUsed: false` every time. Whether
 * the recognizer died instantly or held the mic for the full 4 s points at different causes,
 * and neither log could say which. This is the measurement that comes before the fix.
 */
class SttErrorStampTest {

    @Test fun stampCarriesLabelCodeAndElapsed() = assertEquals(
        "followup_stt 11@85ms", sttErrorStamp(FOLLOWUP_STT_ERROR_PREFIX, 11, 85L),
    )

    @Test fun stampedFollowupIsStillNotErrorlike() = assertFalse(
        "v1.4.5's rule keys off the prefix and must survive the added suffix",
        DebugEntry(error = sttErrorStamp(FOLLOWUP_STT_ERROR_PREFIX, 11, 85L))
            .apply { finishReason = FinishReason.OK }
            .isErrorlike(),
    )

    @Test fun stampedMainSttIsStillErrorlike() = assertTrue(
        DebugEntry(error = sttErrorStamp("STT", 11, 4200L)).isErrorlike(),
    )

    @Test fun grepForBareSttDoesNotMatchAFollowup() = assertFalse(
        "a 'STT 11' grep over an export must not pick these up",
        sttErrorStamp(FOLLOWUP_STT_ERROR_PREFIX, 11, 85L).contains("STT 11"),
    )
}

package com.moto.voice.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Ensures the new BARGE_IN string in FinishReason is stable + distinct — an existing
 * exported debug log parser (if anyone builds one) can rely on it.
 */
class BargeInFinishReasonTest {

    @Test fun bargeInValue() = assertEquals("barge_in_cancel", FinishReason.BARGE_IN)

    @Test fun distinctFromOtherReasons() {
        val others = listOf(
            FinishReason.OK, FinishReason.TIMEOUT_FALLBACK, FinishReason.HTTP_401,
            FinishReason.HTTP_OTHER, FinishReason.NETWORK, FinishReason.OFFLINE_RULE,
            FinishReason.NO_SPEECH, FinishReason.PHONE_UNAVAILABLE,
            FinishReason.INTERCEPTED, FinishReason.LLM_OFF, FinishReason.PARSE_ERROR,
        )
        others.forEach { assertNotEquals("BARGE_IN clashes with $it", FinishReason.BARGE_IN, it) }
    }
}

package com.moto.voice.nlu

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.10 field-report fix: rider said "เปิด YouTube", heard "เปิดอะไรดีคะ", then
 * heard "ยกเลิกแล้ว" — because Google STT's server disconnected mid-listen and
 * the pipeline's blank-answer path defaulted to CANCELLED. Users read that as
 * "the app rejected me". The new SERVER_UNAVAILABLE line makes it clear it's a
 * server hiccup and not a rejection.
 */
class ServerUnavailableLineTest {

    @Test fun serverUnavailableIsDistinctFromCancelled() {
        // The whole point of the new line is to be recognizably different from
        // "ยกเลิกแล้ว" — a future refactor that collapses them would defeat the fix.
        assertNotEquals(ErrorSpeech.SERVER_UNAVAILABLE, ErrorSpeech.CANCELLED)
    }

    @Test fun serverUnavailableSuggestsRetry() {
        // The line should tell the rider to try again, not just apologise.
        // "ลองใหม่" is the shared word both persona variants use.
        assertTrue(
            "SERVER_UNAVAILABLE must invite a retry: ${ErrorSpeech.SERVER_UNAVAILABLE}",
            ErrorSpeech.SERVER_UNAVAILABLE.contains("ลองใหม่"),
        )
    }

    @Test fun serverUnavailableIncludedInSystemLines() {
        // Must be in the pre-synth list so the assistant doesn't stall waiting
        // for Azure to synthesize this line on a network hiccup.
        assertTrue(
            "SERVER_UNAVAILABLE must be pre-synthesized",
            ErrorSpeech.allSystemLines().contains(ErrorSpeech.SERVER_UNAVAILABLE),
        )
    }
}

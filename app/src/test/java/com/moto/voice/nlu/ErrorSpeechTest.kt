package com.moto.voice.nlu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sanity checks on the standardized TTS vocabulary — all lines must be non-blank
 * (never dead-air) and no two lines are identical (so the rider can distinguish
 * failure categories by ear).
 */
class ErrorSpeechTest {

    private val allLines = listOf(
        ErrorSpeech.THINKING,
        ErrorSpeech.ONE_MORE_MOMENT,
        ErrorSpeech.OFFLINE_LIMITED,
        ErrorSpeech.TIMEOUT_WITH_FALLBACK,
        ErrorSpeech.TIMEOUT_NO_FALLBACK,
        ErrorSpeech.HTTP_401,
        ErrorSpeech.HTTP_OTHER,
        ErrorSpeech.YOUTUBE_NOT_FOUND,
        ErrorSpeech.FM_STREAM_FAILED,
        ErrorSpeech.NO_CELL_SIGNAL,
        ErrorSpeech.NOT_HEARD_RETRY,
        ErrorSpeech.NOT_HEARD_GIVING_UP,
        ErrorSpeech.CANCELLED,
    )

    @Test fun everyLineIsNonBlank() {
        allLines.forEachIndexed { i, s -> assertTrue("line #$i is blank", s.isNotBlank()) }
    }

    @Test fun everyLineEndsPolite() {
        // Feminine polite ending per requirement — either "ค่ะ" or "นะคะ" somewhere.
        allLines.forEach { line ->
            assertTrue(
                "expected ค่ะ/นะคะ in '$line'",
                line.contains("ค่ะ") || line.contains("นะคะ")
            )
        }
    }

    @Test fun linesAreDistinct() {
        val seen = mutableSetOf<String>()
        allLines.forEach {
            assertTrue("duplicate: '$it'", seen.add(it))
        }
    }

    @Test fun timeoutVariantsDiffer() {
        assertNotEquals(ErrorSpeech.TIMEOUT_WITH_FALLBACK, ErrorSpeech.TIMEOUT_NO_FALLBACK)
    }

    @Test fun progressLinesShort() {
        // These play mid-request; keep them short so they don't overlap the actual
        // response arriving from n8n.
        assertTrue(ErrorSpeech.THINKING.length < 40)
        assertTrue(ErrorSpeech.ONE_MORE_MOMENT.length < 20)
    }

    @Test fun offlineMentionsWhatWorks() {
        // Rider needs to know they can still call and stop even when offline.
        assertTrue(ErrorSpeech.OFFLINE_LIMITED.contains("โทร"))
        assertTrue(ErrorSpeech.OFFLINE_LIMITED.contains("หยุด"))
    }

    @Test fun cancelledIsBrief() {
        // Barge-in should not linger — brief acknowledgement so we're ready fast.
        assertFalse(ErrorSpeech.CANCELLED.length > 25)
    }
}

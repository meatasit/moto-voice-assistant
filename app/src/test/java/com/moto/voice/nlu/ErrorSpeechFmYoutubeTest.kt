package com.moto.voice.nlu

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the feature-specific error lines used by FmPlayerService (retry exhaust)
 * and the YouTube fallback path so a well-meaning rewording doesn't accidentally
 * change the phrasing the rider is used to hearing.
 */
class ErrorSpeechFmYoutubeTest {

    @Test fun fmMentionsStation() {
        assertTrue(
            "expected 'สถานี' in '${ErrorSpeech.FM_STREAM_FAILED}'",
            ErrorSpeech.FM_STREAM_FAILED.contains("สถานี")
        )
    }

    @Test fun fmMentionsTemporary() {
        // Rider should understand it's not their fault and might work later.
        assertTrue(
            ErrorSpeech.FM_STREAM_FAILED.contains("ชั่วคราว") ||
            ErrorSpeech.FM_STREAM_FAILED.contains("ไม่สำเร็จ")
        )
    }

    @Test fun youtubeSuggestsSpecificName() {
        assertTrue(
            "expected 'เจาะจง' in '${ErrorSpeech.YOUTUBE_NOT_FOUND}'",
            ErrorSpeech.YOUTUBE_NOT_FOUND.contains("เจาะจง")
        )
    }

    @Test fun noCellSignalMentionsSignal() {
        assertTrue(
            "expected 'สัญญาณ' in '${ErrorSpeech.NO_CELL_SIGNAL}'",
            ErrorSpeech.NO_CELL_SIGNAL.contains("สัญญาณ")
        )
    }

    @Test fun fmYoutubeSignalLinesAllPolite() {
        listOf(
            ErrorSpeech.FM_STREAM_FAILED,
            ErrorSpeech.YOUTUBE_NOT_FOUND,
            ErrorSpeech.NO_CELL_SIGNAL,
        ).forEach { line ->
            assertTrue("'$line' missing polite ending",
                line.contains("ค่ะ") || line.contains("นะคะ"))
        }
    }
}

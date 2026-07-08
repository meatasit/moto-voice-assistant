package com.moto.voice.pipeline

import com.moto.voice.tts.TtsRecentSpeech
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies the round-2 fix: [TtsEchoFilter.isSelfEcho] catches echoes from ANY
 * TTS source (FmPlayerService, HelmetGreeter, preflight) via the global
 * [TtsRecentSpeech] singleton — the per-pipeline echo filter alone couldn't
 * see them.
 */
class TtsEchoFilterGlobalTest {

    @Before fun reset() = TtsRecentSpeech.resetForTest()
    @After fun tearDown() = TtsRecentSpeech.resetForTest()

    @Test fun noRecentSpeechMeansNoEcho() {
        assertFalse(TtsEchoFilter.isSelfEcho("โทรหาแม่"))
    }

    @Test fun exactMatchIsEcho() {
        TtsRecentSpeech.markSpeaking("เปิดสถานีไม่สำเร็จค่ะ สถานีอาจมีปัญหาชั่วคราว")
        assertTrue(TtsEchoFilter.isSelfEcho("เปิดสถานีไม่สำเร็จค่ะ สถานีอาจมีปัญหาชั่วคราว"))
    }

    @Test fun observedFieldCaseIsFiltered() {
        // Log evidence: sttFinal captured this ≈ FM_STREAM_FAILED spoken by
        // FmPlayerService's retry-exhaust path.
        TtsRecentSpeech.markSpeaking("เปิดสถานีไม่สำเร็จค่ะ สถานีอาจมีปัญหาชั่วคราว")
        val heard = "เปิดสถานีไม่สำเร็จค่าสถานีอาจมีปัญหาชั่วคราว"
        assertTrue("field-test echo must be filtered", TtsEchoFilter.isSelfEcho(heard))
    }

    @Test fun realCommandIsNotEcho() {
        TtsRecentSpeech.markSpeaking("โหมดออฟไลน์ค่ะ ทำได้เฉพาะโทรกับหยุดเล่นนะคะ")
        assertFalse(TtsEchoFilter.isSelfEcho("โทรหาแม่"))
    }

    @Test fun blankSttResultIsNotEcho() {
        TtsRecentSpeech.markSpeaking("anything")
        assertFalse(TtsEchoFilter.isSelfEcho(""))
    }

    @Test fun endedBecomesRecentDuringLinger() {
        TtsRecentSpeech.markSpeaking("test")
        TtsRecentSpeech.markEnded()
        // Still within linger window.
        assertTrue(TtsEchoFilter.isSelfEcho("test"))
    }
}

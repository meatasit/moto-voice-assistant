package com.moto.voice.tts

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class TtsRecentSpeechTest {

    @Before fun reset() = TtsRecentSpeech.resetForTest()
    @After fun tearDown() = TtsRecentSpeech.resetForTest()

    @Test fun initialStateIsNull() = assertNull(TtsRecentSpeech.currentOrRecent())

    @Test fun speakingIsReturned() {
        TtsRecentSpeech.markSpeaking("สวัสดี")
        assertEquals("สวัสดี", TtsRecentSpeech.currentOrRecent())
    }

    @Test fun endedRecentlyIsReturned() {
        TtsRecentSpeech.markSpeaking("สวัสดี")
        TtsRecentSpeech.markEnded()
        // Still within the linger window (SystemClock hasn't jumped).
        assertEquals("สวัสดี", TtsRecentSpeech.currentOrRecent())
    }

    @Test fun endedWithoutSpeakingIsNoOp() {
        TtsRecentSpeech.markEnded()
        assertNull(TtsRecentSpeech.currentOrRecent())
    }

    @Test fun newSpeakingReplacesOld() {
        TtsRecentSpeech.markSpeaking("first")
        TtsRecentSpeech.markSpeaking("second")
        assertEquals("second", TtsRecentSpeech.currentOrRecent())
    }

    @Test fun lingerConstantIsPositive() {
        assertEquals(1_000L, TtsRecentSpeech.LINGER_AFTER_END_MS)
    }
}

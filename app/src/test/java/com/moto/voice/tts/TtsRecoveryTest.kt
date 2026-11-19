package com.moto.voice.tts

import com.google.gson.GsonBuilder
import com.moto.voice.debug.DebugEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field log 1794968515443 (v1.4.11): from 14 Nov on, every spoken confirmation came back in
 * ~50 ms (`actionTimeMs` 842–926 with `scoTeardownMs` ~815, against 3000–5500 the days
 * before) and the rider heard nothing — while the entries still said `ttsEngine: android`
 * and no `error`. A failed speak was swallowed and the engine was never rebuilt.
 *
 * This suite pins the two things v1.4.12 adds: when to rebuild the engine and retry, and
 * the `ttsError` line that makes a silent utterance visible in the next field log.
 */
class TtsRecoveryTest {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    // ─── when to rebuild the engine and say the line again ───────────────────

    @Test fun firstFailureOfTheCurrentLineRebinds() {
        assertTrue(TtsRecovery.shouldRebind(alreadyRetried = false, isLatest = true))
    }

    @Test fun aLineThatAlreadyRetriedGivesUp() {
        // One rebuild per line — a second failure is reported, never looped on.
        assertFalse(TtsRecovery.shouldRebind(alreadyRetried = true, isLatest = true))
    }

    @Test fun aSupersededLineIsNeverSpokenLate() {
        // A newer line flushed this one; retrying it would talk over the newer line.
        assertFalse(TtsRecovery.shouldRebind(alreadyRetried = false, isLatest = false))
    }

    // ─── "finished" far too fast to have been heard ──────────────────────────

    @Test fun instantDoneOnARealSentenceIsSuspect() {
        assertTrue(TtsRecovery.doneTooFast("กำลังเปิดไลฟ์กรรมกรข่าวค่ะ", elapsedMs = 40))
    }

    @Test fun normalPlaybackIsNotSuspect() {
        assertFalse(TtsRecovery.doneTooFast("กำลังเปิดไลฟ์กรรมกรข่าวค่ะ", elapsedMs = 2100))
    }

    @Test fun aVeryShortLineIsNeverSuspect() {
        assertFalse(TtsRecovery.doneTooFast("ค่ะ", elapsedMs = 40))
    }

    // ─── the line written to the debug entry ─────────────────────────────────

    @Test fun describesARecoveredFailure() {
        assertEquals(
            "speakReturnedError→rebind→ok",
            TtsRecovery.describe("speakReturnedError", rebound = true, recovered = true),
        )
    }

    @Test fun describesAFailureTheRebindDidNotFix() {
        assertEquals(
            "speakReturnedError→rebind→failed(code -9)",
            TtsRecovery.describe("speakReturnedError", rebound = true, recovered = false, finalReason = "code -9"),
        )
    }

    @Test fun describesAFailureWithNoRebind() {
        assertEquals("code -4", TtsRecovery.describe("code -4", rebound = false, recovered = false))
    }

    @Test fun languageResultNames() {
        assertNull(TtsRecovery.languageProblem(0))   // LANG_AVAILABLE
        assertNull(TtsRecovery.languageProblem(1))   // LANG_COUNTRY_AVAILABLE
        assertEquals("thaiVoiceMissingData", TtsRecovery.languageProblem(-1))
        assertEquals("thaiNotSupported", TtsRecovery.languageProblem(-2))
    }

    // ─── DebugEntry surface ──────────────────────────────────────────────────

    @Test fun ttsErrorDefaultsToNull() {
        assertNull(DebugEntry().ttsError)
    }

    @Test fun ttsErrorSerialisedWhenSet() {
        val json = gson.toJson(DebugEntry().apply { ttsError = "speakReturnedError→rebind→ok" })
        assertTrue("ttsError present: $json", json.contains("\"ttsError\""))
    }

    @Test fun ttsErrorsAccumulateOnOneEntry() {
        val e = DebugEntry()
        TtsRecovery.stamp(e, "a")
        TtsRecovery.stamp(e, "b")
        assertEquals("a;b", e.ttsError)
    }
}

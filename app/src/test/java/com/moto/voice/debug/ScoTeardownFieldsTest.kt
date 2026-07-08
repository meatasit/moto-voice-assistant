package com.moto.voice.debug

import android.media.AudioManager
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field-test bug (log 1783477052378): every entry had `scoState=connected` with no
 * teardown visibility — media that followed was silent every time. This suite locks
 * in the debug-log surface we added so future field logs can prove the pipeline
 * released SCO + reached MODE_NORMAL before starting media.
 */
class ScoTeardownFieldsTest {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    // ─── DebugEntry field surface ────────────────────────────────────────────

    @Test fun scoTeardownMsDefaultsToZero() {
        assertEquals(0L, DebugEntry().scoTeardownMs)
    }

    @Test fun audioModeDefaultsToNull() {
        assertEquals(null, DebugEntry().audioMode)
    }

    @Test fun engineChoiceReasonDefaultsToNull() {
        assertEquals(null, DebugEntry().engineChoiceReason)
    }

    @Test fun scoTeardownMsSerialisedWhenSet() {
        val entry = DebugEntry().apply {
            scoTeardownMs = 812L
            audioMode = AudioModeName.NORMAL
            engineChoiceReason = EngineChoiceReason.AZURE_USED
        }
        val json = gson.toJson(entry)
        assertTrue("scoTeardownMs present: $json", json.contains("\"scoTeardownMs\": 812"))
        assertTrue("audioMode present: $json", json.contains("\"audioMode\": \"normal\""))
        assertTrue("engineChoiceReason present: $json", json.contains("\"engineChoiceReason\": \"azure_used\""))
    }

    // ─── AudioModeName.of mapping — the round trip we log ───────────────────

    @Test fun modeNormalMapsToNormal() {
        assertEquals(AudioModeName.NORMAL, AudioModeName.of(AudioManager.MODE_NORMAL))
    }

    @Test fun modeInCommunicationMapsToInComm() {
        assertEquals(AudioModeName.IN_COMMUNICATION, AudioModeName.of(AudioManager.MODE_IN_COMMUNICATION))
    }

    @Test fun modeInCallMapsToInCall() {
        assertEquals(AudioModeName.IN_CALL, AudioModeName.of(AudioManager.MODE_IN_CALL))
    }

    @Test fun modeRingtoneMapsToRingtone() {
        assertEquals(AudioModeName.RINGTONE, AudioModeName.of(AudioManager.MODE_RINGTONE))
    }

    @Test fun unknownModeMapsToUnknown() {
        assertEquals(AudioModeName.UNKNOWN, AudioModeName.of(-42))
        // MODE_INVALID (-2) is what BluetoothAudioRouter.currentAudioMode() returns
        // when AudioManager is unavailable — must not crash the mapping.
        assertEquals(AudioModeName.UNKNOWN, AudioModeName.of(AudioManager.MODE_INVALID))
    }

    // ─── EngineChoiceReason constants — locked shape ────────────────────────

    @Test fun engineChoiceReasonConstantsAreStable() {
        // These strings appear verbatim in exported JSON, so refactors changing them
        // silently would break field-log tooling that greps for them.
        assertEquals("azure_used", EngineChoiceReason.AZURE_USED)
        assertEquals("azure_failed_fallback", EngineChoiceReason.AZURE_FAILED_FALLBACK)
        assertEquals("android_no_key", EngineChoiceReason.ANDROID_NO_KEY)
        assertEquals("android_no_region", EngineChoiceReason.ANDROID_NO_REGION)
        assertEquals("android_offline", EngineChoiceReason.ANDROID_OFFLINE)
    }

    // ─── Summary string surfaces the new fields ─────────────────────────────

    @Test fun summaryContainsTeardownMs() {
        val entry = DebugEntry().apply {
            sttFinal = "เปิดวิทยุ"
            scoTeardownMs = 820L
            audioMode = AudioModeName.NORMAL
            engineChoiceReason = EngineChoiceReason.AZURE_USED
            finishReason = FinishReason.OK
        }
        val s = entry.summary()
        assertTrue("teardown ms visible: $s", s.contains("SCO⇩:820ms"))
        assertTrue("mode visible: $s", s.contains("mode:normal"))
        assertTrue("engine reason visible: $s", s.contains("tts:azure_used"))
    }

    @Test fun summaryOmitsZeroTeardownMs() {
        val entry = DebugEntry().apply { sttFinal = "โทรหาแม่" }
        val s = entry.summary()
        // Non-media actions have no teardown to report — must not clutter the summary.
        assertTrue("no teardown when zero: $s", !s.contains("SCO⇩"))
    }
}

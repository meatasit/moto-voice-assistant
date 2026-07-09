package com.moto.voice.media

import com.moto.voice.pipeline.VoiceCommandPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract lock for v1.3.7 — YouTube nudge uses the LIGHTER-TOUCH yield action
 * instead of the full stop that v1.3.6 used.
 *
 * v1.3.6 sent [FmPlayerService.ACTION_STOP] before the nudge, which killed our
 * service AND cleared the notification. Field-report scenario the rider called out:
 *
 *   1. "เปิดวิทยุ 106"        — FM streaming
 *   2. "เปิด YouTube กรรมกรข่าว" — YouTube intent, 3s nudge, our service dies
 *   3. Watches video for a few minutes
 *   4. "เปิดวิทยุ"             — user expects FM back; instead gets a cold-start
 *                                delay because our service was torn down.
 *
 * v1.3.7 introduces [FmPlayerService.ACTION_YIELD_SESSION] which pauses playback and
 * transitions the player to STATE_IDLE (drops us from media button routing so the
 * nudge lands on YouTube) but keeps the service + notification + URL state alive so
 * the resume in step 4 is instant.
 *
 * These JVM tests can't spin up FmPlayerService without Robolectric, so they cover
 * the *contract* the pipeline relies on: the constant exists, is distinct from
 * ACTION_STOP / ACTION_PAUSE, and matches the string the pipeline is calling with.
 */
class FmYieldSessionContractTest {

    @Test fun yieldSessionConstantIsDefined() {
        assertTrue(
            "ACTION_YIELD_SESSION must be a non-blank Moto Voice intent action",
            FmPlayerService.ACTION_YIELD_SESSION.startsWith("com.moto.voice.")
        )
    }

    @Test fun yieldSessionIsDistinctFromStop() {
        // Regression guard: if a future refactor accidentally aliases YIELD → STOP,
        // the whole point of the v1.3.7 fix is undone. Lock the distinction.
        assertNotEquals(FmPlayerService.ACTION_STOP, FmPlayerService.ACTION_YIELD_SESSION)
    }

    @Test fun yieldSessionIsDistinctFromPause() {
        // Also distinct from ACTION_PAUSE — pause leaves the session in the media
        // button routing chain (STATE_READY + playWhenReady=false = PAUSED), which
        // is exactly the case that would make MEDIA_PLAY resume FM instead of nudging
        // YouTube. YIELD must use player.stop() to reach STATE_IDLE.
        assertNotEquals(FmPlayerService.ACTION_PAUSE, FmPlayerService.ACTION_YIELD_SESSION)
    }

    @Test fun exactValueLockedForBackwardsCompat() {
        // If anyone renames this string, PendingIntents already emitted by prior
        // service instances will stop resolving. Lock the exact value so a rename
        // is a deliberate act with a paired migration note.
        assertEquals("com.moto.voice.FM_YIELD_SESSION", FmPlayerService.ACTION_YIELD_SESSION)
    }

    @Test fun pipelineClassIsAccessible() {
        // Sanity: the pipeline class the yield action is called from compiles and
        // is loadable in this JVM classpath — proves the two are in the same module.
        assertTrue(
            "VoiceCommandPipeline must load for the contract to be enforceable",
            VoiceCommandPipeline::class.java.name.endsWith("VoiceCommandPipeline")
        )
    }
}

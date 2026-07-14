package com.moto.voice.pipeline

import android.media.session.PlaybackState
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.19 — lock the "pause every active controller" invariant.
 *
 * Field log 1783995471690: "ปิด YouTube" did nothing because pre-v1.3.19 code
 * filtered controllers to state == STATE_PLAYING. YouTube was in STATE_ERROR
 * with audio still flowing, missed the filter, and Spotify (the media-key
 * fallback target) got paused instead. Fix: pause everything, always.
 *
 * If a future refactor smuggles a state filter back in — e.g. re-adds
 * .filter { it.playbackState?.state == STATE_PLAYING } at the call site —
 * these tests should still pass at the shouldPauseAtState() level, but a
 * follow-up code review must catch that the invariant helper is being
 * bypassed. Comment [StopSequenceTargets] kdoc reinforces the contract.
 */
class StopSequenceTargetsTest {

    // ─── The specific STATE_ERROR case from the field report ────────────────

    @Test fun stateErrorMustPause() {
        assertTrue(
            "STATE_ERROR was the field-observed leaky case — MUST be pausable",
            StopSequenceTargets.shouldPauseAtState(PlaybackState.STATE_ERROR),
        )
    }

    // ─── Every PlaybackState.state value returns true ───────────────────────

    @Test fun allStatesArePauseTargets() {
        val failing = StopSequenceTargets.ALL_STATES.filter {
            !StopSequenceTargets.shouldPauseAtState(it)
        }
        assertTrue(
            "spec §1.3.19 — pause ALL controllers regardless of state. " +
                "These states failed the check: $failing",
            failing.isEmpty(),
        )
    }

    @Test fun stateNullIsPausable() {
        assertTrue(StopSequenceTargets.shouldPauseAtState(null))
    }

    @Test fun statePausedIsPausable() {
        // pause() on an already-paused session is a documented no-op — but we
        // MUST still call it (some vendor implementations reset internal state).
        assertTrue(StopSequenceTargets.shouldPauseAtState(PlaybackState.STATE_PAUSED))
    }

    @Test fun stateStoppedIsPausable() {
        assertTrue(StopSequenceTargets.shouldPauseAtState(PlaybackState.STATE_STOPPED))
    }

    @Test fun stateBufferingIsPausable() {
        // Buffering means the app is trying to play — stop is unambiguous, pause it too.
        assertTrue(StopSequenceTargets.shouldPauseAtState(PlaybackState.STATE_BUFFERING))
    }

    @Test fun statePlayingIsPausable() {
        // The obvious case — pre-v1.3.19 code only handled this one.
        assertTrue(StopSequenceTargets.shouldPauseAtState(PlaybackState.STATE_PLAYING))
    }

    @Test fun stateNoneIsPausable() {
        assertTrue(StopSequenceTargets.shouldPauseAtState(PlaybackState.STATE_NONE))
    }

    @Test fun stateConnectingIsPausable() {
        assertTrue(StopSequenceTargets.shouldPauseAtState(PlaybackState.STATE_CONNECTING))
    }

    @Test fun unknownStateIntIsPausable() {
        // Vendors sometimes report custom state ints we don't recognise.
        // Better to pause an unknown session than to leave audio flowing.
        assertTrue(StopSequenceTargets.shouldPauseAtState(9999))
        assertTrue(StopSequenceTargets.shouldPauseAtState(-1))
    }
}

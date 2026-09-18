package com.moto.voice.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.6 — a warm switch spends its first deep-link delivery on a known no-op.
 *
 * Field log 1789637279880, all four warm switches, identical shape:
 *
 * ```
 * openYoutube:VID;link→webTargeted;launch→fullScreenIntent;
 * nudge→sessionSeen(playing);nudge→refireSwitch(clearTask);
 * link→webTargeted;launch→fullScreenIntent;nudge→play#1;nudge→confirmed
 * ```
 *
 * Every one landed — and every one landed on the ESCALATION. The first delivery navigated
 * zero times out of four here, and zero times out of eleven in field log 1786104958601,
 * because a plain NEW_TASK intent handed to a task that is already running is brought
 * forward without the intent being delivered. `priorTitle` tells us at fire time which case
 * we are in, so the first delivery can carry CLEAR_TASK and save the rider
 * REFIRE_STILL_PRIOR_MS plus a wasted trampoline.
 *
 * A cold target keeps the plain intent: there is no task to clear, and tearing down a launch
 * that is only slow is exactly the mistake REFIRE_NO_SESSION_MS is written to avoid.
 */
class WarmSwitchRestartTest {

    private val prior = "GTA6 โดนแฮ็คมาโชว์แบบแปลกๆ"

    @Test fun playingWarmSwitchRestartsTheTask() = assertTrue(
        "YouTube is playing — a plain delivery is a proven no-op, CLEAR_TASK landed 2/2 in 1789697284287",
        MediaOrchestrator.firstFireNeedsRestart(prior, priorPlaying = true, behindSecureKeyguard = true),
    )

    @Test fun coldLaunchKeepsThePlainIntent() = assertFalse(
        "nothing to clear, and CLEAR_TASK on a slow cold start would tear down a working launch",
        MediaOrchestrator.firstFireNeedsRestart(null, priorPlaying = false, behindSecureKeyguard = true),
    )

    // ─── v1.4.8: the paused case ─────────────────────────────────────────────

    /**
     * Field log 1789697284287 — the rider paused YouTube from the helmet, then asked for a
     * different clip. Our CLEAR_TASK at that paused, keyguard-held task could not land AND
     * turned the session from paused to stopped, so his play button went dead too. 4 for 4.
     */
    @Test fun pausedBehindSecureKeyguardNeverRestarts() = assertFalse(
        "a restart cannot land here and destroys the session he could still resume",
        MediaOrchestrator.firstFireNeedsRestart(prior, priorPlaying = false, behindSecureKeyguard = true),
    )

    @Test fun pausedButUnlockedStillRestarts() = assertTrue(
        "unlocked, the restarted task can resume, so the restart is the fast path again",
        MediaOrchestrator.firstFireNeedsRestart(prior, priorPlaying = false, behindSecureKeyguard = false),
    )

    // ─── clearTaskCanLand, all four cells ────────────────────────────────────

    @Test fun clearTaskCanLandTruthTable() {
        assertTrue(MediaOrchestrator.clearTaskCanLand(targetPlaying = true, behindSecureKeyguard = true))
        assertTrue(MediaOrchestrator.clearTaskCanLand(targetPlaying = true, behindSecureKeyguard = false))
        assertTrue(MediaOrchestrator.clearTaskCanLand(targetPlaying = false, behindSecureKeyguard = false))
        assertFalse(
            "the one cell that is proven fatal",
            MediaOrchestrator.clearTaskCanLand(targetPlaying = false, behindSecureKeyguard = true),
        )
    }

    @Test fun bufferingCountsAsAlive() {
        assertTrue(MediaOrchestrator.isActivelyPlaying(android.media.session.PlaybackState.STATE_PLAYING))
        assertTrue(MediaOrchestrator.isActivelyPlaying(android.media.session.PlaybackState.STATE_BUFFERING))
        assertFalse(MediaOrchestrator.isActivelyPlaying(android.media.session.PlaybackState.STATE_PAUSED))
        assertFalse(MediaOrchestrator.isActivelyPlaying(android.media.session.PlaybackState.STATE_STOPPED))
        assertFalse(MediaOrchestrator.isActivelyPlaying(null))
    }
}

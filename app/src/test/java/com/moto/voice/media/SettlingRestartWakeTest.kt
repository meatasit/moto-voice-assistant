package com.moto.voice.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.13 — a CLEAR_TASK restart that sits `stopped` behind the secure keyguard is not
 * lost, it is waiting for one more launch. Until now the only thing sending that launch
 * was the rider's next command.
 *
 * Field log 1795045364488 (v1.4.12, helmet ride, locked, cellular), two pairs:
 *
 * ```
 * 1794999172458  prior: playing "Chill & Enjoy Music…"   want 53TJ1Zo-M1w "Start Your Day Right…"
 *   ops: …launch→fullScreenIntent;nudge→sessionSeen(stopped);nudge→restartSettling(stopped);
 *        nudge→launchBlocked(stillPrior)                       ← 15 s of silence, then "ลองสั่งอีกครั้ง"
 * 1794999213657  prior: stopped "Chill & Enjoy Music…"   want 53TJ1Zo-M1w (same clip, 41 s later)
 *   ops: …launch→fullScreenIntent;nudge→sessionSeen(stopped);nudge→confirmed
 *
 * 1794999294967  prior: playing "Start Your Day Right…"  want C_2rJ7_hFGY "Momos & Maggie…"
 *   ops: …nudge→sessionSeen(stopped);nudge→restartSettling(stopped);nudge→launchBlocked(stillPrior)
 * 1794999334044  prior: stopped "Start Your Day Right…"  want XGt1YZi9k7U "VIRAL PHONK…" (40 s later)
 *   ops: …launch→fullScreenIntent;nudge→sessionSeen(stopped);nudge→launchBlocked(wrongVideo)
 *   mediaActualTitle: "Momos & Maggie…"                        ← the PREVIOUS command's clip
 * ```
 *
 * The second pair is the decisive one. The follow-up launch asked for a different clip and
 * what started was the clip the stuck restart had loaded: the plain launch did not navigate,
 * it only woke the task. Field log 1794208929620 (entries 1794188420595 → 1794188497352) is
 * the same shape a third time. Three stuck restarts, three woken by the next plain launch,
 * none by waiting.
 *
 * So the nudge sends that plain launch itself, once, part-way through the settle window.
 * Whether it lands as fast as the rider's own retry did is the Acceptance Suite's to
 * confirm; the op log names it (`nudge→wakeRestart(plain)@<ms>`) so the next ride says.
 */
class SettlingRestartWakeTest {

    private val wakeAt = 10_000L

    @Test fun aRestartInFlightIsLeftAloneFirst() = assertFalse(
        "entries 1794999406302 and 1794999579885 landed on their own; do not launch on top of those",
        MediaOrchestrator.shouldWakeSettlingRestart(
            alreadyWoken = false, nowMs = wakeAt - 1L, wakeAt = wakeAt, haveLinkTarget = true,
        ),
    )

    @Test fun aRestartStillStoppedAtWakeTimeGetsOnePlainLaunch() = assertTrue(
        "entries 1794999172458 / 1794999294967 sat stopped for the whole window; the next plain launch started them",
        MediaOrchestrator.shouldWakeSettlingRestart(
            alreadyWoken = false, nowMs = wakeAt, wakeAt = wakeAt, haveLinkTarget = true,
        ),
    )

    @Test fun onlyOneWakePerLaunch() = assertFalse(
        "a second trampoline every 500 ms would be the re-fire war v1.3.36 already paid for",
        MediaOrchestrator.shouldWakeSettlingRestart(
            alreadyWoken = true, nowMs = wakeAt + 5_000L, wakeAt = wakeAt, haveLinkTarget = true,
        ),
    )

    @Test fun nothingToLaunchMeansNoWake() = assertFalse(
        MediaOrchestrator.shouldWakeSettlingRestart(
            alreadyWoken = false, nowMs = wakeAt + 1L, wakeAt = wakeAt, haveLinkTarget = false,
        ),
    )

    /**
     * The wake belongs to our own restart only. A clip the RIDER paused (v1.4.8, field log
     * 1789697284287) never reaches it: [MediaOrchestrator.pausedPriorAction] sends that shape
     * to GiveUpNeedsUnlock, and a plain launch there would resume the old clip he paused.
     */
    @Test fun aRiderPausedClipNeverReachesTheWake() = assertTrue(
        MediaOrchestrator.pausedPriorAction(
            firstFireRestarted = false, nowMs = wakeAt, refireAt = wakeAt - 3_500L, windowExhausted = false,
        ) == MediaOrchestrator.PausedPriorAction.GiveUpNeedsUnlock,
    )
}

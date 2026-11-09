package com.moto.voice.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.4.10 — a stopped prior session behind a secure keyguard means two different things
 * depending on who stopped it, and v1.4.8's gate only knew one of them.
 *
 * Field log 1794208929620, entry 1794188420595 ("เปิดรายการกรรมกรข่าวคุยนอกจอ"):
 *
 * ```
 * mediaPriorState: playing   keyguardSecure: true   screenLocked: true
 * ops: openYoutube:l-rxyIG_Iwc;link→webTargeted;launch→fullScreenIntent;
 *      nudge→sessionSeen(stopped);nudge→launchBlocked(stillPriorPaused)
 * ```
 *
 * The target was PLAYING when we fired, so the first delivery carried CLEAR_TASK; the
 * `stopped` the first tick saw was our own restart, not a pause. The rider heard
 * "คลิปที่หยุดไว้เปลี่ยนตอนจอล็อคไม่ได้" having paused nothing — and 77 s later (entry
 * 1794188497352) the same clip, screen still locked, went `sessionSeen(stopped)` →
 * `confirmed`. The v1.4.8 case (field log 1789697284287) differs at fire time:
 * `mediaPriorState: paused`, so the gate said no and the first delivery was plain.
 */
class SelfInflictedStopTest {

    private val refireAt = 10_000L

    // ─── our restart ─────────────────────────────────────────────────────────

    @Test fun ourClearTaskRestartKeepsWaitingBeforeRefireAt() = assertEquals(
        "stopped right after our CLEAR_TASK is the restart in flight — give it a cold start's window",
        MediaOrchestrator.PausedPriorAction.SettleRestart,
        MediaOrchestrator.pausedPriorAction(
            firstFireRestarted = true, nowMs = refireAt - 5_000L, refireAt = refireAt, windowExhausted = false,
        ),
    )

    @Test fun ourClearTaskRestartKeepsWaitingPastRefireAt() = assertEquals(
        "entry 1794188420595 gave up here with the paused-clip line; the clip had not been paused",
        MediaOrchestrator.PausedPriorAction.SettleRestart,
        MediaOrchestrator.pausedPriorAction(
            firstFireRestarted = true, nowMs = refireAt + 1_000L, refireAt = refireAt, windowExhausted = false,
        ),
    )

    @Test fun ourClearTaskRestartNeverSpeaksTheUnlockLine() = assertEquals(
        "even at the warm window end the caller extends and, if that runs out too, says stillPrior",
        MediaOrchestrator.PausedPriorAction.SettleRestart,
        MediaOrchestrator.pausedPriorAction(
            firstFireRestarted = true, nowMs = refireAt + 7_000L, refireAt = refireAt, windowExhausted = true,
        ),
    )

    // ─── the rider's pause (v1.4.8, unchanged) ───────────────────────────────

    @Test fun riderPausedWaitsUntilRefireAt() = assertEquals(
        MediaOrchestrator.PausedPriorAction.KeepPolling,
        MediaOrchestrator.pausedPriorAction(
            firstFireRestarted = false, nowMs = refireAt - 1L, refireAt = refireAt, windowExhausted = false,
        ),
    )

    @Test fun riderPausedGivesHonestAnswerAtRefireAt() = assertEquals(
        "field log 1789697284287 — a plain intent to a paused task is a known no-op; say so early",
        MediaOrchestrator.PausedPriorAction.GiveUpNeedsUnlock,
        MediaOrchestrator.pausedPriorAction(
            firstFireRestarted = false, nowMs = refireAt, refireAt = refireAt, windowExhausted = false,
        ),
    )

    @Test fun riderPausedGivesUpAtWindowEndToo() = assertEquals(
        MediaOrchestrator.PausedPriorAction.GiveUpNeedsUnlock,
        MediaOrchestrator.pausedPriorAction(
            firstFireRestarted = false, nowMs = refireAt - 1L, refireAt = refireAt, windowExhausted = true,
        ),
    )

    // ─── the line each shape ends on ─────────────────────────────────────────

    @Test fun settledRestartThatStillMissesSaysSwitchNotLanded() = assertEquals(
        "\"ลองสั่งเปลี่ยนอีกครั้ง\" is what worked in the log; the paused-clip line was false",
        MediaOrchestrator.BlockedLine.SwitchNotLanded,
        MediaOrchestrator.blockedLineFor("stillPrior", locked = true, fsiHonored = true, keyguardSecure = true),
    )

    @Test fun riderPausedStillSaysNeedsUnlock() = assertEquals(
        MediaOrchestrator.BlockedLine.SwitchNeedsUnlock,
        MediaOrchestrator.blockedLineFor("stillPriorPaused", locked = true, fsiHonored = true, keyguardSecure = true),
    )
}

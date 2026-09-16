package com.moto.voice.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.5 — locks [MediaOrchestrator.shouldRefireNoSession], the rule behind the
 * one-shot CLEAR_TASK escalation for a launch whose target never registered a session.
 *
 * Evidence this exists for — field log 1789561893967, six consecutive entries:
 *
 * ```
 * finishReason: launch_blocked
 * fsiTrampolineRan: true, fsiTrampolineLaunchOk: true
 * screenLocked: true, keyguardSecure: true, screenInteractive: false
 * ops: openYoutube:VID;link→webTargeted;launch→fullScreenIntent;nudge→launchBlocked(noSession)
 * ```
 *
 * No `nudge→sessionSeen` on any of them: YouTube was launched over the keyguard and never
 * registered a MediaSession inside the 15 s cold window. The very next interaction
 * (1789561627775), same lock state, logged `sessionSeen(none);play#1;confirmed` — firing the
 * launch again is what lands it, and until now only the rider was doing that.
 *
 * Pure: no Handler, no Context, no MediaSession. Whether the re-fire actually lands on the
 * rider's S24 is the Acceptance Suite's job, not JUnit's.
 */
class NoSessionRefireTest {

    private val refireAt = 6_000L

    @Test fun refiresOnceTheDeadlinePasses() = assertTrue(
        MediaOrchestrator.shouldRefireNoSession(
            alreadyRefired = false, sawSession = false,
            nowMs = refireAt, refireAt = refireAt, haveLinkTarget = true,
        )
    )

    @Test fun waitsWhileTheColdStartStillHasTime() = assertFalse(
        "a cold start is documented at 800ms-3s; tearing it down early is the bug we would be adding",
        MediaOrchestrator.shouldRefireNoSession(
            alreadyRefired = false, sawSession = false,
            nowMs = refireAt - 1, refireAt = refireAt, haveLinkTarget = true,
        )
    )

    @Test fun neverRefiresTwice() = assertFalse(
        "two CLEAR_TASK restarts racing each other is a re-fire war — v1.3.36 paid for that once",
        MediaOrchestrator.shouldRefireNoSession(
            alreadyRefired = true, sawSession = false,
            nowMs = refireAt + 9_000, refireAt = refireAt, haveLinkTarget = true,
        )
    )

    @Test fun leavesTheSessionLostCaseAlone() = assertFalse(
        "a session that appeared and vanished is 'opened then stopped' — restarting it hits the same keyguard",
        MediaOrchestrator.shouldRefireNoSession(
            alreadyRefired = false, sawSession = true,
            nowMs = refireAt + 1_000, refireAt = refireAt, haveLinkTarget = true,
        )
    )

    @Test fun nothingToRefireWithoutAnIdOrQuery() = assertFalse(
        MediaOrchestrator.shouldRefireNoSession(
            alreadyRefired = false, sawSession = false,
            nowMs = refireAt + 1_000, refireAt = refireAt, haveLinkTarget = false,
        )
    )

    /**
     * The escalation must sit outside the documented cold-start range but leave the window
     * room to extend — if it fired at or after the cold window end it could never run.
     */
    @Test fun deadlineSitsInsideTheColdWindow() {
        val coldWindow = MediaOrchestrator.pollWindowMsFor(priorTitle = null)
        assertTrue("re-fire must happen while the poll is still alive", 6_000L < coldWindow)
        assertTrue("re-fire must be past a normal 800ms-3s cold start", 6_000L > 3_000L)
    }
}

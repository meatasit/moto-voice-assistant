package com.moto.voice.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.36 — locks the two decisions that made the rider distrust what the assistant said
 * about a failed launch, both taken from field logs 1786072158662 / 1786104958601.
 *
 * 1. **Never say "ปลดล็อคก่อน" when the full-screen intent was honored.** The locked opens
 *    that failed in the field had `fsiTrampolineRan=true` + `fsiTrampolineLaunchOk=true` —
 *    YouTube WAS launched over the keyguard, the session was just slow. Telling the rider
 *    to unlock is advice he can hear is wrong, and it was on his own bug list.
 *
 * 2. **A cold target gets a longer window than a warm switch.** Three separate `noSession`
 *    blocks happened with `mediaCtrlPkgMiss=none` (nothing else playing at all) inside the
 *    old 9s window — a cold YouTube start over the lock screen simply takes longer.
 */
class LaunchBlockedContractTest {

    // ─── Which line a blocked launch speaks ──────────────────────────────────

    @Test fun stillPriorAlwaysSaysSwitchNotLanded() {
        // The app IS open and audible on the old clip — "can't open" would contradict what
        // the rider hears. True regardless of lock state or FSI.
        for (locked in listOf(true, false)) {
            for (fsi in listOf(true, false)) {
                assertEquals(
                    "stillPrior locked=$locked fsi=$fsi",
                    MediaOrchestrator.BlockedLine.SwitchNotLanded,
                    MediaOrchestrator.blockedLineFor("stillPrior", locked, fsi),
                )
            }
        }
    }

    @Test fun lockedWithFsiHonoredDoesNotTellRiderToUnlock() {
        assertEquals(
            MediaOrchestrator.BlockedLine.NoSession,
            MediaOrchestrator.blockedLineFor("noSession", locked = true, fsiHonored = true),
        )
    }

    @Test fun lockedWithoutFsiKeepsTheUnlockAdvice() {
        // No full-screen-intent path taken → the keyguard genuinely is the obstacle, and
        // unlocking really is what fixes it (acceptance scenario C-denied).
        assertEquals(
            MediaOrchestrator.BlockedLine.LockedNoFsi,
            MediaOrchestrator.blockedLineFor("noSession", locked = true, fsiHonored = false),
        )
    }

    @Test fun unlockedNeverGetsTheLockedLine() {
        for (fsi in listOf(true, false)) {
            assertEquals(
                MediaOrchestrator.BlockedLine.NoSession,
                MediaOrchestrator.blockedLineFor("noSession", locked = false, fsiHonored = fsi),
            )
        }
    }

    // ─── How long we wait before declaring failure ───────────────────────────

    @Test fun coldTargetWaitsLongerThanWarmSwitch() {
        val cold = MediaOrchestrator.pollWindowMsFor(priorTitle = null)
        val warm = MediaOrchestrator.pollWindowMsFor(priorTitle = "crazy chill song playlist")
        assertTrue("cold ($cold) must exceed warm ($warm)", cold > warm)
    }

    @Test fun coldWindowCoversTheObservedFailures() {
        // The field failures declared blocked at ~9.8s (800ms initial delay + 9s window)
        // while the launch itself had succeeded. Anything at or under that is no fix.
        assertTrue(MediaOrchestrator.pollWindowMsFor(priorTitle = null) > 9_000L)
    }

    @Test fun warmSwitchStaysPrompt() {
        // A genuine failed switch must still be reported quickly — the rider is riding.
        assertTrue(MediaOrchestrator.pollWindowMsFor(priorTitle = "anything") <= 9_000L)
    }
}

package com.moto.voice.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.36 — locks the two decisions that made the rider distrust what the assistant said
 * about a failed launch, both taken from field logs 1786072158662 / 1786104958601.
 *
 * 1. ~~**Never say "ปลดล็อคก่อน" when the full-screen intent was honored.**~~ **Reversed in
 *    v1.4.7 for `noSession` behind a SECURE keyguard.** v1.3.36 *inferred* from
 *    `fsiTrampolineLaunchOk=true` that the launch had worked and the session was merely slow,
 *    so unlocking could not have helped. Field log 1789649814596 replaced that inference with
 *    an observation: three `noSession` blocks with the FSI honored, and the rider reported
 *    that **unlocking the screen made YouTube start playing by itself**. The video was loaded
 *    and waiting the whole time; YouTube just will not start its player behind a secure
 *    keyguard. So "ลองสั่งใหม่อีกครั้ง" is advice that provably cannot work (three for three),
 *    and "ปลดล็อคก่อน" is what the rider had already found does.
 *
 *    v1.3.36's actual complaint is still honored: it was about being told to unlock while
 *    audio was audibly playing. That is `stillPrior` / `sessionLost`, and both keep their own
 *    lines below, unchanged and unconditional.
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

    @Test fun secureKeyguardNoSessionSaysItIsWaitingForAnUnlock() {
        // v1.4.7, field log 1789649814596 — the FSI was honored, the launch fired twice, and
        // YouTube still never registered a session. The rider unlocked and it played, so it
        // WAS open: "เปิดไม่ได้" would be false and "ลองสั่งใหม่" cannot work.
        assertEquals(
            MediaOrchestrator.BlockedLine.WaitingForUnlock,
            MediaOrchestrator.blockedLineFor(
                "noSession", locked = true, fsiHonored = true, keyguardSecure = true,
            ),
        )
    }

    @Test fun lockedWithoutFsiSaysTheLaunchItselfWasBlocked() {
        // No full-screen-intent path taken → the keyguard stopped the INTENT, so nothing is
        // open and "เปิดไม่ได้ตอนจอล็อค" is literally true (acceptance scenario C-denied).
        // Distinct from the case above, where YouTube is open and merely paused.
        assertEquals(
            MediaOrchestrator.BlockedLine.LockedNoFsi,
            MediaOrchestrator.blockedLineFor("noSession", locked = true, fsiHonored = false),
        )
    }

    @Test fun theTwoLockedCasesAreNotTheSameLine() {
        // They are different facts — one never opened, the other is open and waiting — and
        // the rider can tell which is which by unlocking.
        assertTrue(
            MediaOrchestrator.blockedLineFor("noSession", true, fsiHonored = false) !=
                MediaOrchestrator.blockedLineFor("noSession", true, true, keyguardSecure = true),
        )
    }

    @Test fun nonSecureKeyguardWithFsiHonoredStillDoesNotSayUnlock() {
        // A swipe keyguard IS dismissed by the trampoline's requestDismissKeyguard, so it is
        // not the obstacle and telling the rider to unlock would be the wrong advice again.
        assertEquals(
            MediaOrchestrator.BlockedLine.NoSession,
            MediaOrchestrator.blockedLineFor(
                "noSession", locked = true, fsiHonored = true, keyguardSecure = false,
            ),
        )
    }

    @Test fun audibleCasesNeverSayUnlockHoweverSecureTheKeyguard() {
        // v1.3.36's real complaint: something IS playing, so "can't open, unlock first"
        // contradicts what the rider hears. Unchanged by the v1.4.7 reversal.
        for (reason in listOf("stillPrior", "wrongVideo", "sessionLost")) {
            val line = MediaOrchestrator.blockedLineFor(
                reason, locked = true, fsiHonored = true, keyguardSecure = true,
            )
            assertTrue(
                "$reason must not send the rider to unlock",
                line != MediaOrchestrator.BlockedLine.WaitingForUnlock &&
                    line != MediaOrchestrator.BlockedLine.LockedNoFsi,
            )
        }
    }

    @Test fun unlockedNeverGetsTheLockedLine() {
        for (fsi in listOf(true, false)) {
            for (secure in listOf(true, false)) {
                assertEquals(
                    "unlocked fsi=$fsi secure=$secure — a locked-screen line would be nonsense",
                    MediaOrchestrator.BlockedLine.NoSession,
                    MediaOrchestrator.blockedLineFor(
                        "noSession", locked = false, fsiHonored = fsi, keyguardSecure = secure,
                    ),
                )
            }
        }
    }

    @Test fun sessionLostIsItsOwnLine() {
        // v1.4.2 — field log 1789518388540: YouTube started the right video, then its
        // session vanished before the window closed. "Couldn't open" was false; the rider
        // unlocked and found it open. Regardless of lock / FSI state this must say so.
        for (locked in listOf(true, false)) {
            for (fsi in listOf(true, false)) {
                assertEquals(
                    "sessionLost locked=$locked fsi=$fsi",
                    MediaOrchestrator.BlockedLine.SessionLost,
                    MediaOrchestrator.blockedLineFor("sessionLost", locked, fsi),
                )
            }
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

    // ─── v1.4.8: a paused switch behind a secure keyguard ────────────────────

    @Test fun pausedSwitchBehindSecureKeyguardHasItsOwnLine() {
        // Field log 1789697284287 — paused from the helmet, then four switch attempts, all
        // stillPrior. "ลองสั่งเปลี่ยนอีกครั้ง" cannot work; play-to-resume or unlock can.
        assertEquals(
            MediaOrchestrator.BlockedLine.SwitchNeedsUnlock,
            MediaOrchestrator.blockedLineFor(
                "stillPriorPaused", locked = true, fsiHonored = true, keyguardSecure = true,
            ),
        )
    }

    @Test fun playingStillPriorKeepsSwitchNotLanded() {
        // Something IS audible: the old clip. Unchanged.
        assertEquals(
            MediaOrchestrator.BlockedLine.SwitchNotLanded,
            MediaOrchestrator.blockedLineFor(
                "stillPrior", locked = true, fsiHonored = true, keyguardSecure = true,
            ),
        )
    }
}

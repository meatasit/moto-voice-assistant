package com.moto.voice.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.3.41 — "playing" is not "playing what was asked for".
 *
 * Field log 1787294052224: the rider asked for กรรมกรข่าว and the log says
 * `nudge→confirmed` while `got:` is the instrumental music he had asked for two commands
 * earlier — a CLEAR_TASK restart that landed after its own window had closed. The old rule
 * confirmed on any verdict except UNKNOWN, and SWITCHED only means "the title moved away
 * from the prior one", so a late arrival from a previous command reads as success for the
 * current one.
 */
class PlayingDecisionTest {

    private fun decide(
        verdict: YoutubeVerify.Verdict,
        expectedKnown: Boolean = true,
        windowExhausted: Boolean = false,
    ) = MediaOrchestrator.playingDecision(verdict, expectedKnown, windowExhausted)

    @Test fun exactTargetConfirmsImmediately() {
        assertEquals(
            MediaOrchestrator.PlayingDecision.Confirm,
            decide(YoutubeVerify.Verdict.CONFIRMED_TARGET),
        )
    }

    @Test fun exactTargetConfirmsEvenAtTheWindowEdge() {
        assertEquals(
            MediaOrchestrator.PlayingDecision.Confirm,
            decide(YoutubeVerify.Verdict.CONFIRMED_TARGET, windowExhausted = true),
        )
    }

    @Test fun switchedWithNoTargetIsTheBestWeCanKnow() {
        // "อันต่อไป" / "เปลี่ยนคลิป" — no expected title to check against.
        assertEquals(
            MediaOrchestrator.PlayingDecision.Confirm,
            decide(YoutubeVerify.Verdict.SWITCHED, expectedKnown = false),
        )
    }

    @Test fun switchedToSomethingElseKeepsPollingWhileThereIsTime() {
        // Ours may still be loading — don't declare either way yet.
        assertEquals(
            MediaOrchestrator.PlayingDecision.KeepPolling,
            decide(YoutubeVerify.Verdict.SWITCHED, expectedKnown = true),
        )
    }

    @Test fun switchedToSomethingElseIsNeverConfirmed() {
        // The bug, locked: a known target that never showed up must not report success.
        assertEquals(
            MediaOrchestrator.PlayingDecision.WrongVideo,
            decide(YoutubeVerify.Verdict.SWITCHED, expectedKnown = true, windowExhausted = true),
        )
    }

    @Test fun noTitleYetKeepsPolling() {
        assertEquals(
            MediaOrchestrator.PlayingDecision.KeepPolling,
            decide(YoutubeVerify.Verdict.UNKNOWN),
        )
    }

    @Test fun noTitleAtTheEdgeIsAcceptedRatherThanFalseBlocked() {
        // Audio IS playing and YouTube simply never published a title.
        assertEquals(
            MediaOrchestrator.PlayingDecision.Confirm,
            decide(YoutubeVerify.Verdict.UNKNOWN, windowExhausted = true),
        )
    }

    @Test fun wrongVideoSpeaksTheSameLineAsAFailedSwitch() {
        // Something IS audible, it just isn't what was asked for — "can't open" would
        // contradict what the rider can hear.
        assertEquals(
            MediaOrchestrator.BlockedLine.SwitchNotLanded,
            MediaOrchestrator.blockedLineFor("wrongVideo", locked = true, fsiHonored = true),
        )
    }
}

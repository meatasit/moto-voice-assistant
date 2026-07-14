package com.moto.voice.pipeline

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * v1.3.19 — locks the YouTube nudge state machine after the field log
 * 1783995471690 regression. Old code passively watched for 5s and tried
 * controller.play() once at the very end; new code actively retries up to 3
 * times spaced ≥1500ms apart, gated on the session being settled + stuck.
 *
 * Test-only timing baseline (all times in ms, pollStart = 0):
 *   settleMs = 2000, retrySpacing = 1500, windowEnd = 9000, maxAttempts = 3.
 * Real production constants are passed by the caller — the decider is pure.
 */
class NudgeDeciderTest {

    private val SETTLE = 2_000L
    private val SPACING = 1_500L
    private val WINDOW_END = 9_000L
    private val MAX_ATTEMPTS = 3

    private fun decide(
        state: Int?,
        nowMs: Long,
        attempts: Int = 0,
        lastAttemptAt: Long = 0L,
    ): NudgeDecider.Action = NudgeDecider.decide(
        state = state,
        nowMs = nowMs,
        pollStartAt = 0L,
        pollWindowEndAt = WINDOW_END,
        playAttempts = attempts,
        lastPlayAttemptAt = lastAttemptAt,
        maxAttempts = MAX_ATTEMPTS,
        settleMs = SETTLE,
        retrySpacingMs = SPACING,
    )

    // ─── (ก) STATE_ERROR / STATE_PAUSED → play() within settle+first tick ───

    @Test fun errorStateAfterSettleFiresPlayNow() {
        // t = SETTLE — first eligible tick (settled becomes true at ≥ SETTLE)
        assertSame(NudgeDecider.Action.PlayNow, decide(PlaybackState.STATE_ERROR, SETTLE))
    }

    @Test fun pausedStateAfterSettleFiresPlayNow() {
        assertSame(NudgeDecider.Action.PlayNow, decide(PlaybackState.STATE_PAUSED, SETTLE))
    }

    @Test fun stoppedStateAfterSettleFiresPlayNow() {
        assertSame(NudgeDecider.Action.PlayNow, decide(PlaybackState.STATE_STOPPED, SETTLE))
    }

    @Test fun noneStateAfterSettleFiresPlayNow() {
        assertSame(NudgeDecider.Action.PlayNow, decide(PlaybackState.STATE_NONE, SETTLE))
    }

    @Test fun nullStateAfterSettleFiresPlayNow() {
        assertSame(NudgeDecider.Action.PlayNow, decide(null, SETTLE))
    }

    // ─── Before settle: stuck states must still KeepPolling ─────────────────

    @Test fun errorBeforeSettleKeepPolling() {
        // Deep-link needs a beat to hand off to YouTube — never nudge before settle.
        assertSame(NudgeDecider.Action.KeepPolling, decide(PlaybackState.STATE_ERROR, SETTLE - 100))
    }

    @Test fun pausedBeforeSettleKeepPolling() {
        assertSame(NudgeDecider.Action.KeepPolling, decide(PlaybackState.STATE_PAUSED, 500))
    }

    // ─── (ข) BUFFERING and other progress states → KeepPolling, never PlayNow ───

    @Test fun bufferingAfterSettleKeepPolling() {
        // BUFFERING is progress — the app is loading. Don't hammer with more play().
        assertSame(NudgeDecider.Action.KeepPolling, decide(PlaybackState.STATE_BUFFERING, SETTLE + 100))
    }

    @Test fun bufferingWithPreviousAttemptStillKeepPolling() {
        // Even if we already called play() once, BUFFERING means "app is working on it".
        assertSame(
            NudgeDecider.Action.KeepPolling,
            decide(PlaybackState.STATE_BUFFERING, SETTLE + SPACING + 500, attempts = 1, lastAttemptAt = SETTLE),
        )
    }

    @Test fun connectingKeepPolling() {
        assertSame(NudgeDecider.Action.KeepPolling, decide(PlaybackState.STATE_CONNECTING, SETTLE + 100))
    }

    @Test fun fastForwardingKeepPolling() {
        assertSame(NudgeDecider.Action.KeepPolling, decide(PlaybackState.STATE_FAST_FORWARDING, SETTLE + 100))
    }

    @Test fun skippingKeepPolling() {
        assertSame(NudgeDecider.Action.KeepPolling, decide(PlaybackState.STATE_SKIPPING_TO_NEXT, SETTLE + 100))
    }

    // ─── Retry cap ≤ 3 ──────────────────────────────────────────────────────

    @Test fun attemptOneAllowed() {
        assertSame(NudgeDecider.Action.PlayNow, decide(PlaybackState.STATE_PAUSED, SETTLE, attempts = 0))
    }

    @Test fun attemptTwoAllowed() {
        // Second attempt: attempts=1, spaced retrySpacing after first
        assertSame(
            NudgeDecider.Action.PlayNow,
            decide(PlaybackState.STATE_PAUSED, SETTLE + SPACING, attempts = 1, lastAttemptAt = SETTLE),
        )
    }

    @Test fun attemptThreeAllowed() {
        // Third attempt: attempts=2, spaced 2x retrySpacing after first
        val now = SETTLE + 2 * SPACING
        assertSame(
            NudgeDecider.Action.PlayNow,
            decide(PlaybackState.STATE_PAUSED, now, attempts = 2, lastAttemptAt = SETTLE + SPACING),
        )
    }

    @Test fun attemptFourBlocked() {
        // Fourth attempt exceeds MAX_ATTEMPTS = 3. If window is not yet exhausted,
        // keep polling; if window is exhausted, give up. Test both.
        val nowInWindow = SETTLE + 3 * SPACING
        assertSame(
            NudgeDecider.Action.KeepPolling,
            decide(PlaybackState.STATE_PAUSED, nowInWindow, attempts = 3, lastAttemptAt = nowInWindow - SPACING),
        )
    }

    @Test fun retryCapExactlyMaxAttempts() {
        // Guard: MAX_ATTEMPTS constant is 3. If a future edit bumps it, this test
        // records the intent so the change is deliberate. The nudge fires exactly
        // this many play() calls at most per window.
        assertEquals(3, MAX_ATTEMPTS)
    }

    // ─── Retry spacing enforced ─────────────────────────────────────────────

    @Test fun consecutiveAttemptsTooCloseBlocked() {
        // Two attempts spaced only 500ms apart (< SPACING = 1500ms) — the second
        // must NOT fire even though the state is still stuck.
        assertSame(
            NudgeDecider.Action.KeepPolling,
            decide(PlaybackState.STATE_ERROR, SETTLE + 500, attempts = 1, lastAttemptAt = SETTLE),
        )
    }

    @Test fun spacingAtExactBoundaryAllowsAttempt() {
        // At exactly SPACING ms after the last attempt, next play() fires.
        assertSame(
            NudgeDecider.Action.PlayNow,
            decide(PlaybackState.STATE_ERROR, SETTLE + SPACING, attempts = 1, lastAttemptAt = SETTLE),
        )
    }

    // ─── Window exhaustion → GiveUp ─────────────────────────────────────────

    @Test fun windowExhaustedStuckGivesUp() {
        // At the window boundary with retry cap already hit, we can't play more →
        // GiveUp so the caller speaks MEDIA_OPENED_NOT_PLAYING.
        assertSame(
            NudgeDecider.Action.GiveUp,
            decide(PlaybackState.STATE_PAUSED, WINDOW_END, attempts = 3, lastAttemptAt = WINDOW_END - 1500),
        )
    }

    @Test fun windowExhaustedBufferingGivesUp() {
        // Even in BUFFERING (progress), if the window is exhausted without ever
        // reaching PLAYING, we tell the rider to tap the phone.
        assertSame(
            NudgeDecider.Action.GiveUp,
            decide(PlaybackState.STATE_BUFFERING, WINDOW_END),
        )
    }

    @Test fun windowNotExhaustedBeforeGiveUp() {
        // Just below window end, still KeepPolling for buffering.
        assertSame(
            NudgeDecider.Action.KeepPolling,
            decide(PlaybackState.STATE_BUFFERING, WINDOW_END - 1),
        )
    }

    // ─── STATE_PLAYING is caller's responsibility ───────────────────────────

    @Test fun playingStateNotHandledByDecider() {
        // STATE_PLAYING is filtered out BEFORE the decider call site — pipeline
        // does the metadata mediaId verification there. If somehow it reaches
        // the decider, "stuck" is false (PLAYING isn't in the stuck set) so we
        // just KeepPolling until window exhaustion. Test locks that we don't
        // false-positive PlayNow on a playing session.
        assertSame(NudgeDecider.Action.KeepPolling, decide(PlaybackState.STATE_PLAYING, SETTLE))
    }
}

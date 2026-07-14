package com.moto.voice.pipeline

import android.media.session.PlaybackState

/**
 * v1.3.19 — pure decision function for the YouTube nudge poll loop.
 *
 * Extracted from [VoiceCommandPipeline.scheduleYoutubeNudgeWithController] so
 * JVM unit tests can lock the state-machine transitions without needing a real
 * MediaController / Handler / Context.
 *
 * The nudge design after v1.3.19:
 *   * Poll YouTube's controller every [YOUTUBE_NUDGE_POLL_INTERVAL_MS] within
 *     a [YOUTUBE_NUDGE_POLL_WINDOW_MS] window.
 *   * Wait [YOUTUBE_NUDGE_SETTLE_MS] for the deep-link to hand off to YouTube
 *     before the first play() attempt.
 *   * If the session reports a non-playing / non-buffering state after settle,
 *     call [android.media.session.MediaController.TransportControls.play] —
 *     the same call the "เล่นต่อ" (seek 0) intercept uses, which the field
 *     rider confirmed works every time. Up to [YOUTUBE_NUDGE_MAX_PLAY_ATTEMPTS]
 *     attempts, spaced by [YOUTUBE_NUDGE_PLAY_RETRY_SPACING_MS].
 *   * STATE_BUFFERING is progress — keep polling, don't re-nudge.
 *   * Window exhausted → GiveUp → speak MEDIA_OPENED_NOT_PLAYING.
 */
internal object NudgeDecider {

    /**
     * What the caller should do this poll tick. Deliberately NOT combined with the
     * "STATE_PLAYING confirmed" branch — that has its own metadata verification
     * flow above the decider call site.
     */
    sealed class Action {
        /** Wait one interval, poll again. Includes STATE_BUFFERING (progress). */
        object KeepPolling : Action()
        /**
         * Fire [android.media.session.MediaController.TransportControls.play] now.
         * Caller increments attempts and records lastPlayAttemptAt = nowMs.
         */
        object PlayNow : Action()
        /** Window exhausted — speak MEDIA_OPENED_NOT_PLAYING and stop polling. */
        object GiveUp : Action()
    }

    /**
     * @param state PlaybackState.state int from the controller (or null if the
     *   controller has no playback state yet). Note: STATE_PLAYING is handled
     *   by the caller BEFORE calling into the decider — this function only
     *   receives non-playing states.
     * @param nowMs current time in ms (SystemClock.uptimeMillis equivalent).
     * @param pollStartAt when the poll actually began (nowMs at first tick).
     * @param pollWindowEndAt hard deadline; nowMs ≥ this = give up.
     * @param playAttempts how many play() calls have already fired this window.
     * @param lastPlayAttemptAt nowMs of the previous play() call (0 = never).
     * @param maxAttempts cap on total play() attempts per window.
     * @param settleMs how long after poll start we must wait before the FIRST
     *   play() attempt — deep link needs a beat to hand off to YouTube.
     * @param retrySpacingMs min gap between successive play() attempts. Gives
     *   the app time to transition through BUFFERING before we re-nudge.
     */
    fun decide(
        state: Int?,
        nowMs: Long,
        pollStartAt: Long,
        pollWindowEndAt: Long,
        playAttempts: Int,
        lastPlayAttemptAt: Long,
        maxAttempts: Int,
        settleMs: Long,
        retrySpacingMs: Long,
    ): Action {
        // BUFFERING is progress — the app is loading. Keep polling; don't
        // hammer it with more play() calls that would just reset the buffer.
        // (STATE_FAST_FORWARDING / REWINDING / SKIPPING treated as progress too
        // — they mean the session IS transitioning under external control.)
        if (state == PlaybackState.STATE_BUFFERING ||
            state == PlaybackState.STATE_CONNECTING ||
            state == PlaybackState.STATE_FAST_FORWARDING ||
            state == PlaybackState.STATE_REWINDING ||
            state == PlaybackState.STATE_SKIPPING_TO_NEXT ||
            state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS
        ) {
            return if (nowMs >= pollWindowEndAt) Action.GiveUp else Action.KeepPolling
        }

        val settled = nowMs >= pollStartAt + settleMs
        val stuck = state == PlaybackState.STATE_PAUSED ||
                    state == PlaybackState.STATE_STOPPED ||
                    state == PlaybackState.STATE_ERROR ||
                    state == PlaybackState.STATE_NONE ||
                    state == null

        if (settled && stuck && playAttempts < maxAttempts) {
            val sinceLastAttempt = nowMs - lastPlayAttemptAt
            if (sinceLastAttempt >= retrySpacingMs) {
                return Action.PlayNow
            }
        }
        if (nowMs >= pollWindowEndAt) return Action.GiveUp
        return Action.KeepPolling
    }
}

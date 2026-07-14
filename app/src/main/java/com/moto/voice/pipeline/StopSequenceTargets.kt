package com.moto.voice.pipeline

import android.media.session.PlaybackState

/**
 * v1.3.19 — invariant lock for the "stop" command target selection.
 *
 * Field log 1783995471690: "ปิด YouTube" repeatedly did nothing while a
 * YouTube music compilation kept playing. Root cause traced to the pre-v1.3.19
 * executeStopSequence, which paused only the FIRST controller whose reported
 * state was STATE_PLAYING. YouTube's session was in STATE_ERROR (stale state
 * with audio still flowing), didn't match, and the media-key fallback then
 * routed to Spotify (most-recently-active) — pausing the wrong app.
 *
 * The v1.3.19 fix in [VoiceCommandPipeline.executeStopSequence] pauses EVERY
 * active MediaController regardless of reported state. pause() on
 * already-paused / stopped / errored sessions is a documented harmless no-op.
 *
 * This object holds the target-selection invariant so a JVM test can assert
 * we didn't quietly re-introduce a state filter. All active controllers are
 * targets — [shouldPauseAtState] returns true for every possible input.
 */
internal object StopSequenceTargets {

    /**
     * @return true if a controller in [state] should receive a pause() call.
     *   Always true for v1.3.19 semantics — see class kdoc. Parametrised so
     *   a future refactor that wants to filter has to modify the function
     *   body (and break the tests) rather than smuggle a `.filter { }` at
     *   the call site.
     */
    fun shouldPauseAtState(@Suppress("UNUSED_PARAMETER") state: Int?): Boolean = true

    /** Sanity-list of all PlaybackState.state values that must return true. */
    val ALL_STATES: List<Int?> = listOf(
        null,
        PlaybackState.STATE_NONE,
        PlaybackState.STATE_STOPPED,
        PlaybackState.STATE_PAUSED,
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_ERROR,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
    )
}

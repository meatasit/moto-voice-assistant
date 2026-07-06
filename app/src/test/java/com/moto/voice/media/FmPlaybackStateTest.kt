package com.moto.voice.media

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The shared state between FmPlayerService and VoiceCommandPipeline. Kept simple —
 * two atomic booleans. These tests exist so a future refactor (e.g., swapping in a
 * StateFlow or moving to a bound service) doesn't accidentally lose the pause-flag
 * semantics.
 */
class FmPlaybackStateTest {

    @Before fun reset() {
        FmPlaybackState.setPlaying(false)
        FmPlaybackState.clearAssistantPaused()
    }

    @After fun cleanup() {
        FmPlaybackState.setPlaying(false)
        FmPlaybackState.clearAssistantPaused()
    }

    @Test fun initialStateIsIdle() {
        assertFalse(FmPlaybackState.isPlaying)
        assertFalse(FmPlaybackState.wasPausedByAssistant)
    }

    @Test fun setPlayingReflects() {
        FmPlaybackState.setPlaying(true)
        assertTrue(FmPlaybackState.isPlaying)
        FmPlaybackState.setPlaying(false)
        assertFalse(FmPlaybackState.isPlaying)
    }

    @Test fun assistantPausedIsIndependent() {
        FmPlaybackState.markAssistantPaused()
        assertTrue(FmPlaybackState.wasPausedByAssistant)
        // isPlaying should be untouched by the pause claim itself.
        assertFalse(FmPlaybackState.isPlaying)
    }

    @Test fun clearingAssistantPausedResets() {
        FmPlaybackState.markAssistantPaused()
        FmPlaybackState.clearAssistantPaused()
        assertFalse(FmPlaybackState.wasPausedByAssistant)
    }

    @Test fun typicalInteractionSequence() {
        // FM is playing.
        FmPlaybackState.setPlaying(true)
        assertTrue(FmPlaybackState.isPlaying)
        // Pipeline pauses for interaction.
        FmPlaybackState.markAssistantPaused()
        FmPlaybackState.setPlaying(false)
        assertTrue(FmPlaybackState.wasPausedByAssistant)
        assertFalse(FmPlaybackState.isPlaying)
        // Interaction ends → resume.
        FmPlaybackState.clearAssistantPaused()
        FmPlaybackState.setPlaying(true)
        assertFalse(FmPlaybackState.wasPausedByAssistant)
        assertTrue(FmPlaybackState.isPlaying)
    }
}

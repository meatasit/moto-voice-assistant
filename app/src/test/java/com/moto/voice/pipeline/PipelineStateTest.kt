package com.moto.voice.pipeline

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Spec v1.3.9 §4 — the visual state singleton. Simple StateFlow-backed
 * enum; tests lock the transitions the RidingModeActivity relies on so a
 * future refactor doesn't quietly break the audio/visual match.
 */
class PipelineStateTest {

    @Before fun reset() = PipelineState.resetForTest()
    @After fun tearDown() = PipelineState.resetForTest()

    @Test fun initialStateIsIdle() {
        assertEquals(PipelineState.State.Idle, PipelineState.state.value)
    }

    @Test fun setListeningTransitions() {
        PipelineState.setListening()
        assertEquals(PipelineState.State.Listening, PipelineState.state.value)
    }

    @Test fun setThinkingTransitions() {
        PipelineState.setThinking()
        assertEquals(PipelineState.State.Thinking, PipelineState.state.value)
    }

    @Test fun setIdleTransitions() {
        PipelineState.setListening()
        PipelineState.setIdle()
        assertEquals(PipelineState.State.Idle, PipelineState.state.value)
    }

    @Test fun listeningThenThinkingThenIdleWalks() {
        // The canonical interaction lifecycle: mic opens → STT captures → done.
        PipelineState.setListening()
        assertEquals(PipelineState.State.Listening, PipelineState.state.value)
        PipelineState.setThinking()
        assertEquals(PipelineState.State.Thinking, PipelineState.state.value)
        PipelineState.setIdle()
        assertEquals(PipelineState.State.Idle, PipelineState.state.value)
    }

    @Test fun repeatSetDoesNotThrow() {
        // The View's setState is idempotent — the singleton must accept redundant
        // sets too so the pipeline can call them freely without state-guarding.
        PipelineState.setListening()
        PipelineState.setListening()
        PipelineState.setListening()
        assertEquals(PipelineState.State.Listening, PipelineState.state.value)
    }
}

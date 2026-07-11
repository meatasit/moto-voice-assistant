package com.moto.voice.pipeline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide signal for what the assistant is currently doing — the visual
 * counterpart to the audio language ([com.moto.voice.audio.Earcon]).
 *
 * Spec v1.3.9 §4 — the [com.moto.voice.RidingModeActivity] draws a big status
 * circle from this state; other places (Quick Settings tile, notification,
 * accessibility) can also observe. Kept as a singleton because there is one
 * pipeline at a time and the visual should match its state without threading
 * through view models.
 *
 *   * [State.Idle] — mic closed, nothing pending. UI: gray, static.
 *   * [State.Listening] — mic open, waiting for the rider. UI: green, breathing pulse.
 *   * [State.Thinking] — STT captured or webhook / action running. UI: yellow, static.
 *
 * The three states must MATCH the earcon language:
 *   * Entering Listening → [com.moto.voice.audio.Earcon.ready] just fired (main STT)
 *     OR [com.moto.voice.audio.Earcon.answerListen] fired (reply prompt).
 *   * Entering Idle → [com.moto.voice.audio.Earcon.endInteraction] just fired, or a
 *     media action is about to play.
 *   * Entering Thinking has no dedicated tone — the rider sees the yellow circle
 *     when TTS is playing our response.
 */
object PipelineState {

    enum class State { Idle, Listening, Thinking }

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun setListening() { _state.value = State.Listening }
    fun setThinking() { _state.value = State.Thinking }
    fun setIdle() { _state.value = State.Idle }

    /** Test hook — resets to Idle so tests don't leak into each other. */
    internal fun resetForTest() { _state.value = State.Idle }
}

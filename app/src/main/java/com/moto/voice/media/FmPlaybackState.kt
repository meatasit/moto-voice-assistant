package com.moto.voice.media

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide shared state between [FmPlayerService] and [com.moto.voice.pipeline.VoiceCommandPipeline].
 *
 * The pipeline needs to know whether FM is playing so it can decide whether to pause
 * before an interaction and resume after. The service publishes its playing state
 * here on every player state transition; the pipeline reads it before starting.
 *
 * `pausedByAssistant` is a claim the pipeline places when it initiates a pause so
 * that the resume-after-interaction only fires when we were the one who paused it
 * (a real "stop" command would leave this false and playback stays stopped).
 */
object FmPlaybackState {
    private val playing = AtomicBoolean(false)
    private val assistantPaused = AtomicBoolean(false)

    val isPlaying: Boolean get() = playing.get()
    val wasPausedByAssistant: Boolean get() = assistantPaused.get()

    fun setPlaying(v: Boolean) { playing.set(v) }
    fun markAssistantPaused() { assistantPaused.set(true) }
    fun clearAssistantPaused() { assistantPaused.set(false) }
}

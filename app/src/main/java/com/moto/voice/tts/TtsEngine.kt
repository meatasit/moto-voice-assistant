package com.moto.voice.tts

/**
 * Contract every TTS backend must honour, no matter what synthesises the audio.
 *
 * **The critical invariant** (spec, Sprint I contract): [speak]'s `onDone` fires when the
 * generated audio has finished PLAYING through the speaker/helmet, NOT when the request
 * has been sent to the synthesiser. This is what lets the pipeline safely open the mic
 * for the next STT round without the recogniser recording the tail end of the assistant's
 * own voice. Any engine that fires onDone too early re-introduces the Sprint 3 "call
 * cancels immediately" bug.
 *
 * Everything else (rate, volume, voice choice) is engine-specific configuration that the
 * router / higher layers plumb through via constructor / settings.
 */
interface TtsEngine {

    /** True once the engine has finished any lazy init and is ready to synth. */
    fun isReady(): Boolean

    /**
     * Speak [text] and invoke [onStart] as soon as audio begins, [onDone] when audio has
     * finished playing, [onError] on synthesis / playback failure. Multiple concurrent
     * calls flush any previous utterance (QUEUE_FLUSH semantics).
     *
     * Callbacks are dispatched on an unspecified thread — callers must not touch UI
     * directly; the pipeline wraps this via [ThaiTTS.speakAwait] which uses coroutines.
     */
    fun speak(
        text: String,
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null,
        onError: ((reason: String) -> Unit)? = null,
    )

    /** Cancel any in-flight synth + playback. Safe to call from any state. */
    fun stop()

    /** Release native resources. After this the engine may not be reused. */
    fun shutdown()
}

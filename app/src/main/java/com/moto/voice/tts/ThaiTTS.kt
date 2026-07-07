package com.moto.voice.tts

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Public TTS facade used by every caller in the app (VoiceCommandPipeline,
 * HelmetGreeter, FmPlayerService, SettingsActivity, SystemStatusActivity,
 * VoiceCommandService).
 *
 * Zero call-site changes from the original Android-only implementation. Internally
 * this now delegates to [TtsRouter], which picks Azure Neural TTS when configured
 * and available, and silently falls back to Android TTS otherwise.
 *
 * ─── Contract invariant ─────────────────────────────────────────────────────────
 * [speakAwait] suspends until the audio has FINISHED PLAYING (via engine onDone),
 * not just when synthesis completed. Any change here must preserve that timing so
 * the pipeline's speak → earcon → listenOnce sequencing stays correct.
 */
class ThaiTTS(context: Context) {

    private val router = TtsRouter.getOrCreate(context)

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        router.speak(text, onStart = null, onDone = onDone, onError = { onDone?.invoke() })
    }

    /** Suspend until playback (not just synthesis) has finished. */
    suspend fun speakAwait(text: String) {
        suspendCancellableCoroutine<Unit> { cont ->
            val resumed = AtomicBoolean(false)
            val resume = {
                if (resumed.compareAndSet(false, true) && cont.isActive) cont.resume(Unit)
            }
            router.speak(text, onStart = null, onDone = { resume() }, onError = { resume() })
            cont.invokeOnCancellation { runCatching { router.stop() } }
        }
    }

    fun stop() {
        router.stop()
    }
}

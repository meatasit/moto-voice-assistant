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
 * this delegates to [TtsRouter], which picks Azure Neural TTS when configured and
 * available, and silently falls back to Android TTS otherwise.
 *
 * ─── Contract invariant ─────────────────────────────────────────────────────────
 * [speakAwait] suspends until the audio has FINISHED PLAYING (via engine onDone),
 * not just when synthesis completed.
 *
 * ─── Global echo tracking ───────────────────────────────────────────────────────
 * Every call updates [TtsRecentSpeech] so the pipeline's echo filter can catch
 * TTS produced by ANY source — not just the pipeline itself. Fixes the field-test
 * bug where FmPlayerService's "เปิดสถานีไม่สำเร็จ" line was picked up as a user
 * command by the next interaction.
 */
class ThaiTTS(context: Context) {

    private val router = TtsRouter.getOrCreate(context)

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        TtsRecentSpeech.markSpeaking(text)
        router.speak(text, onStart = null, onDone = {
            TtsRecentSpeech.markEnded()
            onDone?.invoke()
        }, onError = {
            TtsRecentSpeech.markEnded()
            onDone?.invoke()
        })
    }

    /** Suspend until playback (not just synthesis) has finished. */
    suspend fun speakAwait(text: String) {
        suspendCancellableCoroutine<Unit> { cont ->
            val resumed = AtomicBoolean(false)
            val resume = {
                if (resumed.compareAndSet(false, true) && cont.isActive) cont.resume(Unit)
            }
            TtsRecentSpeech.markSpeaking(text)
            router.speak(text, onStart = null, onDone = {
                TtsRecentSpeech.markEnded()
                resume()
            }, onError = {
                TtsRecentSpeech.markEnded()
                resume()
            })
            cont.invokeOnCancellation {
                TtsRecentSpeech.markEnded()
                runCatching { router.stop() }
            }
        }
    }

    fun stop() {
        TtsRecentSpeech.markEnded()
        router.stop()
    }
}

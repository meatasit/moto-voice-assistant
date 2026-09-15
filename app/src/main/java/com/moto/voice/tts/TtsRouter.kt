package com.moto.voice.tts

import android.content.Context
import com.moto.voice.debug.DebugEntry
import com.moto.voice.debug.DebugLog
import com.moto.voice.debug.EngineChoiceReason
import java.util.concurrent.atomic.AtomicReference

/**
 * The single TTS access point for the rest of the app.
 *
 * v1.4.0 — Android TTS only. Azure Neural TTS (Sprint I → v1.3.42) is gone: the free
 * subscription expired (`ReadOnlyDisabledSubscription`, every synth HTTP 401 in field log
 * 1786688875809) and the rider chose to drop it rather than pay — *"เลิกใช้ Azure ไปเลยก็ได้"*.
 *
 * Removing the second engine also removes, by construction, two bugs that took several
 * rounds to chase:
 *
 *  * **two voices in one ride** — cached lines played in the Azure voice while fresh lines
 *    fell back to Android (v1.3.38 latch was the workaround);
 *  * **two sentences on top of each other** — Azure played through its own MediaPlayer and
 *    Android through the platform engine, so one could not flush the other (v1.3.38
 *    stop-before-speak was the workaround). One engine with QUEUE_FLUSH cannot overlap.
 *
 * Still a singleton so every [ThaiTTS] facade shares one engine instance — that is what
 * makes [stop] from any caller silence whatever is in flight.
 */
class TtsRouter private constructor(app: Context) {

    private val android = AndroidTtsEngine(app)

    /**
     * @param stampDebug whether this speak writes its engine onto the current [DebugEntry].
     *   v1.3.36 — false for lines spoken OUTSIDE an interaction (the nudge's launch-blocked
     *   announcement fires ~10s after the pipeline finished) so they don't overwrite the
     *   finished interaction's TTS fields (field log 1786104958601).
     */
    fun speak(
        text: String,
        onStart: (() -> Unit)?,
        onDone: (() -> Unit)?,
        onError: ((reason: String) -> Unit)?,
        stampDebug: Boolean = true,
    ) {
        // One voice at a time (v1.3.38). Kept even with a single engine: the platform TTS
        // flushes its own queue, but an explicit stop also cancels a pending pre-init
        // utterance so a stale line can't surface after the engine finishes starting.
        stop()
        val entry = if (stampDebug) DebugLog.entries().firstOrNull() else null
        markDebug(entry)
        android.speak(text, onStart, onDone, onError)
    }

    fun stop() {
        android.stop()
    }

    private fun markDebug(entry: DebugEntry?) {
        val head = entry ?: return
        head.ttsEngine = "android"
        head.engineChoiceReason = EngineChoiceReason.ANDROID_ONLY
    }

    companion object {
        private val instance = AtomicReference<TtsRouter?>(null)

        fun getOrCreate(context: Context): TtsRouter {
            val existing = instance.get()
            if (existing != null) return existing
            val fresh = TtsRouter(context.applicationContext)
            return if (instance.compareAndSet(null, fresh)) fresh else instance.get()!!
        }
    }
}

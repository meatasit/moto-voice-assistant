package com.moto.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.moto.voice.data.AppSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

/**
 * Wrapper around Android [TextToSpeech] with a single, long-lived progress listener that
 * dispatches to per-utterance callbacks. Fixes the previous bug where each [speak] call
 * replaced the listener, dropping earlier utterance completions and hanging the pipeline.
 */
class ThaiTTS(private val context: Context) {

    private var tts: TextToSpeech? = null

    @Volatile private var ready = false

    private val pending = CopyOnWriteArrayList<Pair<String, (() -> Unit)?>>()
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) {
            utteranceId?.let { callbacks.remove(it)?.invoke() }
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            utteranceId?.let { callbacks.remove(it)?.invoke() }
        }
        override fun onError(utteranceId: String?, errorCode: Int) {
            utteranceId?.let { callbacks.remove(it)?.invoke() }
        }
    }

    private val settings = runCatching { AppSettings(context) }.getOrNull()

    init {
        val rate = settings?.ttsSpeechRate ?: AppSettings.DEFAULT_TTS_RATE
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("th", "TH")
                tts?.setSpeechRate(rate)
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                tts?.setOnUtteranceProgressListener(listener)
                ready = true
                val snapshot = pending.toList()
                pending.clear()
                snapshot.forEach { (text, cb) -> speak(text, cb) }
            }
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!ready) {
            pending += text to onDone
            return
        }
        val id = UUID.randomUUID().toString()
        if (onDone != null) callbacks[id] = onDone
        val params = Bundle().apply {
            // KEY_PARAM_VOLUME range is 0..1 per docs; we allow up to 1.5 in settings
            // for engines that accept boost. Clamp for safety.
            val volume = (settings?.assistantVolume ?: AppSettings.DEFAULT_ASSIST_VOLUME).coerceIn(0f, 1.5f)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        }
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
        if (result == TextToSpeech.ERROR) {
            callbacks.remove(id)?.invoke()
        }
    }

    suspend fun speakAwait(text: String) {
        suspendCancellableCoroutine<Unit> { cont ->
            speak(text) { if (cont.isActive) cont.resume(Unit) }
            cont.invokeOnCancellation { runCatching { tts?.stop() } }
        }
    }

    fun stop() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
        pending.clear()
        callbacks.clear()
    }
}

package com.moto.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.moto.voice.data.AppSettings
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The original Android [TextToSpeech]-backed engine, wrapped in the [TtsEngine] interface.
 *
 * onDone is dispatched from [UtteranceProgressListener.onDone] which the platform docs
 * define as "playback finished" — matches our [TtsEngine.speak] contract exactly, so no
 * additional bookkeeping is needed.
 */
class AndroidTtsEngine(private val context: Context) : TtsEngine {

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    private data class Pending(val text: String, val onStart: (() -> Unit)?, val onDone: (() -> Unit)?, val onError: ((String) -> Unit)?)
    private val pending = CopyOnWriteArrayList<Pending>()

    /** Per-utterance callbacks by utteranceId. */
    private data class Callbacks(val onStart: (() -> Unit)?, val onDone: (() -> Unit)?, val onError: ((String) -> Unit)?)
    private val callbacks = ConcurrentHashMap<String, Callbacks>()

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            utteranceId?.let { callbacks[it]?.onStart?.invoke() }
        }
        override fun onDone(utteranceId: String?) {
            utteranceId?.let { callbacks.remove(it)?.onDone?.invoke() }
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            utteranceId?.let { callbacks.remove(it)?.onError?.invoke("legacy error") }
        }
        override fun onError(utteranceId: String?, errorCode: Int) {
            utteranceId?.let { callbacks.remove(it)?.onError?.invoke("code $errorCode") }
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
                snapshot.forEach { speak(it.text, it.onStart, it.onDone, it.onError) }
            }
        }
    }

    override fun isReady(): Boolean = ready

    override fun speak(
        text: String,
        onStart: (() -> Unit)?,
        onDone: (() -> Unit)?,
        onError: ((reason: String) -> Unit)?,
    ) {
        if (!ready) {
            pending += Pending(text, onStart, onDone, onError)
            return
        }
        val id = UUID.randomUUID().toString()
        callbacks[id] = Callbacks(onStart, onDone, onError)
        val params = Bundle().apply {
            // KEY_PARAM_VOLUME range is 0..1 per docs; some engines accept boost up to 1.5.
            val volume = (settings?.assistantVolume ?: AppSettings.DEFAULT_ASSIST_VOLUME).coerceIn(0f, 1.5f)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        }
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
        if (result == TextToSpeech.ERROR) {
            callbacks.remove(id)?.onError?.invoke("speak returned ERROR")
        }
    }

    override fun stop() {
        runCatching { tts?.stop() }
    }

    override fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
        pending.clear()
        callbacks.clear()
    }
}

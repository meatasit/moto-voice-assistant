package com.moto.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

class ThaiTTS(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private val pendingQueue = mutableListOf<Pair<String, (() -> Unit)?>>()

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("th", "TH")
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                ready = true
                pendingQueue.forEach { (text, cb) -> speak(text, cb) }
                pendingQueue.clear()
            }
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!ready) {
            pendingQueue.add(text to onDone)
            return
        }
        val id = UUID.randomUUID().toString()
        if (onDone != null) {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id) onDone()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {}
            })
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stop() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        pendingQueue.clear()
    }
}

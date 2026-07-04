package com.moto.voice.pipeline

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.moto.voice.audio.BluetoothAudioRouter
import com.moto.voice.contacts.ContactEntry
import com.moto.voice.contacts.ContactMatcher
import com.moto.voice.contacts.MatchResult
import com.moto.voice.recognition.CommandParser
import com.moto.voice.recognition.VoiceCommand
import com.moto.voice.tts.ThaiTTS

private const val TAG = "VoiceCommandPipeline"
private const val HIGH_CONFIDENCE = 0.75f

class VoiceCommandPipeline(
    private val context: Context,
    private val onFinished: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val btRouter = BluetoothAudioRouter(context)
    private val tts = ThaiTTS(context)
    private val contactMatcher = ContactMatcher(context)
    private var recognizer: SpeechRecognizer? = null
    private var disambiguationCandidates: List<MatchResult> = emptyList()

    fun start() {
        if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            btRouter.connect { beginListening() }
        } else {
            beginListening()
        }
    }

    private fun beginListening() {
        playTing {
            startRecognition(::handleCommandText)
        }
    }

    // ── Command handling ─────────────────────────────────────────────────────

    private fun handleCommandText(text: String) {
        Log.d(TAG, "Recognized: $text")
        val command = CommandParser.parse(text)
        if (command == null) {
            tts.speak("ไม่เข้าใจคำสั่ง ลองพูดว่า โทรหา แล้วตามด้วยชื่อ") { finish() }
            return
        }
        when (command) {
            is VoiceCommand.Call -> handleCallCommand(command.name)
        }
    }

    private fun handleCallCommand(name: String) {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            tts.speak("ไม่มีสิทธิ์เข้าถึงรายชื่อ กรุณาอนุญาตในแอป") { finish() }
            return
        }
        val matches = contactMatcher.findMatches(name)
        when {
            matches.isEmpty() -> {
                tts.speak("ไม่พบ $name ในรายชื่อผู้ติดต่อ") { finish() }
            }
            matches.size == 1 && matches[0].score >= HIGH_CONFIDENCE -> {
                confirmAndCall(matches[0].contact)
            }
            else -> {
                disambiguationCandidates = matches.take(3)
                askDisambiguation(disambiguationCandidates)
            }
        }
    }

    private fun confirmAndCall(contact: ContactEntry) {
        tts.speak("กำลังโทรหา ${contact.displayName}") {
            makeCall(contact.phoneNumber)
        }
    }

    private fun askDisambiguation(candidates: List<MatchResult>) {
        val ordinals = listOf("คนแรก", "คนที่สอง", "คนที่สาม")
        val nameList = candidates.mapIndexed { i, m ->
            "${ordinals.getOrElse(i) { "คนที่ ${i + 1}" }} ${m.contact.displayName}"
        }.joinToString(", ")
        tts.speak("พบหลายคน ได้แก่ $nameList พูด คนแรก คนที่สอง หรือ ยกเลิก") {
            startRecognition(::handleDisambiguationAnswer)
        }
    }

    private fun handleDisambiguationAnswer(answer: String) {
        val lower = answer.trim()
        val index = when {
            lower.contains("แรก") || lower.contains("หนึ่ง") || lower == "1" -> 0
            lower.contains("สอง") || lower == "2" -> 1
            lower.contains("สาม") || lower == "3" -> 2
            lower.contains("ยกเลิก") || lower.contains("cancel", ignoreCase = true) -> -1
            else -> -2
        }
        when {
            index == -1 -> tts.speak("ยกเลิกแล้ว") { finish() }
            index in disambiguationCandidates.indices -> confirmAndCall(disambiguationCandidates[index].contact)
            else -> tts.speak("ไม่เข้าใจ ยกเลิกแล้ว") { finish() }
        }
    }

    // ── Call ─────────────────────────────────────────────────────────────────

    private fun makeCall(phoneNumber: String) {
        if (!hasPermission(Manifest.permission.CALL_PHONE)) {
            tts.speak("ไม่มีสิทธิ์โทรออก กรุณาอนุญาตในแอป") { finish() }
            return
        }
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start call", e)
        }
        finish()
    }

    // ── Audio helpers ─────────────────────────────────────────────────────────

    private fun playTing(onDone: () -> Unit) {
        handler.post {
            try {
                val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
                handler.postDelayed({
                    runCatching { tone.release() }
                    onDone()
                }, 300)
            } catch (_: Exception) {
                onDone()
            }
        }
    }

    private fun startRecognition(onResult: (String) -> Unit) {
        handler.post {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    onResult(matches?.firstOrNull() ?: "")
                }

                override fun onError(error: Int) {
                    Log.w(TAG, "STT error code: $error")
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ไม่ได้ยินเสียง กรุณาลองใหม่"
                        SpeechRecognizer.ERROR_AUDIO -> "เกิดข้อผิดพลาดไมโครโฟน"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ระบบรับเสียงยุ่ง กรุณาลองใหม่"
                        else -> "เกิดข้อผิดพลาด กรุณาลองใหม่"
                    }
                    tts.speak(msg) { finish() }
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "th-TH")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "th-TH")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            }
            recognizer?.startListening(intent)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    private fun finish() {
        stop()
        onFinished()
    }

    fun stop() {
        handler.post {
            recognizer?.destroy()
            recognizer = null
        }
        btRouter.disconnect()
        tts.stop()
    }

    private fun hasPermission(perm: String) =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
}

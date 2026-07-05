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
import com.moto.voice.data.AppSettings
import com.moto.voice.debug.DebugEntry
import com.moto.voice.debug.DebugLog
import com.moto.voice.media.FmPlayerService
import com.moto.voice.network.WebhookClient
import com.moto.voice.network.WebhookResponse
import com.moto.voice.recognition.CommandParser
import com.moto.voice.recognition.VoiceCommand
import com.moto.voice.tts.ThaiTTS

private const val TAG = "VoiceCommandPipeline"
private const val HIGH_CONF = 0.75f

class VoiceCommandPipeline(
    private val context: Context,
    private val settings: AppSettings,
    private val onFinished: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val btRouter = BluetoothAudioRouter(context)
    private val tts = ThaiTTS(context)
    private val contactMatcher = ContactMatcher(context)
    private var recognizer: SpeechRecognizer? = null
    private var disambigCandidates: List<MatchResult> = emptyList()

    fun start() {
        val entry = DebugLog.new()
        val t0 = System.currentTimeMillis()

        if (hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) {
            btRouter.connect(3_000L) { scoOk ->
                entry.scoTimeMs = System.currentTimeMillis() - t0
                Log.d(TAG, "SCO: $scoOk in ${entry.scoTimeMs}ms")
                beginListening(entry)
            }
        } else {
            beginListening(entry)
        }
    }

    private fun beginListening(entry: DebugEntry) {
        playTing {
            val t1 = System.currentTimeMillis()
            listenForSpeech(entry) { text ->
                entry.sttTimeMs = System.currentTimeMillis() - t1
                if (text.isBlank()) {
                    tts.speak("ไม่ได้ยินเสียง") { finish() }
                    return@listenForSpeech
                }
                processText(text, entry)
            }
        }
    }

    // ─── Command processing ───────────────────────────────────────────────────

    private fun processText(text: String, entry: DebugEntry) {
        entry.webhookRequest = text
        if (settings.llmMode && settings.webhookUrl.isNotBlank()) {
            val client = WebhookClient(settings.webhookUrl, settings.authToken, settings.timeoutSeconds)
            Thread {
                val result = client.send(text)
                entry.webhookResponse = result.rawJson
                entry.webhookTimeMs = result.elapsedMs
                handler.post {
                    if (result.response != null) {
                        val t2 = System.currentTimeMillis()
                        executeWebhookAction(result.response, entry) {
                            entry.actionTimeMs = System.currentTimeMillis() - t2
                            finish()
                        }
                    } else {
                        entry.error = result.error
                        tts.speak("โหมดออฟไลน์") { executeRuleBased(text, entry) }
                    }
                }
            }.start()
        } else {
            executeRuleBased(text, entry)
        }
    }

    private fun executeWebhookAction(resp: WebhookResponse, entry: DebugEntry, onDone: () -> Unit) {
        Log.d(TAG, "action=${resp.action} speak=${resp.speak}")
        when (resp.action.lowercase()) {
            "call" -> {
                val name = resp.contact ?: resp.speak
                handleCallByName(name, resp.speak, onDone)
            }
            "youtube_play" -> {
                tts.speak(resp.speak.ifBlank { "กำลังเปิด YouTube" }) {
                    openYoutube(resp.videoId, resp.query)
                    onDone()
                }
            }
            "fm" -> {
                if (!resp.streamUrl.isNullOrBlank()) {
                    tts.speak(resp.speak.ifBlank { "กำลังเล่นวิทยุ" }) {
                        startFm(resp.streamUrl, resp.frequency ?: "FM")
                        onDone()
                    }
                } else {
                    tts.speak(resp.speak.ifBlank { "ไม่มี stream URL" }) { onDone() }
                }
            }
            else -> tts.speak(resp.speak.ifBlank { "เข้าใจแล้ว" }) { onDone() }
        }
    }

    // ─── Rule-based fallback ──────────────────────────────────────────────────

    private fun executeRuleBased(text: String, entry: DebugEntry) {
        val cmd = CommandParser.parse(text)
        if (cmd == null) {
            tts.speak("ไม่เข้าใจคำสั่ง ลองพูดว่า โทรหา ตามด้วยชื่อ") { finish() }
            return
        }
        when (cmd) {
            is VoiceCommand.Call -> handleCallByName(cmd.name, null) { finish() }
        }
    }

    // ─── Call handling ────────────────────────────────────────────────────────

    private fun handleCallByName(name: String, speakOverride: String?, onDone: () -> Unit) {
        if (!hasPerm(Manifest.permission.READ_CONTACTS)) {
            tts.speak("ไม่มีสิทธิ์รายชื่อ") { onDone() }
            return
        }
        val matches = contactMatcher.findMatches(name)
        when {
            matches.isEmpty() -> tts.speak("ไม่พบ $name ในรายชื่อ") { onDone() }
            matches.size == 1 && matches[0].score >= HIGH_CONF ->
                confirmThenCall(matches[0].contact, speakOverride, onDone)
            else -> {
                disambigCandidates = matches.take(3)
                askDisambig(disambigCandidates) { idx ->
                    if (idx < 0) tts.speak("ยกเลิกแล้ว") { onDone() }
                    else confirmThenCall(disambigCandidates[idx].contact, null, onDone)
                }
            }
        }
    }

    private fun confirmThenCall(contact: ContactEntry, speakOverride: String?, onDone: () -> Unit) {
        val confirmMsg = speakOverride?.ifBlank { null }
            ?: "กำลังโทรหา ${contact.displayName}"
        if (settings.confirmBeforeCall) {
            tts.speak("$confirmMsg ยืนยันไหม") {
                listenForSpeech(null) { answer ->
                    if (isConfirmed(answer)) makeCall(contact, onDone)
                    else tts.speak("ยกเลิกแล้ว") { onDone() }
                }
            }
        } else {
            tts.speak(confirmMsg) { makeCall(contact, onDone) }
        }
    }

    private fun askDisambig(candidates: List<MatchResult>, onChoice: (Int) -> Unit) {
        val labels = listOf("คนแรก", "คนที่สอง", "คนที่สาม")
        val list = candidates.mapIndexed { i, m ->
            "${labels.getOrElse(i) { "คนที่ ${i + 1}" }} ${m.contact.displayName}"
        }.joinToString(", ")
        tts.speak("พบหลายคน ได้แก่ $list พูด คนแรก คนที่สอง หรือ ยกเลิก") {
            listenForSpeech(null) { ans ->
                val lower = ans.trim()
                val idx = when {
                    lower.contains("แรก") || lower.contains("หนึ่ง") -> 0
                    lower.contains("สอง") -> 1
                    lower.contains("สาม") -> 2
                    lower.contains("ยกเลิก") || lower.contains("cancel", ignoreCase = true) -> -1
                    else -> -1
                }
                onChoice(idx)
            }
        }
    }

    private fun makeCall(contact: ContactEntry, onDone: () -> Unit) {
        if (!hasPerm(Manifest.permission.CALL_PHONE)) {
            tts.speak("ไม่มีสิทธิ์โทรออก") { onDone() }
            return
        }
        try {
            context.startActivity(
                Intent(Intent.ACTION_CALL, Uri.parse("tel:${contact.phoneNumber}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.e(TAG, "call failed", e)
        }
        onDone()
    }

    // ─── YouTube ──────────────────────────────────────────────────────────────

    private fun openYoutube(videoId: String?, query: String?) {
        fun intent(uri: Uri) = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (!videoId.isNullOrBlank()) {
            val ytUri = Uri.parse("vnd.youtube:$videoId")
            val launched = runCatching {
                if (intent(ytUri).resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent(ytUri)); true
                } else false
            }.getOrDefault(false)
            if (!launched) {
                context.startActivity(intent(Uri.parse("https://www.youtube.com/watch?v=$videoId")))
            }
        } else if (!query.isNullOrBlank()) {
            val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val launched = runCatching {
                if (searchIntent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(searchIntent); true
                } else false
            }.getOrDefault(false)
            if (!launched) {
                context.startActivity(
                    intent(Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}"))
                )
            }
        }
    }

    // ─── FM radio ────────────────────────────────────────────────────────────

    private fun startFm(streamUrl: String, label: String) {
        context.startService(
            Intent(context, FmPlayerService::class.java)
                .setAction(FmPlayerService.ACTION_PLAY)
                .putExtra(FmPlayerService.EXTRA_STREAM_URL, streamUrl)
                .putExtra(FmPlayerService.EXTRA_LABEL, label)
        )
    }

    // ─── Audio helpers ────────────────────────────────────────────────────────

    private fun playTing(onDone: () -> Unit) {
        handler.post {
            runCatching {
                val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
                handler.postDelayed({ runCatching { tone.release() }; onDone() }, 300)
            }.onFailure { onDone() }
        }
    }

    private fun listenForSpeech(entry: DebugEntry?, onResult: (String) -> Unit) {
        handler.post {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(r: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(t: Int, p: Bundle?) {}

                override fun onPartialResults(results: Bundle?) {
                    val partial = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: return
                    entry?.sttPartial = partial
                    Log.d(TAG, "partial: $partial")
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: ""
                    onResult(text)
                }

                override fun onError(error: Int) {
                    Log.w(TAG, "STT error $error")
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ไม่ได้ยินเสียง"
                        SpeechRecognizer.ERROR_AUDIO -> "ข้อผิดพลาดไมโครโฟน"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ระบบรับเสียงยุ่ง"
                        else -> "ข้อผิดพลาด"
                    }
                    if (entry != null) entry.error = "STT $error: $msg"
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
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            recognizer?.startListening(intent)
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun isConfirmed(text: String): Boolean {
        val t = text.lowercase().trim()
        return t.contains("ใช่") || t.contains("โทร") || t.contains("ตกลง") ||
               t.contains("ok", ignoreCase = true) || t.contains("yes", ignoreCase = true) ||
               t.contains("เลย") || t.contains("ได้")
    }

    private fun finish() { stop(); onFinished() }

    fun stop() {
        handler.post { recognizer?.destroy(); recognizer = null }
        btRouter.disconnect()
        tts.stop()
    }

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
}

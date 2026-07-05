package com.moto.voice.pipeline

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.moto.voice.actions.MediaStopper
import com.moto.voice.audio.BluetoothAudioRouter
import com.moto.voice.audio.Earcon
import com.moto.voice.audio.PhoneStateGuard
import com.moto.voice.contacts.ContactEntry
import com.moto.voice.contacts.ContactMatcher
import com.moto.voice.contacts.MatchResult
import com.moto.voice.data.AppHistory
import com.moto.voice.data.AppMemory
import com.moto.voice.data.AppSettings
import com.moto.voice.data.HistoryAction
import com.moto.voice.data.HistoryEntry
import com.moto.voice.data.OfflineNotifier
import com.moto.voice.debug.DebugEntry
import com.moto.voice.debug.DebugLog
import com.moto.voice.media.FmPlayerService
import com.moto.voice.network.WebhookClient
import com.moto.voice.network.WebhookResponse
import com.moto.voice.nlu.LocalIntercept
import com.moto.voice.nlu.NumberWordParser
import com.moto.voice.nlu.CommandParser
import com.moto.voice.nlu.VoiceCommand
import com.moto.voice.tts.ThaiTTS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

private const val TAG = "VoiceCommandPipeline"
private const val HIGH_CONF = 0.75f
/** Extract 11-char YouTube video id from anywhere in the string (bare id, URL, whatever). */
private val YOUTUBE_ID_EXTRACT = Regex("([A-Za-z0-9_-]{11})")
private val YOUTUBE_STRIP_PREFIXES = listOf(
    "เปิดยูทูป", "เปิดยูทูบ", "เปิด youtube", "เปิด yt", "เปิดเพลง",
    "ยูทูป", "ยูทูบ", "youtube", "เพลง",
)

class VoiceCommandPipeline(
    private val context: Context,
    private val settings: AppSettings,
    private val onFinished: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val btRouter = BluetoothAudioRouter(context)
    private val tts = ThaiTTS(context)
    private val contactMatcher = ContactMatcher(context)
    private val memory = AppMemory(context)
    private val history = AppHistory(context)

    // The text the STT captured this session. Used to attach to history entries so
    // "you said X → we did Y" is visible without the debug log.
    private var heardText: String = ""

    private val finished = AtomicBoolean(false)
    private var runJob: Job? = null

    private var recognizer: SpeechRecognizer? = null

    fun start() {
        if (runJob?.isActive == true) return
        runJob = scope.launch { runPipeline() }
    }

    private suspend fun runPipeline() {
        val entry = DebugLog.new()
        val t0 = System.currentTimeMillis()

        val availability = PhoneStateGuard.availability(context)
        if (availability != PhoneStateGuard.Availability.Available) {
            entry.error = "phone unavailable: $availability"
            speakAndRemember(PhoneStateGuard.reasonText(availability))
            finish(); return
        }

        val hasBt = hasPerm(Manifest.permission.BLUETOOTH_CONNECT)
        val scoOk = if (hasBt) {
            connectSco(3_000L).also {
                entry.scoTimeMs = System.currentTimeMillis() - t0
                Log.d(TAG, "SCO: $it in ${entry.scoTimeMs}ms")
            }
        } else false

        // Tell the rider we're falling back to the phone mic — but only when we tried and
        // failed. Skip the announcement entirely when there's no BT permission (no helmet expected).
        if (hasBt && !scoOk) {
            tts.speakAwait("ใช้ไมค์โทรศัพท์")
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            entry.error = "no speech recognition service"
            Earcon.error()
            speakAndRemember("อุปกรณ์ไม่รองรับการรับเสียง")
            finish(); return
        }

        Earcon.ready()

        val t1 = System.currentTimeMillis()
        val text = listenOnce(entry)
        entry.sttTimeMs = System.currentTimeMillis() - t1
        entry.sttFinal = text

        if (text.isBlank()) {
            Earcon.error()
            speakAndRemember("ไม่ได้ยินเสียง")
            finish(); return
        }

        heardText = text
        Earcon.end()

        // Local intercept ALWAYS runs before webhook — offline-first, sub-second response.
        when (val intercept = LocalIntercept.match(text)) {
            is LocalIntercept.Intercept.None -> processText(text, entry)
            else -> handleIntercept(intercept, entry)
        }
    }

    private fun recordHistory(action: HistoryAction) {
        history.record(
            HistoryEntry(
                timestamp = System.currentTimeMillis(),
                heard = heardText,
                spoken = memory.lastSpoken.orEmpty(),
                action = action,
            )
        )
    }

    // ─── Local intercept handlers ────────────────────────────────────────────

    private suspend fun handleIntercept(intercept: LocalIntercept.Intercept, entry: DebugEntry) {
        entry.webhookRequest = "[intercepted: ${intercept::class.simpleName}]"
        when (intercept) {
            LocalIntercept.Intercept.Stop -> {
                MediaStopper.stopAny(context)
                speakAndRemember("หยุดแล้ว")
                recordHistory(HistoryAction.Stop)
            }
            LocalIntercept.Intercept.Help -> speakAndRemember(LocalIntercept.HELP_TEXT)
            LocalIntercept.Intercept.RepeatLast -> {
                val last = memory.lastSpoken
                if (last.isNullOrBlank()) speakAndRemember("ยังไม่มีข้อความให้พูดซ้ำ")
                // Speak-without-remember: repeating shouldn't overwrite the memory itself.
                else tts.speakAwait(last)
            }
            LocalIntercept.Intercept.ResumeLastRadio -> {
                val url = memory.lastStationUrl
                val name = memory.lastStationName ?: "วิทยุ"
                if (url.isNullOrBlank()) speakAndRemember("ยังไม่เคยเปิดวิทยุ พูดชื่อคลื่นได้เลย")
                else {
                    speakAndRemember("เปิด $name")
                    startFm(url, name, memory.lastStationFrequency)
                }
            }
            LocalIntercept.Intercept.CallBackLast -> {
                val number = memory.lastCallNumber
                val name = memory.lastCallName ?: "เบอร์ล่าสุด"
                if (number.isNullOrBlank()) speakAndRemember("ยังไม่มีเบอร์ล่าสุดในแอปนี้")
                else confirmThenCall(ContactEntry(id = "last", displayName = name, phoneNumber = number), null)
            }
            LocalIntercept.Intercept.None -> Unit  // handled in caller
        }
        finish()
    }

    // ─── Command processing ───────────────────────────────────────────────────

    private suspend fun processText(text: String, entry: DebugEntry) {
        entry.webhookRequest = text
        val useWebhook = settings.llmMode && settings.webhookUrl.isNotBlank()
        if (!useWebhook) {
            executeRuleBased(text, entry); return
        }

        val client = WebhookClient(settings.webhookUrl, settings.authToken, settings.timeoutSeconds)
        val result = client.call(text)
        entry.webhookResponse = result.rawJson
        entry.webhookTimeMs = result.elapsedMs

        when (result) {
            is WebhookClient.Result.Success -> {
                OfflineNotifier.onWebhookSuccess()
                val t2 = System.currentTimeMillis()
                executeWebhookAction(result.response, entry)
                entry.actionTimeMs = System.currentTimeMillis() - t2
                finish()
            }
            is WebhookClient.Result.Failure -> {
                entry.error = result.error
                Log.w(TAG, "webhook failed: ${result.error}")
                // Announce offline only ONCE per outage — spec §7.
                if (OfflineNotifier.shouldAnnounce()) speakAndRemember("โหมดออฟไลน์")
                executeRuleBased(text, entry)
            }
        }
    }

    private suspend fun executeWebhookAction(resp: WebhookResponse, entry: DebugEntry) {
        Log.d(TAG, "action=${resp.action} speak=${resp.speak}")
        when (resp.action.lowercase()) {
            "call" -> {
                val name = resp.contact?.takeIf { it.isNotBlank() } ?: resp.speak
                handleCallByName(name, resp.speak)
            }
            "youtube_play" -> handleYoutube(resp, entry)
            "fm" -> {
                if (!resp.streamUrl.isNullOrBlank()) {
                    val name = resp.stationName ?: resp.frequency?.let { "FM $it" } ?: "วิทยุ"
                    speakAndRemember(resp.speak.ifBlank { "กำลังเปิด $name" })
                    startFm(resp.streamUrl, name, resp.frequency)
                } else {
                    speakAndRemember(resp.speak.ifBlank { "ไม่มี stream URL" })
                }
            }
            "stop" -> {
                MediaStopper.stopAny(context)
                speakAndRemember(resp.speak.ifBlank { "หยุดแล้ว" })
                recordHistory(HistoryAction.Stop)
            }
            "none" -> speakAndRemember(resp.speak.ifBlank { "รับทราบ" })
            else -> speakAndRemember(resp.speak.ifBlank { "เข้าใจแล้ว" })
        }
    }

    // ─── Rule-based fallback ──────────────────────────────────────────────────

    private suspend fun executeRuleBased(text: String, entry: DebugEntry) {
        val cmd = CommandParser.parse(text)
        if (cmd == null) {
            speakAndRemember("ไม่เข้าใจคำสั่ง ลองพูดว่า โทรหา ตามด้วยชื่อ")
            finish(); return
        }
        when (cmd) {
            is VoiceCommand.Call -> handleCallByName(cmd.name, null)
        }
        finish()
    }

    // ─── Call handling ────────────────────────────────────────────────────────

    private suspend fun handleCallByName(name: String, speakOverride: String?) {
        if (!hasPerm(Manifest.permission.READ_CONTACTS)) {
            speakAndRemember("ไม่มีสิทธิ์รายชื่อ"); return
        }
        val matches = contactMatcher.findMatches(name)
        when {
            matches.isEmpty() -> speakAndRemember("ไม่พบ $name ในรายชื่อ")
            matches.size == 1 && matches[0].score >= HIGH_CONF ->
                confirmThenCall(matches[0].contact, speakOverride)
            else -> {
                val candidates = matches.take(3)
                val choice = askDisambig(candidates)
                if (choice < 0) speakAndRemember("ยกเลิกแล้ว")
                else confirmThenCall(candidates[choice].contact, null)
            }
        }
    }

    private suspend fun confirmThenCall(contact: ContactEntry, speakOverride: String?) {
        val msg = speakOverride?.takeIf { it.isNotBlank() } ?: "กำลังโทรหา ${contact.displayName}"
        if (settings.confirmBeforeCall) {
            speakAndRemember("$msg ยืนยันไหม")
            val answer = listenOnce(null)
            if (isConfirmed(answer)) makeCall(contact)
            else speakAndRemember("ยกเลิกแล้ว")
        } else {
            speakAndRemember(msg)
            makeCall(contact)
        }
    }

    private suspend fun askDisambig(candidates: List<MatchResult>): Int {
        val labels = listOf("คนแรก", "คนที่สอง", "คนที่สาม")
        val list = candidates.mapIndexed { i, m ->
            "${labels.getOrElse(i) { "คนที่ ${i + 1}" }} ${m.contact.displayName}"
        }.joinToString(", ")
        speakAndRemember("พบหลายคน ได้แก่ $list พูด คนแรก คนที่สอง หรือ ยกเลิก")
        val ans = listenOnce(null)
        return when (val c = NumberWordParser.parse(ans, candidates.size)) {
            is NumberWordParser.Choice.Index -> c.zeroBased
            else -> -1
        }
    }

    private fun makeCall(contact: ContactEntry) {
        if (!hasPerm(Manifest.permission.CALL_PHONE)) {
            scope.launch { speakAndRemember("ไม่มีสิทธิ์โทรออก") }
            return
        }
        if (contact.phoneNumber.isBlank()) {
            Log.w(TAG, "empty phone number for ${contact.displayName}")
            scope.launch { speakAndRemember("ไม่มีเบอร์โทรของ ${contact.displayName}") }
            return
        }
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(contact.phoneNumber)}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            memory.rememberCall(contact.phoneNumber, contact.displayName)
            recordHistory(HistoryAction.Call(contact.displayName, contact.phoneNumber))
        }.onFailure {
            Log.e(TAG, "call failed", it)
            scope.launch { speakAndRemember("โทรไม่ได้ ลองอีกที") }
        }
    }

    // ─── YouTube ──────────────────────────────────────────────────────────────

    private suspend fun handleYoutube(resp: WebhookResponse, entry: DebugEntry) {
        val candidates = collectYoutubeCandidates(resp)
        val autoPick = !settings.askBeforeYoutube || candidates.size <= 1

        val chosen = if (autoPick) candidates.firstOrNull()
            else pickYoutubeFromCandidates(candidates, entry)

        if (chosen != null) {
            val spoken = when {
                resp.speak.isNotBlank() -> resp.speak
                chosen.title.isNotBlank() -> "กำลังเปิด ${chosen.title}"
                else -> "กำลังเปิด YouTube"
            }
            speakAndRemember(spoken)
            openYoutube(chosen.id, resp.query, entry)
            recordHistory(HistoryAction.YoutubeOpen(chosen.id, chosen.title))
            return
        }

        // No usable video id from the webhook — but we should never dead-end silent.
        // Prefer the webhook's own query, then fall back to what the rider actually said
        // (stripped of "เปิด YouTube" filler words) so YouTube search still opens.
        val fallbackQuery = resp.query?.takeIf { it.isNotBlank() }
            ?: stripYoutubeFillerFromHeard(heardText).takeIf { it.isNotBlank() }

        if (fallbackQuery != null) {
            val spoken = resp.speak.ifBlank { "กำลังค้นหา $fallbackQuery" }
            speakAndRemember(spoken)
            openYoutube(null, fallbackQuery, entry)
            recordHistory(HistoryAction.YoutubeOpen("", fallbackQuery))
        } else {
            speakAndRemember(resp.speak.ifBlank { "ไม่พบวิดีโอ ลองพูดชื่อเพลงอีกที" })
        }
    }

    /**
     * Merge legacy video_id/video_title with the newer videos[] into a single deduped list.
     * IDs may arrive as bare 11-char ids OR embedded in URLs like "https://youtu.be/xxx" —
     * we extract the id in both cases.
     */
    private fun collectYoutubeCandidates(resp: WebhookResponse): List<WebhookResponse.Video> {
        val out = linkedMapOf<String, WebhookResponse.Video>()
        extractYoutubeId(resp.videoId)?.let {
            out[it] = WebhookResponse.Video(it, resp.videoTitle ?: "")
        }
        resp.videos?.forEach { v ->
            val id = extractYoutubeId(v.id)
            if (id != null && !out.containsKey(id)) out[id] = v.copy(id = id)
        }
        return out.values.toList()
    }

    /** Return the first 11-char alphanumeric-with-_- token found in [raw], or null. */
    private fun extractYoutubeId(raw: String?): String? {
        val s = raw?.trim() ?: return null
        if (s.isEmpty()) return null
        return YOUTUBE_ID_EXTRACT.find(s)?.value
    }

    /** Strip "เปิด YouTube / เปิดเพลง / ..." prefixes so the remainder is a searchable query. */
    private fun stripYoutubeFillerFromHeard(raw: String): String {
        var s = raw.trim().lowercase()
        var changed = true
        while (changed) {
            changed = false
            for (pfx in YOUTUBE_STRIP_PREFIXES) {
                if (s.startsWith(pfx)) {
                    s = s.substring(pfx.length).trim()
                    changed = true
                }
            }
        }
        return s
    }

    /**
     * Spec §4 YouTube picker:
     *   - TTS reads titles of up to 3 candidates.
     *   - Listens for หนึ่ง/สอง/สาม/อันแรก/อันสุดท้าย/ยกเลิก, up to 2 rounds.
     *   - Silence or unparseable answer twice → default to first + "เปิดอันแรกให้นะครับ".
     *   - No dead-end silence: always speaks before returning.
     */
    private suspend fun pickYoutubeFromCandidates(
        candidates: List<WebhookResponse.Video>, entry: DebugEntry
    ): WebhookResponse.Video? {
        val top = candidates.take(3)
        if (top.isEmpty()) return null

        val labels = listOf("หนึ่ง", "สอง", "สาม")
        val menuText = "เจอ ${top.size} รายการ " + top.mapIndexed { i, v ->
            "${labels[i]} ${v.title}"
        }.joinToString(" ") + " เอาอันไหนดี"

        repeat(2) { attempt ->
            speakAndRemember(if (attempt == 0) menuText else "ไม่ชัดครับ ลองอีกที $menuText")
            val ans = listenOnce(entry)
            when (val choice = NumberWordParser.parse(ans, top.size)) {
                is NumberWordParser.Choice.Index -> return top[choice.zeroBased]
                NumberWordParser.Choice.Cancel -> {
                    speakAndRemember("ยกเลิกแล้ว")
                    return null
                }
                NumberWordParser.Choice.None -> Unit  // ask again next iteration
            }
        }

        // Default per spec: open first + tell the rider we did.
        speakAndRemember("เปิดอันแรกให้นะครับ")
        return top.first()
    }

    private fun openYoutube(videoId: String?, query: String?, entry: DebugEntry) {
        fun view(uri: Uri) = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pm = context.packageManager

        if (videoId != null) {
            val app = view(Uri.parse("vnd.youtube:$videoId"))
            val web = view(Uri.parse("https://www.youtube.com/watch?v=$videoId"))
            val target = if (app.resolveActivity(pm) != null) app else web
            runCatching { context.startActivity(target) }
                .onFailure { entry.error = "youtube launch failed" }
            return
        }
        val q = query?.takeIf { it.isNotBlank() } ?: return
        val search = Intent(Intent.ACTION_SEARCH).apply {
            setPackage("com.google.android.youtube")
            putExtra("query", q)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val target = if (search.resolveActivity(pm) != null) search
            else view(Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(q)}"))
        runCatching { context.startActivity(target) }
            .onFailure { entry.error = "youtube search failed" }
    }

    // ─── FM radio ────────────────────────────────────────────────────────────

    private fun startFm(streamUrl: String, label: String, frequency: Double?) {
        memory.rememberStation(streamUrl, label, frequency)
        val intent = Intent(context, FmPlayerService::class.java)
            .setAction(FmPlayerService.ACTION_PLAY)
            .putExtra(FmPlayerService.EXTRA_STREAM_URL, streamUrl)
            .putExtra(FmPlayerService.EXTRA_LABEL, label)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        recordHistory(HistoryAction.FmPlay(streamUrl, label, frequency))
    }

    // ─── Audio helpers ────────────────────────────────────────────────────────

    private suspend fun connectSco(timeoutMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            btRouter.connect(timeoutMs) { ok ->
                if (cont.isActive) cont.resume(ok)
            }
            cont.invokeOnCancellation { runCatching { btRouter.disconnect() } }
        }

    /**
     * Listen once with automatic single retry on transient recognizer errors.
     * After TTS finishes the audio route needs a beat to switch back to mic — without
     * that beat the second-round STT (call confirmation / disambig / YouTube picker)
     * would fire ERROR_RECOGNIZER_BUSY or ERROR_CLIENT immediately and we'd treat it
     * as silence → auto-cancel. See bug report §1.
     */
    private suspend fun listenOnce(entry: DebugEntry?): String {
        // Small settle delay so TTS audio can fully drain and the recognizer isn't
        // still holding the mic from a previous session.
        delay(350)
        val first = listenOnceRaw(entry, isRetry = false)
        if (first.text.isNotBlank()) return first.text
        // Only retry when the failure was a transient recognizer error, not real silence.
        if (first.wasTransientError) {
            Log.d(TAG, "STT transient error — retrying once")
            delay(400)
            return listenOnceRaw(entry, isRetry = true).text
        }
        return first.text
    }

    private data class SttOutcome(val text: String, val wasTransientError: Boolean)

    private suspend fun listenOnceRaw(entry: DebugEntry?, isRetry: Boolean): SttOutcome =
        suspendCancellableCoroutine { cont ->
            recognizer?.destroy()
            val rec = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = rec
            val resumed = AtomicBoolean(false)
            val startedAt = System.currentTimeMillis()

            rec.setRecognitionListener(object : RecognitionListener {
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
                    if (!resumed.compareAndSet(false, true)) return
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: ""
                    if (cont.isActive) cont.resume(SttOutcome(text, wasTransientError = false))
                }

                override fun onError(error: Int) {
                    if (!resumed.compareAndSet(false, true)) return
                    Log.w(TAG, "STT error $error after ${System.currentTimeMillis() - startedAt}ms")
                    entry?.error = "STT $error"
                    // If it errored within 800ms it almost certainly never actually listened —
                    // classify as transient so the outer layer can retry once.
                    val transient = !isRetry && (System.currentTimeMillis() - startedAt) < 800 && error in setOf(
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                        SpeechRecognizer.ERROR_CLIENT,
                        SpeechRecognizer.ERROR_AUDIO,
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                    )
                    if (cont.isActive) cont.resume(SttOutcome("", wasTransientError = transient))
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "th-TH")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "th-TH")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                // Force at least 3s of listening so the user has time to respond to
                // confirmation prompts even if there's a moment of silence up front.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            runCatching { rec.startListening(intent) }.onFailure { err ->
                if (resumed.compareAndSet(false, true) && cont.isActive) {
                    entry?.error = "STT start failed: ${err.message}"
                    cont.resume(SttOutcome("", wasTransientError = !isRetry))
                }
            }

            cont.invokeOnCancellation {
                runCatching { rec.cancel() }
                runCatching { rec.destroy() }
                if (recognizer === rec) recognizer = null
            }
        }

    // ─── TTS + memory ────────────────────────────────────────────────────────

    /** Speak and remember for the "พูดอีกที" intercept. */
    private suspend fun speakAndRemember(text: String) {
        memory.lastSpoken = text
        tts.speakAwait(text)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun isConfirmed(text: String): Boolean {
        val t = text.lowercase().trim()
        if (t.contains("ไม่") || t.contains("ยกเลิก")) return false
        return t.contains("ใช่") || t.contains("โทร") || t.contains("ตกลง") ||
               t.contains("ok", ignoreCase = true) || t.contains("yes", ignoreCase = true) ||
               t.contains("เลย") || t.contains("ได้")
    }

    private fun finish() {
        if (!finished.compareAndSet(false, true)) return
        cleanup()
        onFinished()
    }

    fun stop() {
        if (finished.getAndSet(true)) return
        runJob?.cancel()
        cleanup()
    }

    private fun cleanup() {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { btRouter.disconnect() }
        runCatching { tts.stop() }
        scope.cancel()
    }

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
}

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
import com.moto.voice.audio.AudioFocusRouter
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
import com.moto.voice.debug.FinishReason
import com.moto.voice.nlu.ErrorSpeech
import com.moto.voice.media.FmPlaybackState
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
/** Spec §4.3: a 1-char final result is almost always wind or a stray beep, not a command. */
private const val MIN_MEANINGFUL_LEN = 2
/** Extract 11-char YouTube video id from anywhere in the string (bare id, URL, whatever). */
private val YOUTUBE_ID_EXTRACT = Regex("([A-Za-z0-9_-]{11})")

class VoiceCommandPipeline(
    private val context: Context,
    private val settings: AppSettings,
    private val onFinished: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val btRouter = BluetoothAudioRouter(context)
    private val focusRouter = AudioFocusRouter(context)
    private val tts = ThaiTTS(context)
    private val contactMatcher = ContactMatcher(context)
    private val memory = AppMemory(context)
    private val history = AppHistory(context)

    /** True if we paused our own FM at the start of this interaction — resume unless the command was "stop". */
    private var pausedOurFm: Boolean = false
    /** True if any webhook action explicitly stopped media — suppresses FM auto-resume. */
    private var stopAction: Boolean = false

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
            entry.finishReason = FinishReason.PHONE_UNAVAILABLE
            speakAndRemember(PhoneStateGuard.reasonText(availability))
            finish(); return
        }

        // Duck / pause other media so the mic doesn't record over YT, Spotify, etc.
        // (spec §2.1). Own-FM gets a soft pause so we can resume with metadata intact.
        focusRouter.request()
        if (FmPlaybackState.isPlaying) {
            Log.d(TAG, "pausing our FM for interaction")
            sendToFm(FmPlayerService.ACTION_PAUSE)
            FmPlaybackState.markAssistantPaused()
            pausedOurFm = true
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
        val text = listenMainWithMissRetry(entry)
        entry.sttTimeMs = System.currentTimeMillis() - t1
        entry.sttFinal = text

        if (text.isBlank()) {
            Earcon.error()
            entry.finishReason = FinishReason.NO_SPEECH
            speakAndRemember(ErrorSpeech.NOT_HEARD_GIVING_UP)
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
        entry.finishReason = FinishReason.INTERCEPTED
        when (intercept) {
            LocalIntercept.Intercept.Stop -> {
                stopAction = true
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
            entry.finishReason = FinishReason.LLM_OFF
            executeRuleBased(text, entry); return
        }

        // Progress markers so the rider knows we didn't hang. Fire at most once each.
        val progress1Spoken = AtomicBoolean(false)
        val progress2Spoken = AtomicBoolean(false)

        val client = WebhookClient(settings.webhookUrl, settings.authToken, settings.timeoutSeconds)
        val result = client.call(text) { elapsed ->
            when {
                elapsed <= 5_000L && progress1Spoken.compareAndSet(false, true) -> {
                    speakAndRemember(ErrorSpeech.THINKING)
                }
                elapsed > 5_000L && progress2Spoken.compareAndSet(false, true) -> {
                    speakAndRemember(ErrorSpeech.ONE_MORE_MOMENT)
                }
            }
        }
        entry.webhookResponse = result.rawJson
        entry.webhookTimeMs = result.elapsedMs

        when (result) {
            is WebhookClient.Result.Success -> {
                OfflineNotifier.onWebhookSuccess()
                val t2 = System.currentTimeMillis()
                executeWebhookAction(result.response, entry)
                entry.actionTimeMs = System.currentTimeMillis() - t2
                entry.finishReason = FinishReason.OK
                finish()
            }
            is WebhookClient.Result.Failure -> handleWebhookFailure(text, entry, result)
        }
    }

    /**
     * Per §1.4 / §6.2 / §6.3: pick a rider-informative TTS line based on the failure
     * type. For each failure we EITHER (a) speak the diagnostic then run rule-based
     * fallback for the parts we still can, OR (b) speak a self-contained line and stop
     * — never back-to-back diagnostics because the rider is on a bike and every extra
     * second matters. Never silent.
     */
    private suspend fun handleWebhookFailure(
        text: String, entry: DebugEntry, result: WebhookClient.Result.Failure
    ) {
        entry.error = "${result.kind}:${result.error}"
        Log.w(TAG, "webhook ${result.kind}: ${result.error}")

        val ruleMatches = CommandParser.parse(text) != null
        val stopMatches = LocalIntercept.match(text) is LocalIntercept.Intercept.Stop
        val canFallBack = ruleMatches || stopMatches

        entry.finishReason = when (result.kind) {
            WebhookClient.Kind.Timeout -> FinishReason.TIMEOUT_FALLBACK
            WebhookClient.Kind.Http401 -> FinishReason.HTTP_401
            WebhookClient.Kind.HttpOther -> FinishReason.HTTP_OTHER
            WebhookClient.Kind.Network -> FinishReason.NETWORK
            WebhookClient.Kind.Parse -> FinishReason.PARSE_ERROR
        }

        when (result.kind) {
            WebhookClient.Kind.Timeout -> {
                if (canFallBack) {
                    speakAndRemember(ErrorSpeech.TIMEOUT_WITH_FALLBACK)
                    executeRuleBased(text, entry)  // executes the matched intent, no "ไม่เข้าใจ" branch
                } else {
                    speakAndRemember(ErrorSpeech.TIMEOUT_NO_FALLBACK)
                    finish()
                }
            }
            WebhookClient.Kind.Http401 -> {
                // Auth issue can't be worked around by rules; give the rider one clear line.
                speakAndRemember(ErrorSpeech.HTTP_401)
                finish()
            }
            WebhookClient.Kind.HttpOther -> {
                speakAndRemember(ErrorSpeech.HTTP_OTHER)
                if (canFallBack) executeRuleBased(text, entry) else finish()
            }
            WebhookClient.Kind.Network, WebhookClient.Kind.Parse -> {
                if (OfflineNotifier.shouldAnnounce()) speakAndRemember(ErrorSpeech.OFFLINE_LIMITED)
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
                stopAction = true
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
        if (!settings.confirmBeforeCall) {
            speakAndRemember(msg)
            makeCall(contact)
            return
        }

        // Confirmation flow: prompt, listen for a positive/negative reply. If the reply
        // clearly says "ไม่/ยกเลิก" we cancel; otherwise (positive keyword OR unusable
        // response OR silence) we CALL — riders don't have time to re-do this on a bike
        // and they can always cancel the outgoing call from the phone dialer.
        speakAndRemember("$msg พูด ยกเลิก เพื่อยกเลิก")
        val answer = listenOnce(null)
        val explicitCancel = answer.contains("ไม่") || answer.contains("ยกเลิก") || answer.contains("cancel", ignoreCase = true)
        if (explicitCancel) speakAndRemember("ยกเลิกแล้ว")
        else makeCall(contact)
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

        // No usable video id from the webhook.
        // Deliberately DO NOT open YouTube search — while riding you can't tap results
        // and being dumped in YouTube is worse than being told to try again. Just speak
        // an actionable retry hint and let the rider re-trigger.
        val hint = "หาวิดีโอไม่เจอ ลองพูดชื่อเจาะจงกว่านี้แล้วกดปุ่มลองใหม่"
        speakAndRemember(hint)
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
        // A fresh play command supersedes any auto-resume that would otherwise happen.
        pausedOurFm = false
        FmPlaybackState.clearAssistantPaused()

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

    /** Best-effort control message to FmPlayerService. Idempotent; safe when service isn't running. */
    private fun sendToFm(action: String) {
        val intent = Intent(context, FmPlayerService::class.java).setAction(action)
        runCatching { context.startService(intent) }
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
     * Convenience wrapper for callers that only care about the recognised text
     * (confirm / disambig / picker paths). Applies the settle delay + retries once on
     * transient recognizer errors. Does NOT retry on real silence — for that use
     * [listenMainWithMissRetry] which speaks a prompt between attempts.
     */
    private suspend fun listenOnce(entry: DebugEntry?): String = listenOnceDetailed(entry).text

    /** Exposes the outcome so the main-flow retry logic can decide what to do next. */
    private suspend fun listenOnceDetailed(entry: DebugEntry?): SttOutcome {
        // Settle delay so TTS audio finishes draining and the recognizer isn't still
        // holding the mic from a previous session. See bug report §1.
        delay(350)
        val first = listenOnceRaw(entry, isRetry = false)
        if (first.text.isNotBlank()) return first
        if (first.wasTransientError) {
            Log.d(TAG, "STT transient error — retrying once")
            delay(400)
            return listenOnceRaw(entry, isRetry = true)
        }
        return first
    }

    /**
     * Main-STT flavour: if the first attempt hears nothing (NO_MATCH / SPEECH_TIMEOUT /
     * result too short to be a real command), automatically prompt with
     * "ไม่ได้ยินค่ะ พูดอีกครั้งนะคะ" and listen once more — the rider doesn't need to
     * press the button again. Spec §4.1 + §4.3.
     *
     * Only applied to the main STT of an interaction; confirm / disambig / picker
     * listens have their own re-prompt loops and shouldn't nest another retry inside.
     */
    private suspend fun listenMainWithMissRetry(entry: DebugEntry): String {
        val first = listenOnceDetailed(entry)
        val firstText = first.text.trim()
        if (firstText.length >= MIN_MEANINGFUL_LEN) return firstText

        // A permission/audio problem won't be helped by asking the rider to speak up.
        // Only re-prompt for real "no speech heard" outcomes (or a too-short result,
        // which is common when wind noise gets recognised as a single syllable).
        val shouldPrompt = first.wasNoSpeech || firstText.isNotEmpty()
        if (!shouldPrompt) return ""

        entry.sttRetryCount = 1
        speakAndRemember(ErrorSpeech.NOT_HEARD_RETRY)
        val second = listenOnceDetailed(entry)
        val secondText = second.text.trim()
        return if (secondText.length < MIN_MEANINGFUL_LEN) "" else secondText
    }

    private data class SttOutcome(
        val text: String,
        val wasTransientError: Boolean,
        val wasNoSpeech: Boolean = false,
        val confidence: Float = -1f,
    )

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
                    // Confidence of the top-1 result if the engine provided any (§4.4).
                    val conf = results
                        ?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                        ?.firstOrNull() ?: -1f
                    if (entry != null && conf >= 0f) entry.sttConfidence = conf
                    Log.d(TAG, "final: '$text' confidence=$conf")
                    if (cont.isActive) cont.resume(
                        SttOutcome(text, wasTransientError = false, wasNoSpeech = false, confidence = conf)
                    )
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
                    val noSpeech = error == SpeechRecognizer.ERROR_NO_MATCH ||
                                   error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    if (cont.isActive) cont.resume(
                        SttOutcome("", wasTransientError = transient, wasNoSpeech = noSpeech)
                    )
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "th-TH")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "th-TH")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                // §4.2: shorter complete-silence so the rider isn't left hanging in wind
                // noise; minimum-length still 3s so slow speakers get a fair chance.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
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

        // Resume our FM if WE paused it AND the command wasn't a stop — spec §2.2.
        if (pausedOurFm && !stopAction) {
            Log.d(TAG, "resuming our FM after interaction")
            sendToFm(FmPlayerService.ACTION_RESUME)
        } else if (pausedOurFm && stopAction) {
            // Stop was commanded: clear the assistant-paused flag so FM stays down.
            FmPlaybackState.clearAssistantPaused()
        }
        pausedOurFm = false

        runCatching { focusRouter.abandon() }
        runCatching { btRouter.disconnect() }
        runCatching { tts.stop() }
        scope.cancel()
    }

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
}

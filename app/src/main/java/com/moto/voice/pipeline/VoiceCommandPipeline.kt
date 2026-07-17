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
import com.moto.voice.audio.AudioFocusRouter
import com.moto.voice.audio.BluetoothAudioRouter
import com.moto.voice.audio.CellularCheck
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
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import com.moto.voice.debug.AudioModeName
import com.moto.voice.debug.AudioRoute
import com.moto.voice.debug.DebugEntry
import com.moto.voice.debug.DebugLog
import com.moto.voice.debug.FinishReason
import com.moto.voice.debug.ScoState
import com.moto.voice.nlu.ErrorSpeech
import com.moto.voice.media.FmPlaybackState
import com.moto.voice.media.FmPlayerService
import com.moto.voice.media.MediaOrchestrator
import com.moto.voice.network.WebhookClient
import com.moto.voice.network.WebhookResponse
import com.moto.voice.nlu.LocalIntercept
import com.moto.voice.nlu.NumberWordParser
import com.moto.voice.nlu.CommandParser
import com.moto.voice.nlu.RandomOpener
import com.moto.voice.nlu.SlotFiller
import com.moto.voice.nlu.VoiceCommand
import com.moto.voice.tts.ThaiTTS
import com.moto.voice.tts.TtsRecentSpeech
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

private const val TAG = "VoiceCommandPipeline"
private const val HIGH_CONF = 0.75f
/** Spec §4.3: a 1-char final result is almost always wind or a stray beep, not a command. */
private const val MIN_MEANINGFUL_LEN = 2
/** Extract 11-char YouTube video id from anywhere in the string (bare id, URL, whatever). */
private val YOUTUBE_ID_EXTRACT = Regex("([A-Za-z0-9_-]{11})")
/** Default STT minimum-listen window (spec §4.2). */
private const val DEFAULT_MIN_LISTEN_MS = 3_000L
/**
 * v1.3.27 — silence after a prompt's answer-listen beep before the mic opens. Rider
 * preference (2026-07-17): prompts/re-listens pace SERIALLY — speak the whole prompt →
 * beep → this clear gap → open mic — so TTS, earcon, and "your turn" don't pile up (the
 * "ประโยคติดกัน / พูดหลังสัญญาณ" confusion). Longer than [com.moto.voice.audio.Earcon.MIC_OPEN_GAP_MS]
 * (150ms, echo-tail guard) because this gap is a deliberate "your turn now" beat.
 */
private const val PROMPT_SETTLE_MS = 300L
/**
 * Disambiguation (contact / YouTube picker) needs longer for the rider to hear + reply.
 * Bumped 6000 → 8000 in v1.3.9 §3 — field feedback that riders on the highway need
 * a beat longer to process "did she say หนึ่ง or สอง" before answering.
 */
private const val DISAMBIG_MIN_LISTEN_MS = 8_000L
/**
 * Confirm-listen (confirmThenCall's "ใช่ไหมคะ" answer window). Was DEFAULT_MIN_LISTEN_MS
 * (3s) which the rider spec calls out as too fast for a bike. Bumped to match disambig.
 */
private const val CONFIRM_MIN_LISTEN_MS = 8_000L
/**
 * Spec v1.3.9 §3 — when the listen window has this many ms left with no partial STT
 * captured yet, fire a soft answer-listen beep as "still waiting, you're not alone".
 * Only for confirm/disambig style prompts where the window is long enough for it to
 * be useful.
 */
private const val ANSWER_LISTEN_REMINDER_MS = 2_000L
/** Spoken slot number for voice-call favorites — matches order of FavoritesStore slots 0..4. */
private val FAVORITE_SLOT_WORDS = listOf("หนึ่ง", "สอง", "สาม", "สี่", "ห้า")
// v1.3.20 sprint — YouTube-nudge timing constants relocated into
// [com.moto.voice.media.MediaOrchestrator] (single owner of media ops).
/**
 * Spec v1.3.8 A2 — 2 consecutive non-silence STT errors is when we start suspecting
 * the platform recognizer service is degrading and force the throttle+recreate path.
 * Lower would be too twitchy (a single ERROR_AUDIO can happen naturally); higher would
 * miss the field-report pattern where the S24 loops errors 7/8 back-to-back.
 */
private const val STT_RECREATE_THRESHOLD = 2
/** Spec v1.3.8 A3 — hard per-interaction ceiling. Longer than the SCO + STT + webhook + action budgets combined. */
private const val INTERACTION_WATCHDOG_MS = 45_000L
/**
 * Spec v1.3.8 B2 — how long the follow-up window listens for a follow-up command.
 * Short — the rider is on a bike.
 *
 * v1.3.14 — restored to 4000ms after rider feedback that v1.3.13's 3000 + mid-
 * window ping combo felt worse. Need targeted follow-up on what specifically
 * felt off before adjusting timings again.
 */
private const val FOLLOWUP_LISTEN_MS = 4_000L

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
    /** The debug entry currently owned by [runPipeline] — retained for [markBargeIn]. */
    private var currentEntry: DebugEntry? = null

    /**
     * Spec v1.3.8 A2 — count of consecutive non-NO_MATCH / non-TIMEOUT STT errors.
     * Reset on any successful listen. Cross-listen state so long sessions where the
     * platform SpeechRecognizer service degrades (ERROR 7 / ERROR 8 loops observed on
     * S24 during multi-command bursts) can be caught and throttled: at ≥2 we insert
     * an extra 400ms breather before the next listen so the OS-side service has time
     * to recycle its internal state.
     */
    private var consecutiveSttErrors: Int = 0

    /**
     * v1.3.10 — classification of the MOST RECENT listen's outcome. Set inside
     * [listenOnceRaw]'s callbacks. Consumed by [maybeSlotFill] (and available to
     * any other caller that needs to distinguish "silence" from "server error"
     * from "some other STT problem"). Reset to [ListenErrorClass.None] on
     * successful results.
     *
     * Server-error family is separate because a server disconnect during a
     * question prompt is recoverable — the pipeline should retry the listen
     * once and tell the rider "server hiccup, try again" instead of the
     * indistinguishable "ยกเลิกแล้ว" line that used to fire on any blank
     * outcome (field report: rider heard cancel and thought they were rejected).
     */
    private var lastListenErrorClass: ListenErrorClass = ListenErrorClass.None

    private enum class ListenErrorClass {
        /** No error — result available or reset. */
        None,
        /** ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT — rider was silent, not a server problem. */
        NoSpeech,
        /** ERROR_NETWORK / ERROR_NETWORK_TIMEOUT / ERROR_SERVER / ERROR_SERVER_DISCONNECTED. */
        ServerError,
        /** Everything else — busy, audio, client, permissions, unknown. */
        Other,
    }

    /**
     * Spec v1.3.8 B2 — set to true by action handlers whose reply is conversational
     * (chat, none, cancelled call, stop). runFollowUpWindow reads this after
     * executeWebhookAction returns and decides whether to open the 4-second listen.
     * Media handlers (youtube_play, fm success) leave it false because the media
     * itself will be playing and we can't listen over it.
     */
    private var followupEligible: Boolean = false

    /**
     * Spec v1.3.9 §1.3 — set to true when a media handler (youtube_play with a
     * valid video, fm with a valid stream URL, ResumeLastRadio, NextVideo) actually
     * fires playback. Consumed by [runPipeline]'s finally block to decide whether
     * to fire the end-interaction earcon: media is its own "we're done listening"
     * signal, so we skip the tone when it will play immediately after.
     */
    private var mediaActionStarted: Boolean = false

    /**
     * Spec v1.3.9 §2.3 — set to true by [withTeachingHint] when at least one prompt
     * this interaction actually appended the teaching hint. Consumed once at the end
     * of the interaction (in [runPipeline]'s finally) to decrement the budget by 1,
     * regardless of how many prompts the interaction had. Per-interaction budgeting
     * (not per-prompt) matches spec §2.3 "10 interaction แรก".
     */
    private var teachingHintFired: Boolean = false

    /** True from start() until finish()/stop() runs. Read by VoiceCommandService to decide barge-in vs new interaction. */
    val isActive: Boolean get() = runJob?.isActive == true && !finished.get()

    fun start() {
        if (runJob?.isActive == true) return
        runJob = scope.launch { runPipeline() }
    }

    /**
     * Called from VoiceCommandService before [stop] on a barge-in cancel so the
     * DebugEntry reflects the reason. The service is responsible for playing the
     * cancel earcon — we can't do it from here because our scope is about to die.
     */
    fun markBargeIn() {
        currentEntry?.finishReason = FinishReason.BARGE_IN
        currentEntry?.error = (currentEntry?.error ?: "") + (if (currentEntry?.error.isNullOrEmpty()) "" else "; ") + "barge_in_cancel"
    }

    private suspend fun runPipeline() {
        val entry = DebugLog.new()
        currentEntry = entry
        try {
            // Spec v1.3.8 A3 — hard 45s ceiling on the entire interaction. Longer than
            // the sum of SCO connect (~300ms) + STT window (~10s) + webhook (~15s) +
            // action (~5s) + slot-fill loop, so a legitimate interaction never trips
            // this. A null return means something is wedged; finishReason gets marked
            // and the finally block below tears everything down cleanly.
            val completed = withTimeoutOrNull(INTERACTION_WATCHDOG_MS) {
                runPipelineBody(entry)
                true
            }
            if (completed == null) {
                Log.w(TAG, "interaction watchdog fired at ${INTERACTION_WATCHDOG_MS}ms — force-resetting")
                entry.finishReason = FinishReason.WATCHDOG_RESET
                entry.error = ((entry.error ?: "") + " watchdog_reset").trim()
                runCatching { Earcon.cancel() }
            }
        } catch (t: Throwable) {
            // v1.3.11 — CancellationException MUST propagate unmarked. It is
            // structured-concurrency's "the parent scope is winding down" signal,
            // not an error. Every field log from v1.3.9 onward showed the noise
            // "runPipeline_threw:JobCancellationException" on every successful
            // interaction because cleanup() → scope.cancel() triggers it at the
            // withTimeoutOrNull boundary. Rethrow directly so the finally still
            // runs but the debug entry stays clean.
            if (t is CancellationException) throw t
            // Spec §1.1 — unexpected non-cancellation throw must NOT leave SCO holding
            // the audio route. Log the reason so the field can tell that finally saved
            // us and it wasn't a normal exit. finally block below runs cleanup
            // unconditionally.
            Log.e(TAG, "runPipeline threw — running finally cleanup", t)
            entry.error = ((entry.error ?: "") + " runPipeline_threw:${t.javaClass.simpleName}").trim()
            throw t
        } finally {
            // Spec v1.3.9 §4 — final visual transition to Idle. The circle in
            // RidingModeActivity goes gray at the same moment the endInteraction
            // earcon fires below (audio + visual match).
            PipelineState.setIdle()
            // Spec v1.3.9 §2.3 — decrement teaching-hint budget by 1 per interaction
            // (not per prompt) when at least one prompt appended the hint.
            if (teachingHintFired) {
                runCatching { settings.teachingUsesLeft = settings.teachingUsesLeft - 1 }
            }
            // Spec v1.3.9 §1.3 — every non-media exit fires the end-interaction tone
            // so the rider knows the mic is closed and BVRA is required to talk again.
            // Skip if:
            //   * media (youtube_play, fm success) is playing — the media sound itself
            //     is the "we're done" signal;
            //   * we already fired cancel (barge-in / watchdog) — that motif already
            //     communicates the same "we stopped listening" meaning and doubling
            //     up would sound like a bug;
            //   * the follow-up window's silent-timeout branch already fired the tone.
            val alreadySignalled = entry.finishReason == FinishReason.WATCHDOG_RESET ||
                entry.finishReason == FinishReason.BARGE_IN
            if (!mediaActionStarted && !alreadySignalled) {
                runCatching { Earcon.endInteraction() }
            }
            // finish() no-ops if already called by one of the explicit branches, so
            // callers still get a single onFinished() event but cleanup() is guaranteed
            // to run — SCO teardown, MODE_NORMAL, focus abandon, TTS stop.
            finish()
        }
    }

    private suspend fun runPipelineBody(entry: DebugEntry) {
        val t0 = System.currentTimeMillis()

        val availability = PhoneStateGuard.availability(context)
        if (availability != PhoneStateGuard.Availability.Available) {
            entry.error = "phone unavailable: $availability"
            entry.finishReason = FinishReason.PHONE_UNAVAILABLE
            speakAndRemember(PhoneStateGuard.reasonText(availability))
            return
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
        entry.audioRoute = if (scoOk) AudioRoute.SCO else AudioRoute.PHONE
        entry.scoState = resolveScoState(hasBt, scoOk)

        // Tell the rider we're falling back to the phone mic — but only when we tried and
        // failed. Skip the announcement entirely when there's no BT permission (no helmet expected).
        if (hasBt && !scoOk) {
            tts.speakAwait("ใช้ไมค์โทรศัพท์")
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            entry.error = "no speech recognition service"
            Earcon.error()
            speakAndRemember("อุปกรณ์ไม่รองรับการรับเสียง")
            return
        }

        Earcon.ready()
        delay(Earcon.MIC_OPEN_GAP_MS)  // spec §1.4 — earcon decay tail out before mic opens

        PipelineState.setListening()  // spec v1.3.9 §4 — visual match to audio
        val t1 = System.currentTimeMillis()
        val text = listenMainWithMissRetry(entry)
        PipelineState.setThinking()
        entry.sttTimeMs = System.currentTimeMillis() - t1
        entry.sttFinal = text

        if (text.isBlank()) {
            Earcon.error()
            entry.finishReason = FinishReason.NO_SPEECH
            speakAndRemember(ErrorSpeech.NOT_HEARD_GIVING_UP)
            return
        }

        // Global self-echo guard (spec bug-2 round-2): if the main STT captured
        // something that closely matches TTS the app just spoke — from FmPlayerService,
        // HelmetGreeter, preflight, etc. — drop it. This catches cross-component echoes
        // that the per-prompt filter can't see because they didn't originate here.
        if (TtsEchoFilter.isSelfEcho(text)) {
            Log.w(TAG, "self-echo detected on main STT — dropping '$text'")
            entry.error = ((entry.error ?: "") + " self_echo").trim()
            entry.finishReason = FinishReason.SELF_ECHO
            Earcon.error()
            speakAndRemember(ErrorSpeech.NOT_HEARD_GIVING_UP)
            return
        }

        // v1.3.9 spec §1 — the old "processing" earcon here was redundant with the
        // TTS reply that immediately follows. Skipped now so the audio language
        // stays 3-signal (ready / answerListen / endInteraction) instead of 4.

        // Spec v1.3.6 §2 — bare-opener slot-filling. Runs before intercept match so
        // "เปิด YouTube" without payload triggers our follow-up instead of hitting the
        // webhook with an unactionable snippet (log 1783581952116 evidence: "เปิด YouTube"
        // three interactions in a row, none ever gave a query the webhook could act on).
        val effectiveText = maybeSlotFill(text, entry) ?: return
        heardText = effectiveText
        entry.sttFinal = effectiveText

        // Local intercept ALWAYS runs before webhook — offline-first, sub-second response.
        when (val intercept = LocalIntercept.match(effectiveText)) {
            is LocalIntercept.Intercept.None -> processText(effectiveText, entry)
            else -> handleIntercept(intercept, entry)
        }
    }

    /**
     * Bare-opener slot-filling — spec v1.3.6 §2.
     *
     * If [originalText] normalises to a bare opener with no payload (see [SlotFiller]),
     * ask a single follow-up through [promptAndListen] (the central listen loop — spec §3
     * prohibits any separate listen path here because the echo-guard round-trips
     * through it) and combine the answer into a full sentence.
     *
     *   returns non-null → the sentence the pipeline should process (either the
     *     original if no slot-fill was needed, or the combined form)
     *   returns null      → the rider cancelled or gave a blank second answer; the
     *     caller must NOT continue running the pipeline (we already spoke CANCELLED)
     *
     * "เปิดวิทยุ" with a saved last station is a special case: we let
     * [LocalIntercept.ResumeLastRadio] handle it (no follow-up needed, we know what
     * to resume). Only slot-fill radio when there's no last-station memory.
     */
    private suspend fun maybeSlotFill(originalText: String, entry: DebugEntry): String? {
        val normalized = LocalIntercept.normalize(originalText)
        val need = SlotFiller.detect(normalized)
        if (need == SlotFiller.Need.None) return originalText
        if (need == SlotFiller.Need.RadioStation && !memory.lastStationUrl.isNullOrBlank()) {
            return originalText
        }

        entry.slotFilled = true
        entry.finishReason = FinishReason.SLOT_FILLED

        val firstAnswer = promptAndListen(withTeachingHint(SlotFiller.promptFor(need)), entry).trim()

        // Explicit cancel wins over everything else — spec v1.3.7.
        if (SlotFiller.isCancelAnswer(firstAnswer)) {
            speakAndRemember(ErrorSpeech.CANCELLED)
            return null
        }
        if (firstAnswer.isNotBlank()) return SlotFiller.combine(need, firstAnswer)

        // v1.3.10 — blank first answer. Distinguish "rider went silent" (accept
        // the cancel semantic) from "Google STT server disconnected mid-listen"
        // (retry once — field report of "เปิด YouTube → cancelled immediately"
        // was actually STT ERROR_SERVER_DISCONNECTED, not a rider cancel).
        val firstErrorClass = lastListenErrorClass
        if (firstErrorClass != ListenErrorClass.ServerError) {
            speakAndRemember(ErrorSpeech.CANCELLED)
            return null
        }

        Log.w(TAG, "slot-fill first listen hit STT server error — retrying once")
        delay(500L)  // let the recognizer service reconnect
        // Retry with the plain prompt (no teaching hint) — keeps the retry short
        // so the rider isn't stuck in a slot-fill loop on a bike.
        val retryAnswer = promptAndListen(SlotFiller.promptFor(need), entry).trim()
        if (SlotFiller.isCancelAnswer(retryAnswer)) {
            speakAndRemember(ErrorSpeech.CANCELLED)
            return null
        }
        if (retryAnswer.isNotBlank()) return SlotFiller.combine(need, retryAnswer)

        // Still nothing — differentiate the message so the rider knows whether
        // the assistant heard silence or hit a server problem.
        val retryErrorClass = lastListenErrorClass
        val line = if (retryErrorClass == ListenErrorClass.ServerError)
            ErrorSpeech.SERVER_UNAVAILABLE
        else
            ErrorSpeech.CANCELLED
        speakAndRemember(line)
        return null
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
                executeStopSequence()
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
                    releaseScoBeforeMedia(entry)
                    startFm(url, name, memory.lastStationFrequency)
                    mediaActionStarted = true  // spec v1.3.9 §1.3
                }
            }
            LocalIntercept.Intercept.CallBackLast -> {
                val number = memory.lastCallNumber
                val name = memory.lastCallName ?: "เบอร์ล่าสุด"
                if (number.isNullOrBlank()) speakAndRemember("ยังไม่มีเบอร์ล่าสุดในแอปนี้")
                else confirmThenCall(ContactEntry(id = "last", displayName = name, phoneNumber = number), null)
            }
            is LocalIntercept.Intercept.CallFavorite -> handleFavoriteCall(intercept.zeroBasedSlot)
            LocalIntercept.Intercept.NextVideo -> handleNextVideo(entry)
            LocalIntercept.Intercept.WhatIsPlaying -> handleWhatIsPlaying()
            is LocalIntercept.Intercept.Seek -> handleSeek(intercept.deltaSeconds, entry)
            is LocalIntercept.Intercept.PlayContinue -> handlePlayContinue(intercept.appHint, entry)
            LocalIntercept.Intercept.None -> Unit  // handled in caller
        }
        finish()
    }

    /**
     * v1.3.20 sprint rule #2 — "เล่นต่อ" / "เล่น YouTube ต่อ". Delegates to
     * [MediaOrchestrator] which picks the target from either [appHint] or
     * [com.moto.voice.media.MediaSessionMemory.lastOpenedApp], and refires the
     * deep link if the target has no session (no more blind media-key dispatch).
     */
    private suspend fun handlePlayContinue(appHint: String?, entry: DebugEntry) {
        MediaOrchestrator.speakPlayConfirmed = settings.confirmMediaStart
        val result = MediaOrchestrator.playContinue(context, appHint, entry)
        when (result) {
            is MediaOrchestrator.Result.NoTarget ->
                speakAndRemember(ErrorSpeech.WHAT_IS_PLAYING_NONE)
            is MediaOrchestrator.Result.Success -> {
                // Refired deep link — nudge polls until STATE_PLAYING then speaks
                // MEDIA_PLAY_CONFIRMED itself. Speaking here would double up.
            }
            else -> speakAndRemember(ErrorSpeech.MEDIA_PLAY_CONFIRMED)
        }
    }

    /**
     * v1.3.11 §2 — dispatch a signed-seconds seek. All routing (target lookup,
     * media-key fallback rules, no-permission handling) lives in [MediaOrchestrator]
     * as of v1.3.20 sprint rule #1.
     */
    private suspend fun handleSeek(deltaSeconds: Int, entry: DebugEntry) {
        val result = MediaOrchestrator.seek(context, deltaSeconds, appHint = null, entry = entry)
        when (result) {
            is MediaOrchestrator.Result.CommandDispatched ->
                speakAndRemember(if (deltaSeconds >= 0) "เลื่อนไปข้างหน้าให้แล้ว" else "ย้อนกลับให้แล้ว")
            is MediaOrchestrator.Result.MediaKeyFallback ->
                speakAndRemember(ErrorSpeech.SEEK_ATTEMPTED)
            else -> speakAndRemember(ErrorSpeech.WHAT_IS_PLAYING_NONE)
        }
    }

    /**
     * Spec v1.3.8 B5 — "อันต่อไป" / "เปลี่ยน" etc. Uses [MediaSessionMemory] to find
     * the next entry in the last webhook's `videos` array. If the list is exhausted
     * or no video has been opened yet this session, speaks the short exhaust line
     * instead of firing an empty intent.
     */
    private suspend fun handleNextVideo(entry: DebugEntry) {
        val next = com.moto.voice.media.MediaSessionMemory.nextVideo()
        if (next == null) {
            speakAndRemember(ErrorSpeech.NEXT_VIDEO_EXHAUSTED)
            return
        }
        val title = next.title.ifBlank { "อันต่อไป" }
        speakAndRemember("กำลังเปิด $title")
        com.moto.voice.media.MediaSessionMemory.advanceTo(next)
        releaseScoBeforeMedia(entry)
        MediaOrchestrator.speakPlayConfirmed = settings.confirmMediaStart
        MediaOrchestrator.openYoutube(context, next.id, null, entry, expectedTitle = next.title)
        recordHistory(HistoryAction.YoutubeOpen(next.id, next.title))
        mediaActionStarted = true  // spec v1.3.9 §1.3
    }

    /** Spec v1.3.8 B5 — "เมื่อกี้อะไร" / "เล่นอะไรอยู่". */
    private suspend fun handleWhatIsPlaying() {
        val title = com.moto.voice.media.MediaSessionMemory.currentTitle()
        if (title.isBlank()) speakAndRemember(ErrorSpeech.WHAT_IS_PLAYING_NONE)
        else speakAndRemember("เมื่อกี้ $title")
    }

    /**
     * Voice-triggered Favorites (spec §2). Slot list is 0-based (0..4). Empty slot →
     * dedicated TTS explaining how to fill it. Populated slot → confirm-flow, TTS
     * announces the real name so the rider can hear which contact will be dialled.
     *
     * Resolution order (spec §2.3 — added after v1.3.4 field report of ID drift after
     * account sync):
     *   1. **Contact-ID exact match.** Look up the fav.displayName in the phone book,
     *      keep only the match whose contact.id == fav.contactId. Best because the
     *      number is guaranteed current.
     *   2. **Name-only match.** Any high-scoring match on the display name — the ID
     *      moved but the name is unchanged, common after a Google sync merge.
     *   3. **Stored phone-number fallback.** Even if the contact was DELETED from the
     *      phone book, we can still dial the number we captured at pick time. This is
     *      the "reboot then favorite gone" symptom the rider reported — the favorite
     *      IS still there, we just refuse to call because Contacts had drifted.
     *   4. Only after all three fail do we speak the "ไม่พบเบอร์" line.
     */
    private suspend fun handleFavoriteCall(zeroBasedSlot: Int) {
        val favs = com.moto.voice.data.FavoritesStore(context).list()
        val slotNumber = zeroBasedSlot + 1
        val slotWord = FAVORITE_SLOT_WORDS.getOrElse(zeroBasedSlot) { "$slotNumber" }
        if (zeroBasedSlot !in favs.indices) {
            speakAndRemember("ยังไม่ได้ตั้งรายการโปรดหมายเลข${slotWord}ค่ะ ตั้งได้ในแอปนะคะ")
            return
        }
        val fav = favs[zeroBasedSlot]

        val target = if (hasPerm(Manifest.permission.READ_CONTACTS)) {
            val matches = contactMatcher.findMatches(fav.displayName)
            matches.firstOrNull { it.contact.id == fav.contactId }?.contact
                ?: matches.firstOrNull()?.contact
        } else null

        val resolved = target
            ?: fav.phoneNumber?.takeIf { it.isNotBlank() }?.let {
                // Fallback: dial the number we stored at pick time. Use fav.displayName
                // so the confirmation TTS still names the right person.
                ContactEntry(id = fav.contactId, displayName = fav.displayName, phoneNumber = it)
            }

        if (resolved == null) {
            speakAndRemember("ไม่พบเบอร์ของ ${fav.displayName} ในเครื่อง")
            return
        }
        confirmThenCall(resolved, "จะโทรหา${resolved.displayName} รายการโปรดหมายเลข$slotWord ใช่ไหมคะ")
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
                // Spec v1.3.8 B2 — after finish-eligible action handlers, open a 4s
                // follow-up window. Media handlers (youtube_play, fm) leave
                // followupEligible = false so this is a no-op there.
                if (settings.followupEnabled && followupEligible) {
                    runFollowUpWindow(entry)
                }
                finish()
            }
            is WebhookClient.Result.Failure -> handleWebhookFailure(text, entry, result)
        }
    }

    /**
     * Spec v1.3.8 B2 — 4-second passive listen after a conversational reply.
     *
     * Rider path: TTS reply finishes → soft "ready" earcon → mic opens for 4s → if
     * something is captured, it's re-entered through the normal intercept + webhook
     * path AS IF the rider had pressed BVRA again. Silence → soft end earcon → done.
     *
     * Spec §3 constraint — must go through [listenOnce] (the central listen loop) so
     * the echo guard round-trip via [TtsRecentSpeech] catches our own TTS. Never
     * create a bespoke listen path here.
     *
     * Non-recursive by design — set followupEligible=false BEFORE re-entering, so a
     * follow-up chat that also flags eligible won't open another follow-up (that
     * would let the assistant chatter until the rider silences it manually).
     */
    private suspend fun runFollowUpWindow(entry: DebugEntry) {
        // Spec v1.3.9 §1.2 — the follow-up window is exactly the "waiting for your
        // reply" state, so it gets the dual-beep answerListen, not the ready tone
        // that would signal "new interaction start".
        Earcon.answerListen()
        delay(Earcon.MIC_OPEN_GAP_MS)  // spec §1.4 — don't let the tail hit the mic
        PipelineState.setListening()  // spec v1.3.9 §4
        // v1.3.14 — reverted v1.3.13's pre-listen breathe + mid-window ping +
        // shortened window. Back to a plain listenOnce over the full 4s window
        // per rider feedback that the v1.3.13 changes felt worse than v1.3.12.
        val text = listenOnce(entry, minListenMs = FOLLOWUP_LISTEN_MS)
        PipelineState.setThinking()
        if (text.isBlank()) {
            // Spec §1.3 — silent-timeout is a real interaction exit; fire the
            // end tone so the rider knows the mic is closed and they need BVRA to
            // talk again. The finally-block hook in runPipeline would also fire it,
            // but the earlier fire here means the tone lands right when the mic
            // actually closes.
            Earcon.endInteraction()
            return
        }
        if (TtsEchoFilter.isSelfEcho(text)) {
            Log.d(TAG, "followup captured self-echo — treating as silence")
            Earcon.endInteraction()
            return
        }
        Log.d(TAG, "followup captured: '$text' — re-entering pipeline")
        entry.finishReason = FinishReason.FOLLOWUP_COMMAND
        entry.followupUsed = true
        heardText = text
        entry.sttFinal = text
        followupEligible = false  // one follow-up per interaction — no chatter loops
        when (val i = LocalIntercept.match(text)) {
            is LocalIntercept.Intercept.None -> processText(text, entry)
            else -> handleIntercept(i, entry)
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
        val action = resp.action.lowercase()

        // Spec v1.3.8 A5 — dedupe the "user stuttered / helmet button double-tapped"
        // pattern. Only side-effecting actions are guarded; "none" is a pure-TTS reply
        // that's fine to repeat, and we don't want the dedupe to eat a legitimate
        // second "หยุด" if the first stop didn't take.
        val payloadForDedupe = when (action) {
            "call" -> resp.contact
            "youtube_play" -> resp.videoId ?: resp.query
            "fm" -> resp.streamUrl ?: resp.frequency?.toString()
            else -> null
        }
        if (payloadForDedupe != null) {
            val key = DedupeGuard.keyOf(action, payloadForDedupe)
            if (DedupeGuard.isRecentDuplicate(key)) {
                Log.d(TAG, "dedupe hit for $key — swallowing duplicate action")
                entry.finishReason = FinishReason.DUPLICATE_ACTION
                speakAndRemember(ErrorSpeech.ACTION_IN_PROGRESS)
                return
            }
            DedupeGuard.markExecuted(key)
        }

        when (action) {
            "call" -> {
                val name = resp.contact?.takeIf { it.isNotBlank() } ?: resp.speak
                handleCallByName(name, resp.speak)
            }
            "youtube_play" -> handleYoutube(resp, entry)
            "fm" -> {
                if (!resp.streamUrl.isNullOrBlank()) {
                    val name = resp.stationName ?: resp.frequency?.let { "FM $it" } ?: "วิทยุ"
                    speakAndRememberWithOpener(resp.speak.ifBlank { "กำลังเปิด $name" })
                    releaseScoBeforeMedia(entry)
                    startFm(resp.streamUrl, name, resp.frequency)
                    mediaActionStarted = true  // spec §1.3 — skip end-interaction earcon
                } else {
                    speakAndRemember(resp.speak.ifBlank { "ไม่มี stream URL" })
                }
            }
            "stop" -> {
                stopAction = true
                executeStopSequence()
                speakAndRemember(resp.speak.ifBlank { "หยุดแล้ว" })
                recordHistory(HistoryAction.Stop)
                followupEligible = true  // spec v1.3.8 B2 — stop is a natural pause, keep the mic open
            }
            "chat" -> {
                // Spec v1.3.8 B1 — n8n added a conversational category. Route it as
                // a first-class response (not "none" — no "ยังทำเรื่องนี้ไม่ได้" tone) so
                // the reply feels natural. Also flagged for the follow-up window (B2)
                // so the rider can keep talking without another BVRA press.
                val spoken = resp.speak.ifBlank { "ค่ะ" }
                speakAndRemember(spoken)
                recordHistory(HistoryAction.Chat(spoken))
                followupEligible = true
            }
            "none" -> {
                speakAndRemember(resp.speak.ifBlank { "รับทราบ" })
                followupEligible = true  // rider may want to correct or continue
            }
            "seek" -> {
                // v1.3.11 §2 — reuse the existing `frequency` field for signed
                // seconds (spec: "frequency = วินาที, บวก=หน้า ลบ=หลัง, 0=สั่งเล่น").
                // v1.3.20 sprint — n==0 goes through the same rule-#2 path as the local
                // "เล่นต่อ" intercept so the target-package decision (last-opened-app +
                // refire deep link if no session) is unified.
                val n = (resp.frequency ?: 0.0).toInt()
                if (n == 0) {
                    MediaOrchestrator.speakPlayConfirmed = settings.confirmMediaStart
                    val result = MediaOrchestrator.playContinue(context, appHint = null, entry = entry)
                    // Only speak here if the nudge WON'T also speak — i.e. we didn't
                    // fire a fresh deep link. Result.Success means "deep link refired,
                    // nudge will confirm on STATE_PLAYING" so double-speech is avoided.
                    if (result !is MediaOrchestrator.Result.Success) {
                        speakAndRemember(resp.speak.ifBlank { ErrorSpeech.MEDIA_PLAY_CONFIRMED })
                    } else if (resp.speak.isNotBlank()) {
                        speakAndRemember(resp.speak)
                    }
                } else {
                    handleSeek(n, entry)
                    if (resp.speak.isNotBlank()) speakAndRemember(resp.speak)
                }
            }
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
            speakAndRemember(ErrorSpeech.PREFLIGHT_MISSING_CONTACTS); return
        }
        val matches = contactMatcher.findMatches(name)
        when {
            matches.isEmpty() -> speakAndRemember("ไม่พบ $name ในรายชื่อ")
            matches.size == 1 && matches[0].score >= HIGH_CONF ->
                confirmThenCall(matches[0].contact, speakOverride)
            else -> {
                val candidates = matches.take(3)
                val choice = askDisambig(candidates)
                if (choice < 0) speakAndRemember(ErrorSpeech.CANCELLED)
                else confirmThenCall(candidates[choice].contact, null)
            }
        }
    }

    private suspend fun confirmThenCall(contact: ContactEntry, speakOverride: String?) {
        // Spec v1.3.9 §2.1 — trim to the essence and END with the question word.
        // Old: "กำลังโทรหา แม่ พูด ยกเลิก เพื่อยกเลิก" — ended on the instruction, mid-
        // instruction is when riders start to talk over the assistant. New base line
        // ends on "ใช่ไหมคะ" so the last word before the answer-listen beep is a
        // question. The "พูด ยกเลิก" hint is now gated on teaching mode (§2.3).
        val basePrompt = speakOverride?.takeIf { it.isNotBlank() }
            ?: "โทรหา${contact.displayName} ใช่ไหมคะ"
        if (!settings.confirmBeforeCall) {
            speakAndRemember(basePrompt)
            makeCall(contact)
            return
        }

        val prompt = withTeachingHint(basePrompt)

        // Confirmation flow: prompt, listen for a positive/negative reply. If the reply
        // clearly says "ไม่/ยกเลิก" we cancel; otherwise (positive keyword OR unusable
        // response OR silence) we CALL — riders don't have time to re-do this on a bike
        // and they can always cancel the outgoing call from the phone dialer.
        val answer = promptAndListen(
            prompt,
            null,
            minListenMs = CONFIRM_MIN_LISTEN_MS,
            withReminder = true,
        )
        val explicitCancel = answer.contains("ไม่") || answer.contains("ยกเลิก") || answer.contains("cancel", ignoreCase = true)
        if (explicitCancel) {
            speakAndRemember(ErrorSpeech.CANCELLED)
            // Spec v1.3.8 B2 — cancelled call is a natural pause; keep listening in case
            // the rider changes their mind or wants to dial someone else.
            followupEligible = true
        } else makeCall(contact)
    }

    /**
     * Field-test rewrite (spec §2.2 – §2.4): read ALL matched candidates, include
     * every valid answer-word in the instruction, re-prompt once on miss before
     * cancelling, and give the rider 6s of listening (up from the default 3s) to
     * think + speak.
     */
    private suspend fun askDisambig(candidates: List<MatchResult>): Int {
        val labels = listOf("หนึ่ง", "สอง", "สาม")
        val list = candidates.mapIndexed { i, m ->
            "${labels.getOrElse(i) { "อันดับ${i + 1}" }} ${m.contact.displayName}"
        }.joinToString(" ")
        val answerHint = disambigAnswerHint(candidates.size)
        // Spec v1.3.9 §2.1 — must end with a question word. Old version ended on
        // "ยกเลิก" (an option, not a question) so a rider whose answer landed right
        // at the tail of the prompt would get an ambiguous "did she say cancel or is
        // that just the last word of her sentence?" listen. New shape: "…พูด X คะ".
        val fullPrompt = withTeachingHint("มี ${candidates.size} รายชื่อ $list พูด $answerHint คะ")

        val firstAns = promptAndListen(fullPrompt, null, DISAMBIG_MIN_LISTEN_MS, withReminder = true)
        val firstChoice = NumberWordParser.parse(firstAns, candidates.size)
        if (firstChoice is NumberWordParser.Choice.Index) return firstChoice.zeroBased
        if (firstChoice is NumberWordParser.Choice.Cancel) return -1

        // Miss: re-prompt with a short one so the rider isn't left hanging.
        val shortPrompt = "เลือก $answerHint คะ"
        val retryAns = promptAndListen(shortPrompt, null, DISAMBIG_MIN_LISTEN_MS, withReminder = true)
        val retryChoice = NumberWordParser.parse(retryAns, candidates.size)
        if (retryChoice is NumberWordParser.Choice.Index) return retryChoice.zeroBased
        return -1  // Cancel / None → announce cancelled in caller.
    }

    /** "หนึ่ง สอง หรือ สาม" for 3 candidates, "หนึ่ง หรือ สอง" for 2, "หนึ่ง" for 1. */
    private fun disambigAnswerHint(count: Int): String = when (count) {
        1 -> "หนึ่ง"
        2 -> "หนึ่ง หรือ สอง"
        3 -> "หนึ่ง สอง หรือ สาม"
        else -> (1..count).joinToString(" ") { "$it" }
    }

    private fun makeCall(contact: ContactEntry) {
        if (!hasPerm(Manifest.permission.CALL_PHONE)) {
            scope.launch { speakAndRemember(ErrorSpeech.PREFLIGHT_MISSING_CALL) }
            return
        }
        if (contact.phoneNumber.isBlank()) {
            Log.w(TAG, "empty phone number for ${contact.displayName}")
            scope.launch { speakAndRemember("ไม่มีเบอร์โทรของ ${contact.displayName}") }
            return
        }
        // §6.7: catch the "no SIM / no telephony radio" case before firing ACTION_CALL,
        // so the rider hears a clear message instead of silent failure.
        if (!CellularCheck.canCall(context)) {
            Log.w(TAG, "no cellular capability — status=${CellularCheck.status(context)}")
            scope.launch { speakAndRemember(ErrorSpeech.NO_CELL_SIGNAL) }
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
            scope.launch { speakAndRemember(ErrorSpeech.NO_CELL_SIGNAL) }
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
            speakAndRememberWithOpener(spoken)
            releaseScoBeforeMedia(entry)
            MediaOrchestrator.speakPlayConfirmed = settings.confirmMediaStart
            MediaOrchestrator.openYoutube(context, chosen.id, resp.query, entry, expectedTitle = chosen.title)
            recordHistory(HistoryAction.YoutubeOpen(chosen.id, chosen.title))
            // Spec v1.3.8 B5 — remember the videos list so "อันต่อไป" can advance.
            com.moto.voice.media.MediaSessionMemory.rememberYoutube(candidates, chosen.id, chosen.title)
            mediaActionStarted = true  // spec v1.3.9 §1.3 — skip end-interaction earcon
            return
        }

        // No usable video id from the webhook.
        // Deliberately DO NOT open YouTube search — while riding you can't tap results
        // and being dumped in YouTube is worse than being told to try again. Just speak
        // the standard retry hint (spec §6.4) and let the rider re-trigger.
        speakAndRemember(ErrorSpeech.YOUTUBE_NOT_FOUND)
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
            val prompt = if (attempt == 0) menuText else ErrorSpeech.YT_PICKER_UNCLEAR_PREFIX + menuText
            val ans = promptAndListen(prompt, entry)
            when (val choice = NumberWordParser.parse(ans, top.size)) {
                is NumberWordParser.Choice.Index -> return top[choice.zeroBased]
                NumberWordParser.Choice.Cancel -> {
                    speakAndRemember(ErrorSpeech.CANCELLED)
                    return null
                }
                NumberWordParser.Choice.None -> Unit  // ask again next iteration
            }
        }

        // Default per spec: open first + tell the rider we did.
        speakAndRemember(ErrorSpeech.YT_PICKER_DEFAULT_FIRST)
        return top.first()
    }

    // v1.3.20 sprint — openYoutube / prepauseIfMusicActive / stopYoutubeControllerIfActive /
    // scheduleYoutubeNudge* / isCorrectVideo / maybeSpeakPlayConfirmed / speakOutOfPipeline
    // consolidated into [com.moto.voice.media.MediaOrchestrator] (single owner of media ops).
    // See CLAUDE.md iron rules #1–#3.

    // ─── FM radio ────────────────────────────────────────────────────────────

    private fun startFm(streamUrl: String, label: String, frequency: Double?) {
        // A fresh play command supersedes any auto-resume that would otherwise happen.
        pausedOurFm = false
        FmPlaybackState.clearAssistantPaused()

        memory.rememberStation(streamUrl, label, frequency)
        // Spec v1.3.8 B5 — clear video context and set FM name so "เมื่อกี้อะไร" answers.
        com.moto.voice.media.MediaSessionMemory.rememberFm(label)
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

    /**
     * Ordered sequence for the "stop" command — replaces the fire-and-forget
     * MediaStopper.stopAny that field-testing proved unreliable (YouTube kept
     * playing / restarted after our transient focus was released).
     *
     *   1. **Upgrade our audio focus to AUDIOFOCUS_GAIN (permanent)** so other apps
     *      receive AUDIOFOCUS_LOSS instead of AUDIOFOCUS_LOSS_TRANSIENT. Permanent
     *      loss = "don't auto-resume", which is exactly the semantics of a user-
     *      commanded stop.
     *   2. **Send ACTION_STOP to our own FmPlayerService.** Its MediaSession will
     *      be released in onDestroy; until then it can intercept dispatched media
     *      buttons.
     *   3. **300ms delay** so the FmPlayerService destruction reaches the point
     *      where MediaSessionManager no longer routes media buttons to us.
     *   4. **Dispatch KEYCODE_MEDIA_PAUSE.** Now targets whichever session is
     *      actually playing (YouTube, Spotify, etc.).
     *   5. **200ms delay** to give the target session a beat to process the pause.
     *
     * cleanup() will [AudioFocusRouter.abandon] afterwards; that's fine because
     * we already handed the target app a permanent loss in step 1 — it won't
     * unpause when we release.
     */
    private suspend fun executeStopSequence(entry: DebugEntry? = currentEntry) {
        Log.d(TAG, "stop: upgrading focus to permanent")
        focusRouter.upgradeToPermanent()
        Log.d(TAG, "stop: sending ACTION_STOP to our FM service")
        sendToFm(FmPlayerService.ACTION_STOP)
        delay(300)  // let our MediaSession release

        // v1.3.20 sprint rule #1 — controller pause loop lives in MediaOrchestrator
        // (single owner of media ops). Orchestrator applies StopSequenceTargets +
        // the "media key only when no permission" rule internally. Rule #3 op-log
        // fields require a non-null DebugEntry, so we synthesise a throwaway one if
        // the caller doesn't have one — the throwaway is NOT added to DebugLog.
        val opEntry = entry ?: DebugEntry()
        MediaOrchestrator.pauseAll(context, opEntry)
        delay(200)  // let the target session process
    }

    // ─── Audio helpers ────────────────────────────────────────────────────────

    /**
     * Field-test bug from log 1783477052378: with the helmet paired, TTS confirmations
     * were heard clearly (SCO up) but the media that came next was silent every time.
     * Root cause — the youtube/fm intent fired while SCO was still up + we still held
     * audio focus, so YouTube couldn't grab music focus / A2DP routing wasn't primary.
     *
     * Spec §1.2 fix sequence, called AFTER the confirmation TTS finishes and BEFORE the
     * media intent / FmPlayerService start:
     *
     *   1. Abandon our audio focus so the media app can gain STREAM_MUSIC focus.
     *   2. Disconnect SCO — [BluetoothAudioRouter.disconnect] now also flips
     *      audio mode back to MODE_NORMAL as of this fix.
     *   3. Sleep 800ms for A2DP to become the active output (per spec §1.2 — either
     *      listen for AudioDeviceCallback, OR 800ms delay minimum; the delay is
     *      simpler and works uniformly across vendor stacks).
     *   4. Log `scoTeardownMs` + `audioMode` to the debug entry so the next field log
     *      can confirm MODE_NORMAL was reached before the intent fired.
     *
     * cleanup() will call [BluetoothAudioRouter.disconnect] + [AudioFocusRouter.abandon]
     * again at pipeline end — both are idempotent so this pre-release is safe.
     */
    private suspend fun releaseScoBeforeMedia(entry: DebugEntry) {
        val t0 = System.currentTimeMillis()
        runCatching { focusRouter.abandon() }
        runCatching { btRouter.disconnect() }
        delay(800L)
        entry.scoTeardownMs = System.currentTimeMillis() - t0
        entry.audioMode = AudioModeName.of(btRouter.currentAudioMode())
    }

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
    private suspend fun listenOnce(entry: DebugEntry?, minListenMs: Long = DEFAULT_MIN_LISTEN_MS): String =
        listenOnceDetailed(entry, minListenMs).text

    /** Exposes the outcome so the main-flow retry logic can decide what to do next. */
    private suspend fun listenOnceDetailed(entry: DebugEntry?, minListenMs: Long = DEFAULT_MIN_LISTEN_MS): SttOutcome {
        // Settle delay so TTS audio finishes draining and the recognizer isn't still
        // holding the mic from a previous session. See bug report §1.
        delay(350)
        // Spec v1.3.8 A2 — if the previous 2+ listens hit non-silence errors, the
        // platform recognizer service is likely degrading. Force an extra 400ms
        // breather and explicit destroy so the fresh instance in listenOnceRaw hits
        // an OS side that had time to reclaim its state. Log so field logs show
        // when this kicked in.
        if (consecutiveSttErrors >= STT_RECREATE_THRESHOLD) {
            Log.w(TAG, "STT $consecutiveSttErrors consecutive errors — throttling + recreate")
            recognizer?.destroy()
            recognizer = null
            entry?.sttRecreated = true
            delay(400L)
        }
        val first = listenOnceRaw(entry, isRetry = false, minListenMs = minListenMs)
        if (first.text.isNotBlank()) return first
        if (first.wasTransientError) {
            Log.d(TAG, "STT transient error — retrying once")
            delay(400)
            return listenOnceRaw(entry, isRetry = true, minListenMs = minListenMs)
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
        // Use the detailed variant so we go through Earcon.ready + echo filter.
        val second = promptAndListenDetailed(ErrorSpeech.NOT_HEARD_RETRY, entry)
        val secondText = second.text.trim()
        return if (secondText.length < MIN_MEANINGFUL_LEN) "" else secondText
    }

    private data class SttOutcome(
        val text: String,
        val wasTransientError: Boolean,
        val wasNoSpeech: Boolean = false,
        val confidence: Float = -1f,
    )

    /**
     * Spec v1.3.9 §2.2 — reaction to a live STT partial while another coroutine is
     * driving TTS. Passed to [listenOnceRaw] via the optional [onPartial] parameter.
     * Existing callers pass `null` and get the previous behaviour (partial written
     * to entry, nothing else).
     */
    private enum class BargeInPartialAction {
        /** Not a decision moment — keep listening as normal. */
        Continue,
        /** Partial matched the current TTS text — discard from entry, keep listening. */
        EchoDrop,
        /** Partial is a real answer during TTS — return it as the final result NOW. */
        UseAsFinal,
    }

    private suspend fun listenOnceRaw(
        entry: DebugEntry?,
        isRetry: Boolean,
        minListenMs: Long = DEFAULT_MIN_LISTEN_MS,
        onPartial: ((String) -> BargeInPartialAction)? = null,
    ): SttOutcome =
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
                    if (onPartial == null) return
                    // Spec v1.3.9 §2.2.ข — barge-in classifier. Runs on the main thread
                    // (RecognitionListener callback), same thread the coroutine will
                    // resume on, so resume-once via [resumed] is race-free.
                    when (onPartial.invoke(partial)) {
                        BargeInPartialAction.Continue -> Unit
                        BargeInPartialAction.EchoDrop -> {
                            entry?.sttPartial = ""  // hide echo from the debug entry
                        }
                        BargeInPartialAction.UseAsFinal -> {
                            if (resumed.compareAndSet(false, true)) {
                                runCatching { rec.stopListening() }
                                consecutiveSttErrors = 0
                                Log.d(TAG, "barge-in: promoting partial '$partial' to final")
                                if (cont.isActive) cont.resume(
                                    SttOutcome(partial, wasTransientError = false, wasNoSpeech = false)
                                )
                            }
                        }
                    }
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
                    // Spec v1.3.8 A2 — success resets the consecutive-error counter.
                    // Blank text still counts as a "the engine was healthy enough to
                    // return" so it's a legitimate reset too.
                    consecutiveSttErrors = 0
                    lastListenErrorClass = ListenErrorClass.None
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
                    val serverFamily = error == SpeechRecognizer.ERROR_NETWORK ||
                                       error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                                       error == SpeechRecognizer.ERROR_SERVER ||
                                       error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED
                    // Spec v1.3.8 A2 — only count the "engine is degrading" family of
                    // errors. NO_MATCH / TIMEOUT are silence, not degradation, and
                    // shouldn't trigger the throttle.
                    if (!noSpeech) consecutiveSttErrors++ else consecutiveSttErrors = 0
                    // v1.3.10 — classify for callers that need to distinguish
                    // "server hiccup" from "rider was silent" (used by maybeSlotFill).
                    lastListenErrorClass = when {
                        noSpeech -> ListenErrorClass.NoSpeech
                        serverFamily -> ListenErrorClass.ServerError
                        else -> ListenErrorClass.Other
                    }
                    if (cont.isActive) cont.resume(
                        SttOutcome("", wasTransientError = transient, wasNoSpeech = noSpeech)
                    )
                }
            })

            // Silence-length hints — spec v1.3.6 §1: default bumped from 1200ms→2000ms
            // after field complaint of riders getting cut off mid-sentence. Rider can
            // tune 1000..3000ms via the "จังหวะรอฟัง" slider in Settings.
            // NOTE: Android treats these as HINTS — the underlying vendor recognizer
            // (Samsung / Google) may honor them, ignore them, or clamp them. Slot-filling
            // for bare openers is the safety net when the hint is ignored.
            val completeSilenceMs = (settings.listenPaceSeconds * 1000f).toLong()
            val possiblyCompleteMs = (completeSilenceMs * 0.4f).toLong()  // scaled from previous 800/1200 ratio
            entry?.completeSilenceMs = completeSilenceMs

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "th-TH")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "th-TH")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, minListenMs)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, completeSilenceMs)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, possiblyCompleteMs)
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

    /**
     * Spec v1.3.8 B3 — variant used for action-taking replies where the webhook's
     * speak text starts with "กำลัง". Prepends a short random opener ~40% of the
     * time so the assistant doesn't sound identical on every consecutive command.
     * Non-"กำลัง" text passes through unchanged.
     *
     * The two possible opener strings (ได้เลย{ค่ะ|ครับ}, จัดให้{ค่ะ|ครับ}) are in
     * ErrorSpeech and therefore in the pre-synthesize cache — no Azure hit added.
     */
    private suspend fun speakAndRememberWithOpener(text: String) {
        val combined = RandomOpener.pickPrefixFor(text) + text
        speakAndRemember(combined)
    }

    /**
     * Spec v1.3.9 §2.3 — append the teaching hint (" ตอบหลังเสียงติ๊งนะคะ") to
     * question prompts for the first [com.moto.voice.data.AppSettings.TEACHING_MODE_BUDGET]
     * interactions after install. After the budget is exhausted the hint auto-
     * suppresses so returning riders don't hear it forever.
     *
     * Idempotent per interaction: flips [teachingHintFired] the first time it
     * fires; the finally-block in [runPipeline] decrements the budget by exactly
     * one per interaction (not per prompt) so a confirm-with-disambig doesn't
     * eat two uses.
     */
    private fun withTeachingHint(basePrompt: String): String {
        if (!settings.isTeachingModeActive) return basePrompt
        teachingHintFired = true
        return basePrompt + ErrorSpeech.TEACHING_HINT
    }

    /**
     * Every place that asks the rider a question uses this helper. It enforces the
     * flow contract from field-test bug §2: speakAwait must complete (audio done,
     * not just synth done) → [Earcon.answerListen] gives the "your turn" dual-beep →
     * a [Earcon.MIC_OPEN_GAP_MS] silence gap → then listen. This is what stops the
     * mic from picking up the tail of the assistant's own TTS — the exact echo
     * captured in log 1783432847869.
     *
     * Earcon updated from `ready` (rising beep, spec meaning "new interaction") to
     * `answerListen` (dual beep, spec meaning "your turn to reply") in v1.3.9 so
     * the rider can distinguish "press BVRA to talk" from "just talk, no button".
     *
     * On top of the audible gap, [TtsEchoFilter] rejects any STT result that is
     * ≥75% similar to the prompt we just spoke, as an extra guard for phone-mic
     * mode where speaker/mic isolation is zero.
     */
    private suspend fun promptAndListen(
        prompt: String,
        entry: DebugEntry?,
        minListenMs: Long = DEFAULT_MIN_LISTEN_MS,
        withReminder: Boolean = false,
    ): String {
        // v1.3.27 — rider preference (2026-07-17): prompts pace SERIALLY on EVERY route.
        // Speak the whole prompt → answer-listen beep → a clear PROMPT_SETTLE_MS gap →
        // open the mic. Previously SCO used a barge-in path (promptAndListenBargeIn) that
        // fired the beep + opened the mic 150ms into the TTS, piling speech + beep + "your
        // turn" together — the rider found that confusing. The barge-in path is retained
        // (unused) so it's easy to restore if the preference changes.
        speakAndRemember(prompt)
        Earcon.answerListen()
        delay(PROMPT_SETTLE_MS)
        PipelineState.setListening()  // spec v1.3.9 §4
        val result = withRemainingReminder(minListenMs, withReminder) {
            listenOnce(entry, minListenMs)
        }
        PipelineState.setThinking()
        if (TtsEchoFilter.isEcho(result, memory.lastSpoken)) {
            Log.w(TAG, "echo detected — treating as no-speech: '$result'")
            entry?.error = ((entry?.error ?: "") + " tts_echo_filtered").trim()
            return ""
        }
        return result
    }

    /**
     * Spec v1.3.9 §2.2 — full barge-in variant of [promptAndListen] used when the
     * pipeline is on SCO. Opens the mic concurrent with TTS start (§2.2.ก) and runs
     * an echo-vs-real-answer classifier on every partial STT result via
     * [TtsEchoFilter.classifyDuringTts] (§2.2.ข). A real answer during TTS stops
     * the TTS mid-sentence and is processed immediately.
     *
     * Timing per §2.2.ค: the effective silence-input window is padded by an
     * estimated TTS duration so the recognizer doesn't time out mid-prompt; the
     * rider's usable "waiting-for-them" budget after the earcon still amounts to
     * [minListenMs].
     *
     * Marks [DebugEntry.bargeInAnswer] on success.
     *
     * v1.3.27 — RETAINED but no longer dispatched: the rider chose serial prompt pacing
     * (see [promptAndListen]). Kept intact so barge-in can be restored without a rewrite.
     */
    @Suppress("unused")
    private suspend fun promptAndListenBargeIn(
        prompt: String,
        entry: DebugEntry?,
        minListenMs: Long,
        withReminder: Boolean,
    ): String = kotlinx.coroutines.coroutineScope {
        memory.lastSpoken = prompt

        val ttsCompletedAt = java.util.concurrent.atomic.AtomicLong(0L)
        val bargedIn = AtomicBoolean(false)
        val ttsJob = launch {
            tts.speakAwait(prompt)
            ttsCompletedAt.set(System.currentTimeMillis())
        }

        // §2.2.ก — mic opens concurrent with TTS. Wait a moment for TTS to actually
        // start producing audio (so the answer-listen earcon lands between our
        // start and the mic open) but do NOT wait for TTS to finish.
        delay(150)
        Earcon.answerListen()
        delay(Earcon.MIC_OPEN_GAP_MS)
        PipelineState.setListening()  // spec v1.3.9 §4

        val partialHandler: (String) -> BargeInPartialAction = { partial ->
            if (ttsCompletedAt.get() != 0L) {
                // TTS already finished — normal partial, no barge-in classification
                BargeInPartialAction.Continue
            } else {
                when (TtsEchoFilter.classifyDuringTts(partial, TtsRecentSpeech.currentOrRecent())) {
                    TtsEchoFilter.BargeInClass.ECHO -> BargeInPartialAction.EchoDrop
                    TtsEchoFilter.BargeInClass.REAL_ANSWER -> {
                        Log.d(TAG, "barge-in real answer during TTS: '$partial' — stopping TTS")
                        entry?.bargeInAnswer = true
                        bargedIn.set(true)
                        ttsJob.cancel()
                        runCatching { tts.stop() }
                        BargeInPartialAction.UseAsFinal
                    }
                    TtsEchoFilter.BargeInClass.UNKNOWN -> BargeInPartialAction.Continue
                }
            }
        }

        // §2.2.ค — pad the STT minimum window by an estimate of TTS length so the
        // rider still gets ≈[minListenMs] to think AFTER TTS finishes. ~60ms/char
        // is empirical for Thai Neural TTS at rate 1.0; capped so we don't spin
        // the recognizer forever.
        val ttsEstimateMs = (prompt.length * 60L).coerceAtMost(6_000L)
        val effectiveMin = minListenMs + ttsEstimateMs

        val result = withRemainingReminder(effectiveMin, withReminder) {
            listenOnceRaw(
                entry = entry,
                isRetry = false,
                minListenMs = effectiveMin,
                onPartial = partialHandler,
            ).text
        }.trim()

        if (!bargedIn.get()) ttsJob.join()
        PipelineState.setThinking()

        // Post-listen echo defence in depth — the pre-partial classifier is best
        // effort; if a final result slipped through that still looks like our
        // prompt, drop it (matches non-barge-in promptAndListen behaviour).
        if (TtsEchoFilter.isEcho(result, memory.lastSpoken)) {
            Log.w(TAG, "post-listen echo detected — dropping '$result'")
            entry?.error = ((entry?.error ?: "") + " tts_echo_filtered").trim()
            return@coroutineScope ""
        }
        result
    }

    /**
     * Spec v1.3.9 §3 — for long confirm / disambig windows, fire a soft
     * [Earcon.answerListen] "ping" at (window − 2s) if the rider hasn't answered
     * yet. Signals "still waiting on you" without saying anything new. Cancelled
     * automatically if [block] returns before the reminder time.
     *
     * When [enabled] is false this is a passthrough — used for short main-STT
     * listens where 2s is most of the window and the reminder would be noise.
     */
    private suspend fun <T> withRemainingReminder(
        windowMs: Long,
        enabled: Boolean,
        block: suspend () -> T,
    ): T {
        if (!enabled || windowMs <= ANSWER_LISTEN_REMINDER_MS + 500) {
            return block()
        }
        val reminderAt = windowMs - ANSWER_LISTEN_REMINDER_MS
        val reminderJob = scope.launch {
            delay(reminderAt)
            runCatching { Earcon.answerListen() }
        }
        try {
            return block()
        } finally {
            reminderJob.cancel()
        }
    }

    /** Detailed variant used by [listenMainWithMissRetry] where we need the SttOutcome. */
    private suspend fun promptAndListenDetailed(prompt: String, entry: DebugEntry): SttOutcome {
        speakAndRemember(prompt)
        Earcon.answerListen()
        delay(PROMPT_SETTLE_MS)  // v1.3.27 — clearer "your turn" beat (was MIC_OPEN_GAP_MS 150ms)
        val outcome = listenOnceDetailed(entry)
        if (TtsEchoFilter.isEcho(outcome.text, memory.lastSpoken)) {
            Log.w(TAG, "echo detected — treating as no-speech: '${outcome.text}'")
            entry.error = ((entry.error ?: "") + " tts_echo_filtered").trim()
            return SttOutcome(text = "", wasTransientError = false, wasNoSpeech = true, confidence = -1f)
        }
        return outcome
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

    /**
     * Explain to the debug log WHY audioRoute ended up sco/phone. Just knowing
     * "phone" isn't enough for field diagnosis — is it "no helmet paired" (normal
     * for testing / no-helmet mode) or "helmet paired but SCO handshake failed"
     * (a real problem)?
     */
    private fun resolveScoState(hasBtPermission: Boolean, scoOk: Boolean): String {
        if (scoOk) return ScoState.CONNECTED
        if (!hasBtPermission) return ScoState.NO_PERMISSION
        // scoOk == false + we have permission — probe the adapter to see if there
        // even IS a headset connected. If yes → SCO failed. If no → no headset.
        val bm = context.getSystemService(BluetoothManager::class.java) ?: return ScoState.NO_HEADSET
        val adapter = bm.adapter ?: return ScoState.NO_HEADSET
        val connected = runCatching {
            adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
        }.getOrDefault(false)
        return if (connected) ScoState.FAILED else ScoState.NO_HEADSET
    }
}

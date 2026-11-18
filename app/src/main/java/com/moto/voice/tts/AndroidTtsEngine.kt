package com.moto.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.moto.voice.data.AppSettings
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The original Android [TextToSpeech]-backed engine, wrapped in the [TtsEngine] interface.
 *
 * onDone is dispatched from [UtteranceProgressListener.onDone] which the platform docs
 * define as "playback finished" — matches our [TtsEngine.speak] contract exactly, so no
 * additional bookkeeping is needed.
 *
 * ─── v1.4.12 — a failed speak is never silent, and never permanent ──────────────
 * Field log 1794968515443: from 14 Nov every confirmation "finished" in ~50 ms and the rider
 * heard nothing for four days, with no error in any entry. This process lives for days (it
 * hosts the VoiceInteractionService) and held ONE [TextToSpeech] for all of them; once that
 * binding goes bad — the engine app is updated or restarted under us — the platform does
 * not rebuild it, and every later `speak()` fails instantly. The old code swallowed that.
 *
 * Now a line that fails (speak returns ERROR, the engine reports an error, or "done" arrives
 * too fast to have been played) rebuilds the engine once and is said again; the outcome goes
 * to [onDiagnostic] so the debug entry shows `ttsError: …→rebind→ok` or why it still failed.
 * A failed init fails its queued lines instead of leaving them waiting forever.
 *
 * @param onDiagnostic receives one [TtsRecovery.describe] token per abnormal line.
 */
class AndroidTtsEngine(
    private val context: Context,
    private val onDiagnostic: (String) -> Unit = {},
) : TtsEngine {

    private class Utterance(
        val id: String,
        val text: String,
        val onStart: (() -> Unit)?,
        val onDone: (() -> Unit)?,
        val onError: ((String) -> Unit)?,
    ) {
        @Volatile var startedAt = 0L
        /** Set once this line has been through a rebuild — it does not get a second one. */
        @Volatile var retried = false
        @Volatile var firstReason: String? = null
    }

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    /** Bumped on every (re)build so a late init callback from a dead instance is ignored. */
    private var generation = 0
    @Volatile private var ready = false
    private var initFailed = false
    @Volatile private var languageProblem: String? = null

    /** Lines waiting for the engine to finish (re)initialising. Guarded by [lock]. */
    private val pending = ArrayList<Utterance>()
    /** Lines handed to the engine, by utteranceId. */
    private val live = ConcurrentHashMap<String, Utterance>()
    /** Id of the newest line — an older one is never retried on top of it. */
    @Volatile private var latestId: String? = null

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            utteranceId?.let { live[it]?.onStart?.invoke() }
        }
        override fun onDone(utteranceId: String?) {
            val u = utteranceId?.let { live.remove(it) } ?: return
            val elapsed = SystemClock.elapsedRealtime() - u.startedAt
            if (TtsRecovery.doneTooFast(u.text, elapsed)) fail(u, "doneTooFast(${elapsed}ms)")
            else succeed(u)
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            utteranceId?.let { live.remove(it) }?.let { fail(it, "legacy error") }
        }
        override fun onError(utteranceId: String?, errorCode: Int) {
            utteranceId?.let { live.remove(it) }?.let { fail(it, "code $errorCode") }
        }
    }

    private val settings = runCatching { AppSettings(context) }.getOrNull()

    init {
        synchronized(lock) { buildLocked() }
    }

    /** Thai voice missing / unsupported on the current engine, else null. */
    fun standingProblem(): String? = languageProblem

    private fun buildLocked() {
        val gen = ++generation
        ready = false
        initFailed = false
        tts = TextToSpeech(context) { status -> onInit(gen, status) }
    }

    private fun rebuildLocked() {
        val old = tts
        tts = null
        ready = false
        runCatching { old?.stop() }
        runCatching { old?.shutdown() }
        buildLocked()
    }

    private fun onInit(gen: Int, status: Int) {
        var drained: List<Utterance> = emptyList()
        var ok = false
        synchronized(lock) {
            if (gen != generation) return  // a rebuilt-away instance reporting late
            val engine = tts
            ok = status == TextToSpeech.SUCCESS && engine != null
            if (ok && engine != null) {
                val lang = runCatching { engine.setLanguage(Locale("th", "TH")) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                languageProblem = TtsRecovery.languageProblem(lang)
                engine.setSpeechRate(settings?.ttsSpeechRate ?: AppSettings.DEFAULT_TTS_RATE)
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                engine.setOnUtteranceProgressListener(listener)
                ready = true
            } else {
                initFailed = true
            }
            drained = pending.toList()
            pending.clear()
        }
        Log.i(TAG, "init gen=$gen status=$status ok=$ok lang=$languageProblem queued=${drained.size}")
        drained.forEach { u ->
            when {
                !ok -> giveUp(u, "initFailed($status)")
                u.retried && latestId != u.id -> giveUp(u, "superseded")
                else -> speakNow(u)
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
        val u = Utterance(UUID.randomUUID().toString(), text, onStart, onDone, onError)
        latestId = u.id
        submit(u)
    }

    private fun submit(u: Utterance) {
        synchronized(lock) {
            // A failed init used to leave every later line queued forever; try again instead.
            if (initFailed) rebuildLocked()
            if (!ready) {
                pending += u
                return
            }
        }
        speakNow(u)
    }

    private fun speakNow(u: Utterance) {
        val params = Bundle().apply {
            // KEY_PARAM_VOLUME range is 0..1 per docs; some engines accept boost up to 1.5.
            val volume = (settings?.assistantVolume ?: AppSettings.DEFAULT_ASSIST_VOLUME).coerceIn(0f, 1.5f)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        }
        u.startedAt = SystemClock.elapsedRealtime()
        live[u.id] = u
        val result = runCatching { tts?.speak(u.text, TextToSpeech.QUEUE_FLUSH, params, u.id) }.getOrNull()
        if (result != TextToSpeech.SUCCESS) {
            live.remove(u.id)?.let { fail(it, "speakReturnedError") }
        }
    }

    private fun succeed(u: Utterance) {
        if (u.retried) report(TtsRecovery.describe(u.firstReason ?: "unknown", rebound = true, recovered = true))
        u.onDone?.invoke()
    }

    /** The line was not spoken: rebuild the engine and say it again, once; else report it. */
    private fun fail(u: Utterance, reason: String) {
        if (!TtsRecovery.shouldRebind(u.retried, isLatest = latestId == u.id)) {
            giveUp(u, reason)
            return
        }
        u.retried = true
        u.firstReason = reason
        Log.w(TAG, "speak failed ($reason) — rebuilding the engine and retrying once")
        // Engine callbacks arrive on binder threads; keep the engine's lifecycle on main.
        main.post {
            synchronized(lock) {
                rebuildLocked()
                pending += u
            }
        }
    }

    private fun giveUp(u: Utterance, reason: String) {
        report(TtsRecovery.describe(u.firstReason ?: reason, rebound = u.retried, recovered = false, finalReason = reason))
        u.onError?.invoke(reason)
    }

    private fun report(token: String) {
        Log.w(TAG, "tts: $token")
        runCatching { onDiagnostic(token) }
    }

    override fun stop() {
        runCatching { tts?.stop() }
    }

    override fun shutdown() {
        synchronized(lock) {
            generation++
            runCatching { tts?.stop() }
            runCatching { tts?.shutdown() }
            tts = null
            ready = false
            pending.clear()
        }
        live.clear()
    }

    private companion object { const val TAG = "AndroidTtsEngine" }
}

package com.moto.voice.debug

import android.content.Context
import com.google.gson.GsonBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

data class DebugEntry(
    val timestamp: Long = System.currentTimeMillis(),
    var sttPartial: String = "",
    var sttFinal: String = "",
    var webhookRequest: String? = null,
    var webhookResponse: String? = null,
    var scoTimeMs: Long = 0,
    var sttTimeMs: Long = 0,
    var webhookTimeMs: Long = 0,
    var actionTimeMs: Long = 0,
    var error: String? = null,
    /** How the pipeline finished. See [FinishReason]. Populated near the end of runPipeline. */
    var finishReason: String? = null,
    /** STT confidence of the top result (0..1). -1f = engine didn't provide any. Spec §4.4. */
    var sttConfidence: Float = -1f,
    /** How many extra listen attempts the main STT used (0 or 1). Spec §4.1. */
    var sttRetryCount: Int = 0,
    /** Which mic actually recorded ("sco" = helmet, "phone" = built-in). Spec §9.1. */
    var audioRoute: String? = null,
    /**
     * Distinguishes "SCO didn't come up because no headset was paired" from
     * "SCO tried and failed" — audioRoute=phone alone can't tell them apart.
     * Values: [ScoState].
     */
    var scoState: String? = null,

    // ─── TTS instrumentation (Sprint I §6) ───────────────────────────────────
    /** Which engine actually delivered the audio (azure / android / android_fallback). */
    var ttsEngine: String? = null,
    /** Synthesise time in ms (0 when we served from cache). */
    var ttsSynthMs: Long = 0,
    /** Playback time in ms (from prepare start to OnCompletion). */
    var ttsPlayMs: Long = 0,
    /** True if the last Azure speak served from the on-disk cache instead of hitting the API. */
    var cacheHit: Boolean = false,
    /** Reason string when Azure failed (network, HTTP code, playback error). */
    var azureError: String? = null,
    /**
     * Why the TTS router picked the engine it did. See [EngineChoiceReason].
     * Field-test 1783477052378 showed every entry as ttsEngine=android with no way to tell
     * whether the key was missing, the region was blank, or we were offline — this makes it explicit.
     */
    var engineChoiceReason: String? = null,

    // ─── SCO lifecycle (bug from log 1783477052378) ──────────────────────────
    /**
     * Milliseconds the pipeline spent releasing SCO + waiting for A2DP to become the
     * primary output before starting media (YouTube / FM). Populated by
     * [com.moto.voice.pipeline.VoiceCommandPipeline.releaseScoBeforeMedia]. 0 for
     * non-media actions (call / stop / help / repeat / none) where the release happens
     * in cleanup() instead.
     */
    var scoTeardownMs: Long = 0,
    /**
     * `audioManager.mode` value at the moment media was about to start, converted to
     * a human name (normal / in_call / in_comm / ringtone). Verifies the SCO teardown
     * left the platform in MODE_NORMAL so A2DP can carry STREAM_MUSIC.
     */
    var audioMode: String? = null,

    /**
     * True when the pipeline dispatched KEYCODE_MEDIA_PLAY 3s after opening YouTube
     * because [android.media.AudioManager.isMusicActive] was still false — the video
     * was open but paused (common when the deep-linked video was already loaded).
     * Field-test evidence: sttFinal="มันยังไม่เปิดเลยเงียบอยู่" in log 1783581952116.
     * Only set to true when the nudge actually fires; false includes both "no need"
     * and "not a media action" — grep for `true` to find real nudges in exports.
     */
    var youtubeNudged: Boolean = false,

    /**
     * The `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` value actually used
     * for the main-STT listen this interaction. Configurable via Settings §"จังหวะรอฟัง"
     * (spec v1.3.6 §1). Recorded so field logs can prove which value the recognizer
     * was actually given, though the platform is free to honor it or not.
     */
    var completeSilenceMs: Long = 0,

    /**
     * True when the pipeline detected a bare command opener ("โทรหา" / "เปิด youtube" /
     * "เปิดวิทยุ" with no payload) and asked one follow-up before running the pipeline
     * with the combined sentence. Spec v1.3.6 §2. Finished with FinishReason.SLOT_FILLED
     * at the moment of combination — a later stage may overwrite finishReason (e.g. OK
     * after a successful call), so this boolean is the durable marker.
     */
    var slotFilled: Boolean = false,
) {
    fun time(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))

    fun summary(): String = buildString {
        append("[${time()}]")
        if (sttFinal.isNotBlank()) append("  STT: \"$sttFinal\"")
        if (audioRoute != null) append("  route:${audioRoute}")
        if (scoState != null) append("  sco:${scoState}")
        if (scoTimeMs > 0) append("  SCO:${scoTimeMs}ms")
        if (scoTeardownMs > 0) append("  SCO⇩:${scoTeardownMs}ms")
        if (audioMode != null) append("  mode:${audioMode}")
        if (sttTimeMs > 0) append("  STT:${sttTimeMs}ms")
        if (webhookTimeMs > 0) append("  WH:${webhookTimeMs}ms")
        if (actionTimeMs > 0) append("  ACT:${actionTimeMs}ms")
        if (engineChoiceReason != null) append("  tts:${engineChoiceReason}")
        if (completeSilenceMs > 0) append("  silHint:${completeSilenceMs}ms")
        if (youtubeNudged) append("  yt:nudged")
        if (slotFilled) append("  slot:filled")
        if (finishReason != null) append("  end:${finishReason}")
        if (error != null) append("  ⚠️ $error")
    }
}

/** Constants for [DebugEntry.audioRoute]. Kept as strings so JSON export is human-readable. */
object AudioRoute {
    /** SCO connection to the helmet HFP is up — mic is the helmet. */
    const val SCO = "sco"
    /** Falling back to the built-in phone microphone. */
    const val PHONE = "phone"
}

/**
 * Constants for [DebugEntry.scoState] — granular explanation of why audioRoute
 * ended up SCO or PHONE. Populated by the pipeline right after connectSco returns.
 */
object ScoState {
    /** SCO link established successfully; mic + speaker on the helmet. */
    const val CONNECTED = "connected"
    /** A Bluetooth HFP headset IS connected but the SCO handshake failed within timeout. */
    const val FAILED = "failed"
    /** No paired/connected HFP device — normal for testing without a helmet. */
    const val NO_HEADSET = "no_headset"
    /** BLUETOOTH_CONNECT permission missing — we can't probe. */
    const val NO_PERMISSION = "no_permission"
}

/**
 * Constants for [DebugEntry.engineChoiceReason]. The router's decision path is
 * `key.isNotBlank() && region.isNotBlank() && online → Azure`, else Android; the
 * reason string tells us which of those gates failed. `azure_used` means Azure
 * synthesised the audio; `azure_failed_fallback` means Azure was attempted but
 * threw and we fell through to Android silently (spec §1.3).
 */
object EngineChoiceReason {
    const val AZURE_USED = "azure_used"
    const val AZURE_FAILED_FALLBACK = "azure_failed_fallback"
    const val ANDROID_NO_KEY = "android_no_key"
    const val ANDROID_NO_REGION = "android_no_region"
    const val ANDROID_OFFLINE = "android_offline"
}

/**
 * Constants for [DebugEntry.audioMode]. Values map directly from AudioManager.getMode()
 * ints so we don't have to grep for magic numbers in the exported JSON.
 */
object AudioModeName {
    const val NORMAL = "normal"
    const val RINGTONE = "ringtone"
    const val IN_CALL = "in_call"
    const val IN_COMMUNICATION = "in_communication"
    const val UNKNOWN = "unknown"

    /** Convert an AudioManager.MODE_* int to the corresponding constant above. */
    fun of(mode: Int): String = when (mode) {
        android.media.AudioManager.MODE_NORMAL -> NORMAL
        android.media.AudioManager.MODE_RINGTONE -> RINGTONE
        android.media.AudioManager.MODE_IN_CALL -> IN_CALL
        android.media.AudioManager.MODE_IN_COMMUNICATION -> IN_COMMUNICATION
        else -> UNKNOWN
    }
}

/**
 * String constants for [DebugEntry.finishReason] — kept as plain strings (not an enum)
 * so the JSON export stays human-readable and stable across refactors.
 */
object FinishReason {
    const val OK = "ok"
    const val TIMEOUT_FALLBACK = "timeout_fallback"
    const val HTTP_401 = "http_401"
    const val HTTP_OTHER = "http_other"
    const val NETWORK = "network"
    const val OFFLINE_RULE = "offline_rule"
    const val NO_SPEECH = "no_speech"
    const val PHONE_UNAVAILABLE = "phone_unavailable"
    const val INTERCEPTED = "intercepted"
    const val LLM_OFF = "llm_off"
    const val PARSE_ERROR = "parse_error"
    /** Rider double-tapped BVRA during an active interaction (spec §3.1). */
    const val BARGE_IN = "barge_in_cancel"
    /** STT captured the assistant's own TTS (via [com.moto.voice.tts.TtsRecentSpeech]). */
    const val SELF_ECHO = "self_echo"
    /**
     * Rider spoke a bare command opener ("โทรหา" / "เปิด youtube" / "เปิดวิทยุ" with
     * no payload) and the local slot-filler asked one follow-up question, then
     * combined the answer into a full sentence before re-entering the pipeline.
     * Spec v1.3.6 §2.
     */
    const val SLOT_FILLED = "slot_filled"
}

object DebugLog {
    private const val MAX = 50  // spec §9
    private val list = CopyOnWriteArrayList<DebugEntry>()
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun new(): DebugEntry {
        val e = DebugEntry()
        list.add(0, e)
        while (list.size > MAX) list.removeAt(list.size - 1)
        return e
    }

    fun entries(): List<DebugEntry> = list.toList()

    fun clear() = list.clear()

    /**
     * Field-test bug 3: the exported file was reported to contain "garbage bytes"
     * before the JSON in one session. The current file this reviewer inspected is
     * clean (`0x5B 0x0A 0x20 ...` — `[\n  {`), but we harden the write path anyway:
     *
     *   - **Explicit UTF-8** (default, but explicit for review clarity).
     *   - **Delete-if-exists** so we can never append onto a stale file with the
     *     same timestamped name (paranoia — the timestamp is unique per session).
     *   - **Buffered write via FileOutputStream** — single atomic write, no
     *     intermediate reader that could leave the file partially flushed.
     *   - **No BOM** (Kotlin's Charsets.UTF_8 never emits one; documented so future
     *     maintainers don't add one thinking it "helps" some viewer).
     */
    fun exportToFile(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "moto_voice_debug_${System.currentTimeMillis()}.json")
        if (file.exists()) file.delete()

        val json = gson.toJson(list.toList())
        file.outputStream().buffered().use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
            out.flush()
        }
        return file
    }
}

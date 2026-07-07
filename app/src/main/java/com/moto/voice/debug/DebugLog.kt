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
) {
    fun time(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))

    fun summary(): String = buildString {
        append("[${time()}]")
        if (sttFinal.isNotBlank()) append("  STT: \"$sttFinal\"")
        if (audioRoute != null) append("  route:${audioRoute}")
        if (scoState != null) append("  sco:${scoState}")
        if (scoTimeMs > 0) append("  SCO:${scoTimeMs}ms")
        if (sttTimeMs > 0) append("  STT:${sttTimeMs}ms")
        if (webhookTimeMs > 0) append("  WH:${webhookTimeMs}ms")
        if (actionTimeMs > 0) append("  ACT:${actionTimeMs}ms")
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

    fun exportToFile(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "moto_voice_debug_${System.currentTimeMillis()}.json")
        file.writeText(gson.toJson(list.toList()))
        return file
    }
}

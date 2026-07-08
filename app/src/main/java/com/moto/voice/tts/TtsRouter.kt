package com.moto.voice.tts

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.moto.voice.data.AppSettings
import com.moto.voice.debug.DebugLog
import com.moto.voice.debug.EngineChoiceReason
import java.util.concurrent.atomic.AtomicReference

/**
 * The single TTS access point for the rest of the app. Picks between Azure and Android
 * per-call, and silently falls back to Android if Azure is unavailable or fails.
 *
 * Per spec §1.3: the swap must be seamless — the rider never hears an error announcement
 * about which engine is being used, and the caller's speak / onDone callback timing is
 * indistinguishable from a pure-Android setup.
 *
 * Singleton so both the pipeline's [ThaiTTS] facade and the pre-synthesize [CacheWarmer]
 * share the same cache instance and Azure config.
 */
class TtsRouter private constructor(private val app: Context) {

    private val cache = TtsCache(app)
    private val android = AndroidTtsEngine(app)

    private val azure = AtomicReference<AzureTtsEngine?>(null)

    private data class Config(val region: String, val key: String, val voice: String)

    fun speak(
        text: String,
        onStart: (() -> Unit)?,
        onDone: (() -> Unit)?,
        onError: ((reason: String) -> Unit)?,
    ) {
        val cfg = loadConfig()
        val online = isOnline()

        // Route decision: Azure only when configured AND online. Everything else → Android.
        // Field log 1783477052378 showed every entry `ttsEngine=android` — we couldn't
        // tell whether the key was lost, the region was blank, or connectivity failed.
        // Compute the exact reason so the next field log makes the answer legible.
        val androidReason = when {
            cfg.key.isBlank() -> EngineChoiceReason.ANDROID_NO_KEY
            cfg.region.isBlank() -> EngineChoiceReason.ANDROID_NO_REGION
            !online -> EngineChoiceReason.ANDROID_OFFLINE
            else -> null
        }
        if (androidReason != null) {
            android.speak(text, onStart, onDone, onError)
            markDebug("android", reason = androidReason, error = null)
            return
        }

        val engine = azureFor(cfg)
        engine.speak(
            text,
            onStart = onStart,
            onDone = {
                markDebug("azure", reason = EngineChoiceReason.AZURE_USED, error = null)
                onDone?.invoke()
            },
            onError = { reason ->
                Log.w(TAG, "azure failed: $reason — falling back to Android silently")
                markDebug("azure_failed", reason = EngineChoiceReason.AZURE_FAILED_FALLBACK, error = reason)
                // Silent fallback per spec §1.3 — no user-facing announcement.
                android.speak(
                    text,
                    onStart = null,  // don't fire onStart twice
                    onDone = {
                        // Keep engineChoiceReason = azure_failed_fallback so the field
                        // log records that Azure was TRIED — don't overwrite with an
                        // Android success reason. Just refresh the timings.
                        markDebug("android_fallback", reason = EngineChoiceReason.AZURE_FAILED_FALLBACK, error = null)
                        onDone?.invoke()
                    },
                    onError = { androidReason2 ->
                        markDebug("android_fallback_failed", reason = EngineChoiceReason.AZURE_FAILED_FALLBACK, error = androidReason2)
                        onError?.invoke(androidReason2)
                    },
                )
            },
        )
    }

    fun stop() {
        azure.get()?.stop()
        android.stop()
    }

    /**
     * Attach the current TTS timing + engine choice to the most-recent DebugEntry.
     * Timings are pulled from [AzureTtsState] so we can distinguish synth vs playback
     * ms and know whether the cache served it. For pure-Android calls the timings
     * remain zero — they were never synthesised via Azure.
     *
     * [reason] is the [EngineChoiceReason] constant explaining why THIS engine was
     * chosen — populates `engineChoiceReason` so field logs make the fallback path
     * legible (spec-round-3 bug 3, log 1783477052378).
     */
    private fun markDebug(engine: String, reason: String, error: String?) {
        val head = DebugLog.entries().firstOrNull() ?: return
        head.ttsEngine = engine
        head.engineChoiceReason = reason
        head.ttsSynthMs = AzureTtsState.synthMs().coerceAtLeast(0)
        head.ttsPlayMs = AzureTtsState.playMs().coerceAtLeast(0)
        head.cacheHit = AzureTtsState.cacheHit()
        if (error != null) head.azureError = error
    }

    private fun azureFor(cfg: Config): AzureTtsEngine {
        val existing = azure.get()
        if (existing != null) return existing
        val fresh = AzureTtsEngine(app, cfg.region, cfg.key, cfg.voice, cache)
        return if (azure.compareAndSet(null, fresh)) fresh else azure.get()!!
    }

    private fun loadConfig(): Config {
        val s = AppSettings(app)
        return Config(
            region = s.azureRegion,
            key = s.azureKey,
            voice = s.azureVoice,
        )
    }

    private fun isOnline(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Invalidate the Azure engine so the next speak re-reads config. */
    fun reloadAzureConfig() {
        val old = azure.getAndSet(null)
        runCatching { old?.shutdown() }
    }

    /**
     * Kick off a pre-synth of every system line into the persistent cache. WiFi-only
     * per spec §3.3. Callers: Settings after voice/rate change; Settings after successful
     * preview.
     */
    fun warmCache() {
        val cfg = loadConfig()
        if (cfg.key.isBlank() || cfg.region.isBlank()) return
        CacheWarmer(app, cfg.region, cfg.key, cfg.voice, cache).warmAllIfPossible()
    }

    /** Test hook: expose the cache for instrumentation. */
    internal fun cacheForTest(): TtsCache = cache

    companion object {
        private const val TAG = "TtsRouter"
        private val instance = AtomicReference<TtsRouter?>(null)

        fun getOrCreate(context: Context): TtsRouter {
            val existing = instance.get()
            if (existing != null) return existing
            val fresh = TtsRouter(context.applicationContext)
            return if (instance.compareAndSet(null, fresh)) fresh else instance.get()!!
        }
    }
}

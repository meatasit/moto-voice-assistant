package com.moto.voice.tts

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.moto.voice.data.AppSettings
import com.moto.voice.nlu.ErrorSpeech
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Warms the persistent tier of [TtsCache] with every line in [ErrorSpeech.allSystemLines].
 *
 * Runs in the background on a fresh Thread. Spec §3.3: WiFi only, so we don't burn
 * the rider's mobile data on ~21 short synths. If WiFi isn't up we bail — will be
 * retried on the next voice / rate change or on the next preview-success.
 */
class CacheWarmer(
    private val context: Context,
    private val region: String,
    private val key: String,
    private val voice: String,
    private val cache: TtsCache,
) {
    companion object {
        private const val TAG = "CacheWarmer"
        /** Longer than the 2s speak timeout — cache warm is best-effort background work. */
        private const val WARM_TIMEOUT_MS = 6_000L
    }

    fun warmAllIfPossible() {
        if (!isOnWifi()) {
            Log.d(TAG, "not on wifi — deferring cache warm")
            return
        }
        Thread(this::warmBlocking, "TtsCacheWarmer").start()
    }

    private fun warmBlocking() {
        val settings = runCatching { AppSettings(context) }.getOrNull()
        val rate = settings?.ttsSpeechRate ?: AppSettings.DEFAULT_TTS_RATE
        val http = OkHttpClient.Builder()
            .connectTimeout(WARM_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(WARM_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

        // Fresh voice / rate combo = drop the old persistent set so we don't grow forever.
        cache.clearPersistent()

        val lines = ErrorSpeech.allSystemLines().distinct()
        Log.d(TAG, "warming ${lines.size} system lines with voice=$voice rate=$rate")
        var ok = 0
        var fail = 0
        for (line in lines) {
            // If we already have this line in the persistent set (e.g. mid-warm restart),
            // skip. Not strictly necessary since clearPersistent above nukes them, but
            // safe in case of retries.
            if (cache.get(line, voice, rate)?.parentFile?.name == "tts_persist") continue
            val tmp = runCatching { synth(http, line, rate) }.getOrNull()
            if (tmp != null) {
                cache.putPersistent(line, voice, rate, tmp)
                tmp.delete()
                ok++
            } else fail++
        }
        Log.d(TAG, "cache warm finished: ok=$ok fail=$fail")
    }

    private fun synth(http: OkHttpClient, text: String, rate: Float): File {
        val ssml = AzureSsml.build(text, voice, rate)
        val body = ssml.toRequestBody("application/ssml+xml".toMediaType())
        val req = Request.Builder()
            .url("https://$region.tts.speech.microsoft.com/cognitiveservices/v1")
            .header("Ocp-Apim-Subscription-Key", key)
            .header("Content-Type", "application/ssml+xml")
            .header("X-Microsoft-OutputFormat", "audio-16khz-64kbitrate-mono-mp3")
            .header("User-Agent", "MotoVoice/1.2-warmer")
            .post(body)
            .build()
        val resp = http.newCall(req).execute()
        resp.use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            val bytes = r.body?.bytes() ?: throw IllegalStateException("empty body")
            val out = File.createTempFile("warm_", ".mp3", context.cacheDir)
            out.writeBytes(bytes)
            return out
        }
    }

    private fun isOnWifi(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}

package com.moto.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.moto.voice.data.AppSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Azure Speech Neural TTS engine.
 *
 * Flow per [speak] call:
 *   1. Build SSML with [AzureSsml.build] using the configured voice + rate.
 *   2. POST to /cognitiveservices/v1 with 2s timeout.
 *   3. Response body (MP3) is streamed into a temp file.
 *   4. [MediaPlayer] plays the file with USAGE_ASSISTANT so it routes to whatever
 *      the pipeline routed to (SCO helmet during interaction, phone speaker otherwise).
 *   5. **onDone fires from [MediaPlayer.OnCompletionListener]** — matching the
 *      TtsEngine contract that "done" means audio has FINISHED PLAYING.
 *
 * On any failure (network, HTTP 4xx/5xx, playback error, 2s timeout): the callback
 * chain [onError] is invoked. TtsRouter treats that as the signal to try the Android
 * engine transparently — the rider hears no error announcement.
 *
 * The engine does not maintain a queue: each [speak] cancels the previous MediaPlayer
 * (QUEUE_FLUSH semantics). This matches Android TTS behaviour.
 */
class AzureTtsEngine(
    private val context: Context,
    private val region: String,
    private val key: String,
    private val voice: String,
    private val cache: TtsCache,
) : TtsEngine {

    private companion object {
        const val TAG = "AzureTtsEngine"
        /** Spec §2.5: 2 second synth timeout before falling back to Android engine. */
        const val SYNTH_TIMEOUT_MS = 2_000L
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(SYNTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(SYNTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(SYNTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val activePlayer = AtomicReference<MediaPlayer?>()

    override fun isReady(): Boolean = key.isNotBlank() && region.isNotBlank()

    override fun speak(
        text: String,
        onStart: (() -> Unit)?,
        onDone: (() -> Unit)?,
        onError: ((reason: String) -> Unit)?,
    ) {
        if (!isReady()) {
            onError?.invoke("azure not configured")
            return
        }
        val settings = runCatching { AppSettings(context) }.getOrNull()
        val rate = settings?.ttsSpeechRate ?: AppSettings.DEFAULT_TTS_RATE

        Thread {
            val synthStart = System.currentTimeMillis()
            val cachedFile = cache.get(text, voice, rate)
            val cacheHit = cachedFile != null
            val file = if (cachedFile != null) {
                Log.d(TAG, "cache hit for '${text.take(40)}'")
                cachedFile
            } else {
                val fresh = runCatching { synthesizeToFile(text, rate) }
                    .onFailure { Log.w(TAG, "synth failed", it) }
                    .getOrNull()
                if (fresh == null) {
                    val elapsed = System.currentTimeMillis() - synthStart
                    AzureTtsState.setSynthTiming(elapsed, cacheHit = false)
                    AzureTtsState.recordFailure("synth failed")
                    onError?.invoke("synth failed after ${elapsed}ms")
                    return@Thread
                }
                cache.put(text, voice, rate, fresh)
                fresh
            }
            val synthMs = System.currentTimeMillis() - synthStart
            AzureTtsState.setSynthTiming(synthMs, cacheHit)

            val playStart = System.currentTimeMillis()
            playFile(file, onStart,
                onDone = {
                    AzureTtsState.setPlayTiming(System.currentTimeMillis() - playStart)
                    AzureTtsState.recordSuccess()
                    onDone?.invoke()
                },
                onError = { reason ->
                    AzureTtsState.setPlayTiming(System.currentTimeMillis() - playStart)
                    AzureTtsState.recordFailure(reason)
                    onError?.invoke(reason)
                },
            )
        }.start()
    }

    private fun synthesizeToFile(text: String, rate: Float): File {
        val ssml = AzureSsml.build(text, voice, rate)
        val body = ssml.toRequestBody("application/ssml+xml".toMediaType())
        val req = Request.Builder()
            .url("https://$region.tts.speech.microsoft.com/cognitiveservices/v1")
            .header("Ocp-Apim-Subscription-Key", key)
            .header("Content-Type", "application/ssml+xml")
            .header("X-Microsoft-OutputFormat", "audio-16khz-64kbitrate-mono-mp3")
            .header("User-Agent", "MotoVoice/1.2")
            .post(body)
            .build()
        val resp = http.newCall(req).execute()
        resp.use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            val bytes = r.body?.bytes() ?: throw IllegalStateException("empty body")
            val out = File.createTempFile("azure_tts_", ".mp3", context.cacheDir)
            out.writeBytes(bytes)
            return out
        }
    }

    private fun playFile(
        file: File,
        onStart: (() -> Unit)?,
        onDone: (() -> Unit)?,
        onError: ((String) -> Unit)?,
    ) {
        val id = UUID.randomUUID().toString().take(8)
        val player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
        // Swap active player atomically so a fresh speak() cancels the old.
        activePlayer.getAndSet(player)?.let { runCatching { it.release() } }

        try {
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener {
                Log.d(TAG, "[$id] playback start")
                onStart?.invoke()
                it.start()
            }
            player.setOnCompletionListener {
                Log.d(TAG, "[$id] playback done")
                activePlayer.compareAndSet(player, null)
                runCatching { player.release() }
                onDone?.invoke()
            }
            player.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "[$id] MediaPlayer error what=$what extra=$extra")
                activePlayer.compareAndSet(player, null)
                runCatching { player.release() }
                onError?.invoke("playback error $what/$extra")
                true
            }
            player.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "[$id] prepare failed", e)
            activePlayer.compareAndSet(player, null)
            runCatching { player.release() }
            onError?.invoke("prepare failed: ${e.message}")
        }
    }

    override fun stop() {
        activePlayer.getAndSet(null)?.let { runCatching { it.release() } }
    }

    override fun shutdown() { stop() }
}

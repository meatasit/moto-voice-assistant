package com.moto.voice.tts

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Disk cache for synthesised MP3 audio, keyed by SHA-256 of (text + voice + rate).
 *
 * Two tiers:
 *  - **LRU**  under [context.cacheDir]/tts_lru/ — bounded to [MAX_LRU_BYTES], evicted
 *    by oldest-mtime when full. Free to be cleared by the OS.
 *  - **Persist** under [context.filesDir]/tts_persist/ — never evicted here. Used by
 *    the pre-synth warmer to keep the system lines (ErrorSpeech.*) instantly available
 *    without hitting Azure. Small (< 5MB in practice; ~21 short utterances × ~50KB).
 *
 * Reads consult persist first, then LRU. Writes always go to LRU unless the caller
 * uses [putPersistent].
 */
class TtsCache(private val context: Context) {

    companion object {
        private const val TAG = "TtsCache"
        /** Spec §3.1: ~50 MB. */
        const val MAX_LRU_BYTES = 50L * 1024L * 1024L
        private const val LRU_DIR = "tts_lru"
        private const val PERSIST_DIR = "tts_persist"
    }

    private val lruDir: File get() = File(context.cacheDir, LRU_DIR).also { it.mkdirs() }
    private val persistDir: File get() = File(context.filesDir, PERSIST_DIR).also { it.mkdirs() }

    /**
     * Deterministic key so the same (text, voice, rate) always maps to the same file.
     * SHA-256 hex — collision-free enough for our purposes.
     */
    fun keyFor(text: String, voice: String, rate: Float): String {
        val bytes = "$text$voice${"%.2f".format(rate)}".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
    }

    fun get(text: String, voice: String, rate: Float): File? {
        val key = keyFor(text, voice, rate)
        val persist = File(persistDir, "$key.mp3")
        if (persist.exists()) return persist
        val lru = File(lruDir, "$key.mp3")
        if (lru.exists()) {
            // Touch mtime for LRU-freshness tracking.
            lru.setLastModified(System.currentTimeMillis())
            return lru
        }
        return null
    }

    fun put(text: String, voice: String, rate: Float, source: File) {
        val key = keyFor(text, voice, rate)
        val dest = File(lruDir, "$key.mp3")
        runCatching {
            source.copyTo(dest, overwrite = true)
            evictIfNeeded()
        }.onFailure { Log.w(TAG, "put failed", it) }
    }

    fun putPersistent(text: String, voice: String, rate: Float, source: File) {
        val key = keyFor(text, voice, rate)
        val dest = File(persistDir, "$key.mp3")
        runCatching { source.copyTo(dest, overwrite = true) }
            .onFailure { Log.w(TAG, "putPersistent failed", it) }
    }

    /** Delete all persistent-set files. Called when voice or rate changes. */
    fun clearPersistent() {
        runCatching {
            persistDir.listFiles()?.forEach { it.delete() }
        }
    }

    /**
     * Delete all LRU-tier files but leave the persistent set (pre-synthesized system
     * lines) alone. Called from [com.moto.voice.MotoVoiceApplication.onTrimMemory] on
     * TRIM_MEMORY_MODERATE+ (spec v1.3.8 A4) — the LRU is regenerable from Azure on
     * demand, but the persistent lines are what keeps the app usable offline right
     * after the OS reclaims memory.
     *
     * @return number of files deleted (test hook).
     */
    fun clearLru(): Int {
        val files = runCatching { lruDir.listFiles()?.toList() ?: emptyList() }.getOrDefault(emptyList())
        var deleted = 0
        files.forEach { if (it.delete()) deleted++ }
        Log.d(TAG, "clearLru deleted $deleted files")
        return deleted
    }

    /** Total on-disk size of the LRU tier (excludes persist). Test hook. */
    fun lruBytes(): Long = runCatching {
        (lruDir.listFiles() ?: emptyArray()).sumOf { it.length() }
    }.getOrDefault(0L)

    private fun evictIfNeeded() {
        val files = (lruDir.listFiles() ?: return).toMutableList()
        var total = files.sumOf { it.length() }
        if (total <= MAX_LRU_BYTES) return
        files.sortBy { it.lastModified() }  // oldest first
        val it = files.iterator()
        while (it.hasNext() && total > MAX_LRU_BYTES) {
            val f = it.next()
            val sz = f.length()
            if (f.delete()) total -= sz
        }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}

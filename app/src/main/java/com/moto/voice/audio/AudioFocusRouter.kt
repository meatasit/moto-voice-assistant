package com.moto.voice.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Requests transient-may-duck audio focus so other apps (Spotify, YouTube Music, Google
 * Maps navigation, incoming call ringtone, etc.) automatically lower or pause while
 * the assistant is speaking / listening. Focus is abandoned at the end of the
 * interaction, letting the OS restore the previous media state.
 *
 * On API 26+ we use the [AudioFocusRequest] API. Below that, the deprecated
 * [AudioManager.requestAudioFocus] taking a listener + hint is used.
 *
 * This class does NOT touch our own [com.moto.voice.media.FmPlayerService] — that has
 * its own pause/resume path so the FM can seamlessly resume with proper metadata.
 */
class AudioFocusRouter(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null

    /** Kept as a member so both request() and abandon() reference the same instance. */
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.d(TAG, "focus change: $change")
        // We intentionally do NOT stop the pipeline on transient loss — losing focus
        // to (say) the caller ringtone is a system-level event the pipeline can't
        // usefully react to mid-interaction; PhoneStateGuard handles pre-flight.
    }

    /**
     * @return true if focus was granted. Callers should still proceed even on false —
     *   ducking is best-effort UX, not a correctness requirement.
     */
    fun request(): Boolean {
        val am = audioManager ?: return false
        if (request != null) return true  // already holding

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(listener)
            .setWillPauseWhenDucked(false)  // we're the ducker, not the duckee
            .build()
        request = req
        val result = runCatching { am.requestAudioFocus(req) }.getOrNull()
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.d(TAG, "focus request granted=$granted result=$result")
        if (!granted) request = null
        return granted
    }

    fun abandon() {
        val am = audioManager ?: return
        val req = request ?: return
        runCatching { am.abandonAudioFocusRequest(req) }
        request = null
        Log.d(TAG, "focus abandoned")
    }

    private companion object { const val TAG = "AudioFocusRouter" }
}

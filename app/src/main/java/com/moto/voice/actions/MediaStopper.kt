package com.moto.voice.actions

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import com.moto.voice.media.FmPlayerService

/**
 * Media pause dispatchers.
 *
 * Split into single-responsibility calls because the field-test bug required careful
 * sequencing (upgrade focus → stop our FM → delay → dispatch pause → delay) that a
 * compound "just stop everything" fire-and-forget couldn't express. The pipeline
 * now orchestrates the sequence itself via [com.moto.voice.pipeline.VoiceCommandPipeline.executeStopSequence].
 */
object MediaStopper {

    /**
     * Dispatch KEYCODE_MEDIA_PAUSE to whichever media session currently holds audio
     * focus. Best-effort — some OEMs intercept media button broadcasts.
     *
     * Caller MUST ensure our own MediaSession (FmPlayerService) has been released
     * OR is inactive before calling this, otherwise the key event may be absorbed
     * by our own session before it reaches the target app (YouTube, Spotify, etc.).
     */
    fun dispatchExternalPauseOnly(context: Context) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
    }

    /**
     * Convenience used by [com.moto.voice.HistoryActivity] "tap to repeat stop" — this
     * path is NOT inside the pipeline so it has no access to AudioFocusRouter, and the
     * user tapped from foreground (not while riding) so YouTube-resume-after-focus-release
     * is a minor annoyance rather than the field-test showstopper it was in the pipeline.
     */
    fun stopAnySimple(context: Context) {
        runCatching {
            context.startService(
                Intent(context, FmPlayerService::class.java).setAction(FmPlayerService.ACTION_STOP)
            )
        }
        dispatchExternalPauseOnly(context)
    }
}

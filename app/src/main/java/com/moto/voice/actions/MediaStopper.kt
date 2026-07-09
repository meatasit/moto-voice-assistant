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
     * Dispatch a single KEYCODE_MEDIA_PAUSE (DOWN+UP) — the counterpart to
     * [dispatchMediaPlay]. Used by the v1.3.8 pre-pause step (spec A1) to force the
     * current playback into a known-idle state BEFORE we launch a YouTube deep link,
     * so the 3-second isMusicActive nudge check can't false-positive on the previous
     * video's still-decoding audio and skip the nudge for the new one.
     */
    fun dispatchMediaPause(context: Context) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
    }

    /**
     * Dispatch a single KEYCODE_MEDIA_PLAY (DOWN+UP). Used by the YouTube nudge:
     * field log 1783581952116 showed the YouTube intent succeeding, our SCO fully
     * torn down (scoTeardownMs=818) and yet the video staying paused — the rider
     * captured "มันยังไม่เปิดเลยเงียบอยู่" in the mic to confirm. Nudging with a
     * play key at t+3s is the platform-agnostic way to wake it up without needing
     * the YouTube app's internal control.
     *
     * Only call after verifying [AudioManager.isMusicActive] is false — otherwise
     * we'd interrupt something that's actually playing.
     */
    fun dispatchMediaPlay(context: Context) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
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

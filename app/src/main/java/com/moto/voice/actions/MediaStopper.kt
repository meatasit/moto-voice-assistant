package com.moto.voice.actions

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import com.moto.voice.media.FmPlayerService

/**
 * Two-tier stop:
 *  1. If our own [FmPlayerService] is running, tell it to stop directly (clean shutdown +
 *     notification dismissal).
 *  2. Otherwise dispatch KEYCODE_MEDIA_PAUSE to whichever media session currently holds
 *     audio focus — this lets us pause Spotify, YouTube Music, etc. that we didn't start.
 *
 * Not perfect: on some OEMs the media button broadcast is intercepted. That's acceptable
 * — this is a best-effort convenience command; the user can always long-press their
 * headset button.
 */
object MediaStopper {

    fun stopAny(context: Context) {
        // Tier 1: our own player. Idempotent — service ignores STOP when idle.
        context.startService(
            Intent(context, FmPlayerService::class.java).setAction(FmPlayerService.ACTION_STOP)
        )
        // Tier 2: dispatch media key to system media session.
        dispatchPause(context)
    }

    private fun dispatchPause(context: Context) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
    }
}

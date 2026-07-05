package com.moto.voice.actions

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import com.moto.voice.media.FmPlayerService

/**
 * Two-tier stop:
 *  1. If our own [FmPlayerService] is running, tell it to stop directly (clean shutdown).
 *  2. Dispatch KEYCODE_MEDIA_PAUSE to whichever media session currently holds audio focus
 *     — this lets us pause Spotify, YouTube Music, etc. that we didn't start.
 *
 * Not perfect: on some OEMs the media button broadcast is intercepted. That's acceptable
 * — this is a best-effort convenience; the user can always long-press their headset button.
 */
object MediaStopper {

    fun stopAny(context: Context) {
        stopOurRadio(context)
        dispatchPause(context)
    }

    private fun stopOurRadio(context: Context) {
        val intent = Intent(context, FmPlayerService::class.java).setAction(FmPlayerService.ACTION_STOP)
        // Since Android O the service may need startForegroundService if not already foreground.
        // If not running, the service will just handle STOP and stop itself.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startService(intent)  // STOP action doesn't need foreground promotion
            } else {
                context.startService(intent)
            }
        }
    }

    private fun dispatchPause(context: Context) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
    }
}

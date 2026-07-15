package com.moto.voice.media

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * v1.3.24 — invisible trampoline that lets us open a media app while the screen is
 * LOCKED / off, the rider's primary situation (helmet + phone in pocket).
 *
 * Field logs 1784078976959 / 1784082476746 proved that a bare
 * `startActivity(vnd.youtube:…)` from our background voice service is silently dropped
 * by Background-Activity-Launch (BAL) when the screen is locked — YouTube never opens,
 * only the honest "unlock first" error was possible, which is useless while riding.
 *
 * The sanctioned way to get an Activity on screen from the background over the keyguard
 * is a full-screen-intent notification (what alarm / incoming-call apps use). That
 * notification launches THIS activity. Because we declare `showWhenLocked` +
 * `turnScreenOn` (manifest + the runtime calls below), we become a real foreground
 * activity over the lock screen — and a foreground activity is allowed to
 * `startActivity` the actual YouTube deep link. Audio then plays even if YouTube's own
 * UI stays behind a secure keyguard.
 *
 * See [LockScreenLauncher] for the notification side, gated on USE_FULL_SCREEN_INTENT.
 */
class LockLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Manifest sets showWhenLocked/turnScreenOn, but set them at runtime too so the
        // window is guaranteed to come up over the keyguard and wake the screen (API 27+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        // Best-effort: dismiss a non-secure (swipe) keyguard so YouTube's UI is visible.
        // For a secure lock this is a no-op for our purposes — audio still starts.
        runCatching {
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        }

        @Suppress("DEPRECATION")
        val target: Intent? = intent?.getParcelableExtra(EXTRA_TARGET_INTENT)
        if (target != null) {
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(target) }
                .onFailure { Log.w(TAG, "LockLaunchActivity: startActivity(target) failed", it) }
        } else {
            Log.w(TAG, "LockLaunchActivity: no EXTRA_TARGET_INTENT — nothing to launch")
        }
        finish()
    }

    companion object {
        private const val TAG = "LockLaunchActivity"
        /** The real target Intent (e.g. the YouTube VIEW deep link) to fire over the lock screen. */
        const val EXTRA_TARGET_INTENT = "com.moto.voice.media.EXTRA_TARGET_INTENT"
    }
}

package com.moto.voice.media

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.moto.voice.MotoVoiceApplication

/**
 * v1.3.24 — launches a target Intent over the lock screen via a full-screen-intent
 * notification, the Android-sanctioned way to bring an Activity to the foreground from
 * the background while the device is locked (see [LockLaunchActivity] for the why).
 *
 * Gated on USE_FULL_SCREEN_INTENT. On Android 14+ (the rider's S24) this permission is
 * NOT auto-granted to non-call/alarm apps — [canUseFullScreenIntent] reports whether the
 * rider has granted it, and [SystemStatusChecker] surfaces a row that deep-links to the
 * grant screen. Below API 34 the permission is granted at install time.
 */
object LockScreenLauncher {

    private const val TAG = "LockScreenLauncher"
    private const val NOTIF_ID = 0xB14E

    /** Whether we're allowed to fire full-screen intents (else the launch would be demoted). */
    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return runCatching { nm.canUseFullScreenIntent() }.getOrDefault(false)
    }

    /**
     * Post a high-importance full-screen-intent notification whose full-screen action is
     * [LockLaunchActivity] carrying [target]. Returns true if the notification was posted
     * (best-effort — the OS decides whether to launch it full-screen based on lock state
     * and the permission). Caller should have checked [canUseFullScreenIntent] first.
     */
    @SuppressLint("MissingPermission") // POST_NOTIFICATIONS is checked by the try/catch below.
    fun launchOverLockScreen(context: Context, target: Intent): Boolean {
        val appCtx = context.applicationContext
        val trampoline = Intent(appCtx, LockLaunchActivity::class.java).apply {
            putExtra(LockLaunchActivity.EXTRA_TARGET_INTENT, target)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val fullScreen = PendingIntent.getActivity(appCtx, REQ_CODE, trampoline, flags)

        val notif = NotificationCompat.Builder(appCtx, MotoVoiceApplication.CH_LAUNCH)
            .setSmallIcon(appCtx.applicationInfo.icon)
            .setContentTitle("กำลังเปิดสื่อให้ค่ะ")
            .setContentText("แตะเพื่อเปิดหน้าจอ")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(fullScreen)          // tap fallback if FSI is demoted
            .setFullScreenIntent(fullScreen, true) // the over-lockscreen launch
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .build()

        return runCatching {
            NotificationManagerCompat.from(appCtx).notify(NOTIF_ID, notif)
            true
        }.onFailure {
            Log.w(TAG, "launchOverLockScreen: notify failed (POST_NOTIFICATIONS denied?)", it)
        }.getOrDefault(false)
    }

    private const val REQ_CODE = 0xB1
    private const val TIMEOUT_MS = 12_000L
}

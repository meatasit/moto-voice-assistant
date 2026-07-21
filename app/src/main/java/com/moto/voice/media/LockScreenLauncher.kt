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
    private const val NOTIF_ID_BASE = 0xB14E

    /**
     * v1.3.29 — the id of the notification we last posted, so the next launch can cancel it.
     * Ids ROTATE per launch: the OS only fires a full-screen intent when a notification is
     * newly posted, so re-using a still-active id makes `notify` an update and the launch is
     * silently demoted (field log 1784256366258: `fsiRan=false` on both the first fire and the
     * 2.5s re-fire, well inside this notification's 12s lifetime).
     */
    private var lastNotifId: Int? = null
    private var launchSeq = 0

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
     *
     * v1.3.30 — [title]/[text] are the notification copy. They default to the media wording
     * but the call path (field log 1784551582120: `ACTION_CALL` BAL-dropped while locked,
     * same silent-drop as the media deep link) passes call-specific copy so the heads-up
     * fallback reads correctly if the OS demotes the FSI.
     */
    @SuppressLint("MissingPermission") // POST_NOTIFICATIONS is checked by the try/catch below.
    fun launchOverLockScreen(
        context: Context,
        target: Intent,
        title: String = "กำลังเปิดสื่อให้ค่ะ",
        text: String = "แตะเพื่อเปิดหน้าจอ",
    ): Boolean {
        val appCtx = context.applicationContext
        val trampoline = Intent(appCtx, LockLaunchActivity::class.java).apply {
            putExtra(LockLaunchActivity.EXTRA_TARGET_INTENT, target)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Retire the previous launch notification first. While it is still posted, a notify()
        // on its id is an update and the OS will not honor the full-screen intent again.
        val nmc = NotificationManagerCompat.from(appCtx)
        lastNotifId?.let { runCatching { nmc.cancel(it) } }
        val notifId = NOTIF_ID_BASE + (launchSeq++ and 0xF)

        // A rotating request code too, so the PendingIntent carries THIS launch's target
        // rather than mutating the one the previous notification still holds.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val fullScreen = PendingIntent.getActivity(appCtx, notifId, trampoline, flags)

        val notif = NotificationCompat.Builder(appCtx, MotoVoiceApplication.CH_LAUNCH)
            .setSmallIcon(appCtx.applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(fullScreen)          // tap fallback if FSI is demoted
            .setFullScreenIntent(fullScreen, true) // the over-lockscreen launch
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .build()

        return runCatching {
            nmc.notify(notifId, notif)
            lastNotifId = notifId
            true
        }.onFailure {
            Log.w(TAG, "launchOverLockScreen: notify failed (POST_NOTIFICATIONS denied?)", it)
        }.getOrDefault(false)
    }

    private const val TIMEOUT_MS = 12_000L
}

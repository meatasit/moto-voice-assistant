package com.moto.voice.pipeline

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.moto.voice.MainActivity
import com.moto.voice.MotoVoiceApplication.Companion.CH_LISTENING

/**
 * Posts a compact notification with a PendingIntent that deep-links to the exact
 * OS settings screen that fixes the reported [PreflightCheck.Issue]. Tapping puts
 * the rider ONE tap away from resolving the issue instead of hunting through menus.
 */
object PreflightNotification {

    private const val NOTIF_ID = 45

    fun show(context: Context, issue: PreflightCheck.Issue) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        val target = intentFor(context, issue.kind)
        val pi = PendingIntent.getActivity(context, 0, target, PendingIntent.FLAG_IMMUTABLE)

        val n = NotificationCompat.Builder(context, CH_LISTENING)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Moto Voice ต้องการสิทธิ์")
            .setContentText(issue.speak)
            .setStyle(NotificationCompat.BigTextStyle().bigText(issue.speak))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    fun cancel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(NOTIF_ID)
    }

    private fun intentFor(context: Context, kind: PreflightCheck.Issue.Kind): Intent {
        return when (kind) {
            PreflightCheck.Issue.Kind.NotDefaultAssistant -> {
                runCatching { Intent(Settings.ACTION_VOICE_INPUT_SETTINGS) }
                    .getOrElse { Intent(context, MainActivity::class.java) }
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // Perms: app details page — user long-presses "Permissions" from there.
            // Direct MANAGE_APP_PERMISSIONS isn't reliable across OEMs.
            PreflightCheck.Issue.Kind.MissingMic,
            PreflightCheck.Issue.Kind.MissingContacts,
            PreflightCheck.Issue.Kind.MissingCall -> {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }
    }
}

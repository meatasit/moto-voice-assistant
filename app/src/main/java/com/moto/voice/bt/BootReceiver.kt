package com.moto.voice.bt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.moto.voice.pipeline.PreflightCheck
import com.moto.voice.pipeline.PreflightNotification

/**
 * Runs on ACTION_BOOT_COMPLETED (and the OEM variants MyKarl / QUICKBOOT_POWERON).
 *
 * We deliberately do NOT start VoiceCommandService here — starting a foreground
 * service from a boot broadcast is restricted on Android 12+, and a persistent
 * pipeline service would just eat battery. The app has zero background work
 * outside of an actual voice interaction.
 *
 * What we DO check: whether the OS just rebooted with a permission or role loss
 * we should warn the rider about. If preflight passes, we say nothing (a silent
 * boot is a healthy boot). If it fails, the rider sees the same deep-linking
 * notification they would see on a trigger, before the first helmet button press.
 *
 * HelmetGreeter (registered dynamically from MotoVoiceApplication) handles the
 * BT ACL_CONNECTED path, so we don't double up here.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ACCEPTED_ACTIONS) return
        Log.d(TAG, "boot broadcast: $action")

        val issue = PreflightCheck(context).check()
        if (issue != null) {
            Log.w(TAG, "post-boot preflight failed: ${issue.kind}")
            PreflightNotification.show(context, issue)
        } else {
            Log.d(TAG, "post-boot preflight passed — no notification needed")
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
        val ACCEPTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            // OEM variants delivered by some Samsung / Xiaomi builds.
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}

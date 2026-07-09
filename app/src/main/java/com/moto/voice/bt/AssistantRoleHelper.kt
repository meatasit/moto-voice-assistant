package com.moto.voice.bt

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Central place for anything "make Moto Voice the default digital assistant".
 *
 * Field-test bug (v1.3.5): the onboarding "ตั้งเป็น Digital Assistant" button
 * appeared to do nothing; the same button in Settings worked. Root cause: onboarding
 * called [RoleManager.createRequestRoleIntent] first, which on the Samsung S24 Ultra
 * we ship on returns an intent that the platform silently no-ops when Samsung's own
 * assistant framework is holding the role. Settings never went through RoleManager —
 * it just fired [Settings.ACTION_VOICE_INPUT_SETTINGS] and that opened the OS picker.
 *
 * Fix: both call sites now go through this helper, and the helper opens the OS
 * picker directly. If the rider's device doesn't expose that action (very rare),
 * we fall through to app-details as a last resort so the button never appears dead.
 */
object AssistantRoleHelper {

    /**
     * Return the intent that opens the OS "default digital assistant" picker on
     * this device. Never returns null — always has a fallback so the caller can
     * launch unconditionally.
     */
    fun defaultAssistantPickerIntent(activity: Activity): Intent {
        val primary = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        return if (primary.resolveActivity(activity.packageManager) != null) primary
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
        }
    }

    /**
     * Whether Moto Voice currently holds the assistant role. On API 29+ we use the
     * [RoleManager] check (authoritative on modern Android); on older devices we
     * inspect Settings.Secure "assistant" for our package name as a best-effort.
     */
    fun isDefaultAssistant(activity: Activity): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getString(activity.contentResolver, "assistant")
                ?.contains(activity.packageName) == true
        }
    }
}

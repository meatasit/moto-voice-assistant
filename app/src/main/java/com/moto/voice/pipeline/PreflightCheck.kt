package com.moto.voice.pipeline

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.moto.voice.nlu.ErrorSpeech

/**
 * Fast health check run every time the pipeline is triggered.
 *
 * OS updates (particularly Samsung One UI) sometimes revoke individual permissions or
 * silently reset the Default Assistant role. When that happens the rider hears
 * nothing on button press — until they eventually notice, the app is dead. Spec §5.1
 * wants us to catch this: TTS the exact problem + a notification with a deep link
 * to the right settings screen.
 *
 * This class is deliberately Kotlin-plain so it's unit-testable. It reads live state
 * from a Context but has no lifecycle or state of its own.
 */
class PreflightCheck(private val context: Context) {

    /** Single missing thing the rider needs to fix. */
    data class Issue(
        val kind: Kind,
        /** TTS line matched to this issue — see [ErrorSpeech.PREFLIGHT_*]. */
        val speak: String,
    ) {
        enum class Kind { NotDefaultAssistant, MissingMic, MissingContacts, MissingCall }
    }

    /**
     * @return the first issue that would prevent normal operation, in the priority
     *   order listed below. Returns null when everything the rider needs is present.
     *   (Optional bits like BLUETOOTH_CONNECT and POST_NOTIFICATIONS are not blocking
     *   — the pipeline degrades gracefully without them.)
     */
    fun check(): Issue? {
        // Priority order: role first (fixing it is a two-tap system flow), then perms
        // in "impact if missing" order — no mic = can't do anything at all.
        if (!isDefaultAssistant()) {
            return Issue(Issue.Kind.NotDefaultAssistant, ErrorSpeech.PREFLIGHT_NOT_DEFAULT)
        }
        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
            return Issue(Issue.Kind.MissingMic, ErrorSpeech.PREFLIGHT_MISSING_MIC)
        }
        if (!hasPerm(Manifest.permission.READ_CONTACTS)) {
            return Issue(Issue.Kind.MissingContacts, ErrorSpeech.PREFLIGHT_MISSING_CONTACTS)
        }
        if (!hasPerm(Manifest.permission.CALL_PHONE)) {
            return Issue(Issue.Kind.MissingCall, ErrorSpeech.PREFLIGHT_MISSING_CALL)
        }
        return null
    }

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    private fun isDefaultAssistant(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        } else {
            Settings.Secure.getString(context.contentResolver, "assistant")
                ?.contains(context.packageName) == true
        }
    }
}

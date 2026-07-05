package com.moto.voice.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Detects whether it's a bad time to grab the microphone (call ringing / in progress).
 *
 * Primary signal is [AudioManager.mode] because it's publicly readable and reflects
 * the call state without needing READ_PHONE_STATE. If the user has already granted
 * READ_PHONE_STATE (we don't ask for it), we also consult [TelephonyManager] for a
 * second opinion.
 */
object PhoneStateGuard {

    enum class Availability { Available, InCall }

    fun availability(context: Context): Availability {
        val am = context.getSystemService(AudioManager::class.java)
        if (am != null) {
            when (am.mode) {
                AudioManager.MODE_IN_CALL,
                AudioManager.MODE_IN_COMMUNICATION,
                AudioManager.MODE_RINGTONE -> return Availability.InCall
            }
        }
        if (hasPhoneStatePermission(context)) {
            val tm = context.getSystemService(TelephonyManager::class.java)
            @Suppress("DEPRECATION")
            val state = tm?.callState
            if (state == TelephonyManager.CALL_STATE_OFFHOOK ||
                state == TelephonyManager.CALL_STATE_RINGING) return Availability.InCall
        }
        return Availability.Available
    }

    fun reasonText(a: Availability): String = when (a) {
        Availability.InCall -> "กำลังใช้สายอยู่ ลองใหม่หลังวางสาย"
        Availability.Available -> ""
    }

    private fun hasPhoneStatePermission(context: Context) =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
}

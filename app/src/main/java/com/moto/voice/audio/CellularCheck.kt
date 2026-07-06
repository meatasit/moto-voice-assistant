package com.moto.voice.audio

import android.content.Context
import android.telephony.TelephonyManager

/**
 * Freely-accessible checks (no runtime permission) for "can we actually place a
 * voice call right now" — used to short-circuit the makeCall path so the rider
 * hears a clear "no signal" message instead of a silent failure and a phantom
 * ringtone (spec §6.7).
 *
 * We DO NOT read signal strength or service state because those require
 * READ_PHONE_STATE on newer APIs — the app doesn't hold that permission and we
 * won't ask for it just for this. SIM state + phone type is enough to catch the
 * common failure modes (no SIM, airplane mode, missing radio).
 */
object CellularCheck {

    enum class Status {
        Ready,
        NoSim,
        NoRadio,
        Unknown,
    }

    fun status(context: Context): Status {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return Status.Unknown

        // Phone type NONE = the device has no telephony radio at all (Wi-Fi tablet).
        @Suppress("DEPRECATION")
        val type = tm.phoneType
        if (type == TelephonyManager.PHONE_TYPE_NONE) return Status.NoRadio

        // SIM state is publicly readable across all SDK levels.
        val sim = tm.simState
        return when (sim) {
            TelephonyManager.SIM_STATE_READY -> Status.Ready
            TelephonyManager.SIM_STATE_ABSENT,
            TelephonyManager.SIM_STATE_UNKNOWN,
            TelephonyManager.SIM_STATE_PIN_REQUIRED,
            TelephonyManager.SIM_STATE_PUK_REQUIRED,
            TelephonyManager.SIM_STATE_NETWORK_LOCKED,
            TelephonyManager.SIM_STATE_NOT_READY,
            TelephonyManager.SIM_STATE_PERM_DISABLED,
            TelephonyManager.SIM_STATE_CARD_IO_ERROR,
            TelephonyManager.SIM_STATE_CARD_RESTRICTED -> Status.NoSim
            else -> Status.Unknown
        }
    }

    /** True when [status] indicates we can attempt a call. Errs on the side of trying. */
    fun canCall(context: Context): Boolean {
        val s = status(context)
        // Unknown means we couldn't determine either way — let the OS decide by
        // attempting the call. NoSim/NoRadio are the only cases we short-circuit.
        return s == Status.Ready || s == Status.Unknown
    }
}

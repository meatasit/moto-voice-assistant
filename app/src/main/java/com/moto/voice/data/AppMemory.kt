package com.moto.voice.data

import android.content.Context

/**
 * Persistent memory of the last thing the assistant did — needed for local-intercept
 * commands like "โทรกลับ" (call back) and "เปิดวิทยุ" (resume last station) that must
 * work offline without hitting the webhook.
 *
 * Kept intentionally small: only the last successful state per category. Nothing here
 * survives a factory-reset because it lives in SharedPreferences.
 */
class AppMemory(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ── Last radio station ────────────────────────────────────────────────────
    var lastStationUrl: String?
        get() = prefs.getString(K_STATION_URL, null)
        set(v) { prefs.edit().putString(K_STATION_URL, v).apply() }

    var lastStationName: String?
        get() = prefs.getString(K_STATION_NAME, null)
        set(v) { prefs.edit().putString(K_STATION_NAME, v).apply() }

    var lastStationFrequency: Double?
        get() = if (prefs.contains(K_STATION_FREQ)) {
            java.lang.Double.longBitsToDouble(prefs.getLong(K_STATION_FREQ, 0))
        } else null
        set(v) {
            val e = prefs.edit()
            if (v == null) e.remove(K_STATION_FREQ)
            else e.putLong(K_STATION_FREQ, java.lang.Double.doubleToRawLongBits(v))
            e.apply()
        }

    fun rememberStation(url: String?, name: String?, frequency: Double?) {
        if (url.isNullOrBlank()) return
        lastStationUrl = url
        lastStationName = name
        lastStationFrequency = frequency
    }

    // ── Last spoken TTS ("พูดอีกที") ──────────────────────────────────────────
    var lastSpoken: String?
        get() = prefs.getString(K_SPOKEN, null)
        set(v) { prefs.edit().putString(K_SPOKEN, v).apply() }

    // ── Last call (for "โทรกลับ") ─────────────────────────────────────────────
    var lastCallNumber: String?
        get() = prefs.getString(K_CALL_NUMBER, null)
        set(v) { prefs.edit().putString(K_CALL_NUMBER, v).apply() }

    var lastCallName: String?
        get() = prefs.getString(K_CALL_NAME, null)
        set(v) { prefs.edit().putString(K_CALL_NAME, v).apply() }

    fun rememberCall(number: String?, name: String?) {
        if (number.isNullOrBlank()) return
        lastCallNumber = number
        lastCallName = name
    }

    fun clear() { prefs.edit().clear().apply() }

    private companion object {
        const val FILE = "moto_voice_memory"
        const val K_STATION_URL = "last_station_url"
        const val K_STATION_NAME = "last_station_name"
        const val K_STATION_FREQ = "last_station_freq"
        const val K_SPOKEN = "last_spoken"
        const val K_CALL_NUMBER = "last_call_number"
        const val K_CALL_NAME = "last_call_name"
    }
}

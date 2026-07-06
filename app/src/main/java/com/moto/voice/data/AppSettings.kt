package com.moto.voice.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val TAG = "AppSettings"

class AppSettings(context: Context) {

    companion object {
        private const val PREFS = "moto_voice_prefs"
        private const val SECURE_PREFS = "moto_voice_secure"
        private const val SECURE_PREFS_FALLBACK = "moto_voice_secure_fb"
        const val DEFAULT_WEBHOOK_URL = "https://n8n.nodes-core.com/webhook/Javis"
        const val DEFAULT_TOKEN = "meatasit"
        /** Icecast streams + slow n8n cold-starts need generous headroom; 15s per spec §1.1. */
        const val DEFAULT_TIMEOUT = 15
        const val MIN_TIMEOUT = 5
        const val MAX_TIMEOUT = 30
        const val MIN_TTS_RATE = 0.8f
        const val MAX_TTS_RATE = 1.5f
        const val DEFAULT_TTS_RATE = 1.0f
        const val MIN_ASSIST_VOLUME = 0.5f
        const val MAX_ASSIST_VOLUME = 1.5f
        const val DEFAULT_ASSIST_VOLUME = 1.0f
    }

    /** true if the auth token store is hardware-backed encrypted, false if using plaintext fallback. */
    val isTokenStoreSecure: Boolean

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val secure: SharedPreferences

    init {
        val (store, secureOk) = runCatching {
            val key = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val esp = EncryptedSharedPreferences.create(
                context, SECURE_PREFS, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            esp to true
        }.getOrElse { err ->
            Log.e(TAG, "EncryptedSharedPreferences unavailable — auth token will be stored UNENCRYPTED", err)
            context.getSharedPreferences(SECURE_PREFS_FALLBACK, Context.MODE_PRIVATE) to false
        }
        secure = store
        isTokenStoreSecure = secureOk

        // Auto-migrate legacy defaults so existing installs get the new baseline
        // without the user having to open Settings.
        migrateLegacyDefaults()
    }

    private fun migrateLegacyDefaults() {
        // If an old install had a short timeout from previous defaults (< MIN),
        // bump to the new default. Users who set a value within range keep theirs.
        val currentTimeout = prefs.getInt("timeout", DEFAULT_TIMEOUT)
        if (currentTimeout < MIN_TIMEOUT) {
            prefs.edit().putInt("timeout", DEFAULT_TIMEOUT).apply()
        }
        // Preseed the auth token if the store is empty (fresh install or previous
        // version left it blank).
        val currentToken = secure.getString("auth_token", "") ?: ""
        if (currentToken.isBlank()) {
            secure.edit().putString("auth_token", DEFAULT_TOKEN).apply()
        }
    }

    var webhookUrl: String
        get() = prefs.getString("webhook_url", DEFAULT_WEBHOOK_URL) ?: DEFAULT_WEBHOOK_URL
        set(v) { prefs.edit().putString("webhook_url", v).apply() }

    var authToken: String
        get() = secure.getString("auth_token", "") ?: ""
        set(v) { secure.edit().putString("auth_token", v).apply() }

    var timeoutSeconds: Int
        get() = prefs.getInt("timeout", DEFAULT_TIMEOUT).coerceIn(MIN_TIMEOUT, MAX_TIMEOUT)
        set(v) { prefs.edit().putInt("timeout", v.coerceIn(MIN_TIMEOUT, MAX_TIMEOUT)).apply() }

    var llmMode: Boolean
        get() = prefs.getBoolean("llm_mode", true)
        set(v) { prefs.edit().putBoolean("llm_mode", v).apply() }

    var confirmBeforeCall: Boolean
        get() = prefs.getBoolean("confirm_call", true)
        set(v) { prefs.edit().putBoolean("confirm_call", v).apply() }

    /** Spec §4/§8: default OFF — YouTube opens first result silently. */
    var askBeforeYoutube: Boolean
        get() = prefs.getBoolean("ask_youtube", false)
        set(v) { prefs.edit().putBoolean("ask_youtube", v).apply() }

    /** Spec §7/§8: default ON — TTS says "พร้อมใช้งานครับ" through the helmet on connect. */
    var greetOnConnect: Boolean
        get() = prefs.getBoolean("greet_on_connect", true)
        set(v) { prefs.edit().putBoolean("greet_on_connect", v).apply() }

    /** Spec §2.3: after a phone call ends, resume FM if we were playing before it started. */
    var resumeAfterCall: Boolean
        get() = prefs.getBoolean("resume_after_call", true)
        set(v) { prefs.edit().putBoolean("resume_after_call", v).apply() }

    /** Spec §2.4: per-app assistant volume (0.5–1.5) applied to TTS via KEY_PARAM_VOLUME. */
    var assistantVolume: Float
        get() = prefs.getFloat("assistant_volume", DEFAULT_ASSIST_VOLUME).coerceIn(MIN_ASSIST_VOLUME, MAX_ASSIST_VOLUME)
        set(v) { prefs.edit().putFloat("assistant_volume", v.coerceIn(MIN_ASSIST_VOLUME, MAX_ASSIST_VOLUME)).apply() }

    /** Spec §8: TTS speech rate 0.8 (slower) .. 1.5 (faster). Default 1.0 (normal). */
    var ttsSpeechRate: Float
        get() = prefs.getFloat("tts_rate", DEFAULT_TTS_RATE).coerceIn(MIN_TTS_RATE, MAX_TTS_RATE)
        set(v) { prefs.edit().putFloat("tts_rate", v.coerceIn(MIN_TTS_RATE, MAX_TTS_RATE)).apply() }

    /** Marker: first-run wizard completed. Set to true when the user finishes Onboarding. */
    var onboardingComplete: Boolean
        get() = prefs.getBoolean("onboarding_done", false)
        set(v) { prefs.edit().putBoolean("onboarding_done", v).apply() }
}

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
        const val DEFAULT_TIMEOUT = 4
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
    }

    var webhookUrl: String
        get() = prefs.getString("webhook_url", DEFAULT_WEBHOOK_URL) ?: DEFAULT_WEBHOOK_URL
        set(v) { prefs.edit().putString("webhook_url", v).apply() }

    var authToken: String
        get() = secure.getString("auth_token", "") ?: ""
        set(v) { secure.edit().putString("auth_token", v).apply() }

    var timeoutSeconds: Int
        get() = prefs.getInt("timeout", DEFAULT_TIMEOUT).coerceIn(1, 30)
        set(v) { prefs.edit().putInt("timeout", v.coerceIn(1, 30)).apply() }

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
}

package com.moto.voice.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AppSettings(context: Context) {

    companion object {
        private const val PREFS = "moto_voice_prefs"
        private const val SECURE_PREFS = "moto_voice_secure"
        const val DEFAULT_WEBHOOK_URL = "https://n8n.nodes-core.com/webhook/Javis"
        const val DEFAULT_TIMEOUT = 4
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val secure: SharedPreferences = runCatching {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, SECURE_PREFS, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrDefault(context.getSharedPreferences("${SECURE_PREFS}_fb", Context.MODE_PRIVATE))

    var webhookUrl: String
        get() = prefs.getString("webhook_url", DEFAULT_WEBHOOK_URL) ?: DEFAULT_WEBHOOK_URL
        set(v) = prefs.edit().putString("webhook_url", v).apply()

    var authToken: String
        get() = secure.getString("auth_token", "") ?: ""
        set(v) = secure.edit().putString("auth_token", v).apply()

    var timeoutSeconds: Int
        get() = prefs.getInt("timeout", DEFAULT_TIMEOUT)
        set(v) = prefs.edit().putInt("timeout", v).apply()

    var llmMode: Boolean
        get() = prefs.getBoolean("llm_mode", true)
        set(v) = prefs.edit().putBoolean("llm_mode", v).apply()

    var confirmBeforeCall: Boolean
        get() = prefs.getBoolean("confirm_call", true)
        set(v) = prefs.edit().putBoolean("confirm_call", v).apply()
}

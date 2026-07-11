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
        /**
         * "จังหวะรอฟัง" — how long to wait for the rider to be done speaking before
         * the recognizer finalises. Field-test complaint: pauses in the middle of a
         * sentence caused cut-off. Range 1.0..3.0 s; default 2.0 s.
         *
         * Fed to [android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS]
         * (and the POSSIBLY_COMPLETE variant scaled to 0.4×). Note: Android treats
         * these as hints only — some vendor STT engines ignore them entirely, which is
         * why slot-filling in [com.moto.voice.pipeline.VoiceCommandPipeline] exists
         * as the safety net for bare openers.
         */
        const val MIN_LISTEN_PACE_SEC = 1.0f
        const val MAX_LISTEN_PACE_SEC = 3.0f
        const val DEFAULT_LISTEN_PACE_SEC = 2.0f
        /**
         * Spec v1.3.9 §2.3 — number of prompts that get the "ตอบหลังเสียงติ๊งนะคะ"
         * teaching hint before auto-suppression. Ten interactions is enough for the
         * rider to learn the dual-beep vs single-beep language without becoming
         * chatter.
         */
        const val TEACHING_MODE_BUDGET = 10
        const val PERSONA_FEMININE = "feminine"
        const val PERSONA_MASCULINE = "masculine"

        // Azure defaults (spec §4.1, §4.3).
        const val DEFAULT_AZURE_REGION = "southeastasia"
        const val AZURE_VOICE_PREMWADEE = "th-TH-PremwadeeNeural"  // feminine
        const val AZURE_VOICE_NIWAT = "th-TH-NiwatNeural"          // masculine
        const val AZURE_VOICE_ACHARA = "th-TH-AcharaNeural"        // feminine
        const val DEFAULT_AZURE_VOICE = AZURE_VOICE_PREMWADEE

        val AZURE_VOICES = listOf(AZURE_VOICE_PREMWADEE, AZURE_VOICE_NIWAT, AZURE_VOICE_ACHARA)
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

    /** Spec §7/§8: default ON — TTS says a short greeting through the helmet on connect. */
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

    /**
     * Spec v1.3.6 §1 — hint the recognizer to wait this many seconds of silence
     * before finalising the STT result. Range 1.0..3.0, default 2.0 (was hard-coded
     * to 1.2 s in v1.3.5 which cut riders off mid-sentence).
     */
    var listenPaceSeconds: Float
        get() = prefs.getFloat("listen_pace", DEFAULT_LISTEN_PACE_SEC).coerceIn(MIN_LISTEN_PACE_SEC, MAX_LISTEN_PACE_SEC)
        set(v) { prefs.edit().putFloat("listen_pace", v.coerceIn(MIN_LISTEN_PACE_SEC, MAX_LISTEN_PACE_SEC)).apply() }

    /**
     * Spec v1.3.8 B2 — "คุยต่อเนื่องหลังตอบ". After finish-eligible actions (chat, none,
     * cancelled call, stop) the pipeline auto-opens a 4-second follow-up window so the
     * rider can keep talking without another BVRA press. Default ON.
     */
    var followupEnabled: Boolean
        get() = prefs.getBoolean("followup_enabled", true)
        set(v) { prefs.edit().putBoolean("followup_enabled", v).apply() }

    /**
     * Spec v1.3.9 §2.3 — how many more question-answer prompts still get the
     * "ตอบหลังเสียงติ๊งนะคะ" teaching hint. Starts at [TEACHING_MODE_BUDGET] on install
     * and decrements per prompt that fires. Clamps at 0. Intentionally not part of
     * the backup schema — this is a per-install onboarding metric; restoring an old
     * backup with a spent budget onto a fresh device would defeat the purpose.
     *
     * A negative value in prefs (never set) is treated as [TEACHING_MODE_BUDGET] so
     * fresh installs get the full 10 without needing an onCreate migration.
     */
    var teachingUsesLeft: Int
        get() = prefs.getInt("teaching_uses_left", TEACHING_MODE_BUDGET).coerceAtLeast(0)
        set(v) { prefs.edit().putInt("teaching_uses_left", v.coerceAtLeast(0)).apply() }

    /** Convenience — is the teaching hint still allowed? */
    val isTeachingModeActive: Boolean
        get() = teachingUsesLeft > 0

    /** Marker: first-run wizard completed. Set to true when the user finishes Onboarding. */
    var onboardingComplete: Boolean
        get() = prefs.getBoolean("onboarding_done", false)
        set(v) { prefs.edit().putBoolean("onboarding_done", v).apply() }

    /**
     * Which polite-particle set the assistant uses. Serialized as "feminine" / "masculine"
     * to keep exported backup JSON stable. Default feminine to match v1.x baseline behaviour.
     */
    var persona: String
        get() = prefs.getString("persona", PERSONA_FEMININE) ?: PERSONA_FEMININE
        set(v) { prefs.edit().putString("persona", v).apply() }

    // ─── Azure Neural TTS (Sprint I) ─────────────────────────────────────────
    var azureRegion: String
        get() = prefs.getString("azure_region", DEFAULT_AZURE_REGION) ?: DEFAULT_AZURE_REGION
        set(v) { prefs.edit().putString("azure_region", v).apply() }

    /** Stored in EncryptedSharedPreferences (same slot as auth token). Blank = Azure disabled. */
    var azureKey: String
        get() = secure.getString("azure_key", "") ?: ""
        set(v) { secure.edit().putString("azure_key", v).apply() }

    var azureVoice: String
        get() = prefs.getString("azure_voice", DEFAULT_AZURE_VOICE) ?: DEFAULT_AZURE_VOICE
        set(v) { prefs.edit().putString("azure_voice", v).apply() }
}

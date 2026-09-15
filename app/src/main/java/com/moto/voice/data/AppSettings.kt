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

        /** Values for [llmProvider]. Wire format — the webhook reads these exact strings. */
        const val LLM_API = "api"
        const val LLM_LOCAL = "local"


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
        // v1.4.0 — Azure is gone; don't leave a dead subscription key sitting in the store.
        if (secure.contains("azure_key")) secure.edit().remove("azure_key").apply()
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

    /**
     * v1.4.0 — which brain answers: [LLM_API] (cloud, via n8n → OpenAI) or [LLM_LOCAL]
     * (Ollama on the home PC). Sent to the webhook as `llm` and routed there; the app never
     * holds an API key.
     *
     * Default API. Field log 1789440952407: webhookTimeMs 30.7s → 15.4s (client timeout) →
     * 9.3s → 4.1s → 1.6s across five consecutive commands — the local model was loading
     * into a busy GPU and the first commands of the ride paid for it. Rider's call:
     * *"Default เป็น Api key ก่อน เพื่อให้มันนิ่งๆ ไม่มีตัวแปรเรื่อง Local LLM"*.
     */
    var llmProvider: String
        get() = prefs.getString("llm_provider", LLM_API).let { if (it == LLM_LOCAL) LLM_LOCAL else LLM_API }
        set(v) { prefs.edit().putString("llm_provider", if (v == LLM_LOCAL) LLM_LOCAL else LLM_API).apply() }

    var confirmBeforeCall: Boolean
        get() = prefs.getBoolean("confirm_call", true)
        set(v) { prefs.edit().putBoolean("confirm_call", v).apply() }

    /** Spec §4/§8: default OFF — YouTube opens first result silently. */
    var askBeforeYoutube: Boolean
        get() = prefs.getBoolean("ask_youtube", false)
        set(v) { prefs.edit().putBoolean("ask_youtube", v).apply() }

    /** Spec §7/§8: default ON — TTS says a short greeting through the helmet on connect. */
    /**
     * v1.3.41 — play the earcons on STREAM_VOICE_CALL while SCO is up, instead of
     * STREAM_MUSIC.
     *
     * Default OFF, and deliberately a setting rather than a behaviour. Field logs keep
     * showing the ready cue landing on the phone speaker with `scoState=connected` — the
     * rider has never once heard the first-press cue — and the VOICE_CALL stream is the one
     * the SCO link actually carries. But v1.3.38 shipped exactly this change unconditionally
     * and made the app unusable with the helmet on (log 1786763666528 proved it: the same
     * build worked perfectly with no helmet, i.e. with this path disabled).
     *
     * So it goes behind a switch he can flip while parked, and the tone calls are guarded
     * (see Earcon.play) so it can no longer take an interaction down either way.
     */
    var earconOnScoStream: Boolean
        get() = prefs.getBoolean("earcon_on_sco_stream", false)
        set(v) { prefs.edit().putBoolean("earcon_on_sco_stream", v).apply() }

    /**
     * v1.3.42 — open YouTube with `https://www.youtube.com/watch?v=…` (targeted at the
     * YouTube package) instead of the `vnd.youtube:` custom scheme. Default ON.
     *
     * The custom scheme is why locked switches don't land. When YouTube's task already
     * exists, a `vnd.youtube:` VIEW intent with NEW_TASK just brings that task forward and
     * the new video is never delivered — field log 1786104958601 has eleven consecutive
     * switches failing that way, 22 deliveries, none landing, while log 1786763666528 shows
     * the same code switching fine with the screen UNLOCKED. Forcing it with CLEAR_TASK
     * (v1.3.36) works but restarts the whole app, which is slow enough that the result
     * arrives after we've given up — and then lands on top of the NEXT command.
     *
     * An https App Link goes through the normal intent-filter path rather than a private
     * scheme, so it has a real chance of being delivered to the running activity instead of
     * swallowed. Never tried before this: the URL has been in the code since day one, but
     * only as the fallback for devices without the YouTube app, so on this phone it has
     * never once executed.
     *
     * Safe by construction — the intent is package-targeted (no app chooser) and falls back
     * to `vnd.youtube:` if YouTube can't handle it. Worst case matches today's behaviour.
     */
    var youtubeWebLink: Boolean
        get() = prefs.getBoolean("youtube_web_link", true)
        set(v) { prefs.edit().putBoolean("youtube_web_link", v).apply() }

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
     * Spec v1.3.11 §3.1 — "ยืนยันเมื่อเริ่มเล่น". After the YouTube deep-link fires
     * and the [com.moto.voice.media.MediaSessions] controller reports STATE_PLAYING,
     * the pipeline speaks a short "เล่นแล้วค่ะ" so the rider on a helmet (who can't
     * glance at the screen) knows playback actually started. Default ON per rider
     * approval. Only affects YouTube — FM's stream audio is its own confirmation.
     */
    var confirmMediaStart: Boolean
        get() = prefs.getBoolean("confirm_media_start", true)
        set(v) { prefs.edit().putBoolean("confirm_media_start", v).apply() }

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

}

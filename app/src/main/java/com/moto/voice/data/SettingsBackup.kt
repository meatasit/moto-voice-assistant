package com.moto.voice.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName

/**
 * On-disk JSON schema for spec §8 backup/restore. Includes every setting the rider
 * could reasonably want to preserve across factory resets / device swaps EXCEPT for
 * secrets — auth token and any future keys must be re-entered by hand.
 *
 * The [version] field lets us evolve the schema safely: bump on breaking changes and
 * add a migration branch in [restore].
 */
data class SettingsBackup(
    val version: Int = CURRENT_VERSION,
    @SerializedName("webhook_url") val webhookUrl: String,
    @SerializedName("timeout_seconds") val timeoutSeconds: Int,
    @SerializedName("llm_mode") val llmMode: Boolean,
    @SerializedName("confirm_before_call") val confirmBeforeCall: Boolean,
    @SerializedName("ask_before_youtube") val askBeforeYoutube: Boolean,
    @SerializedName("greet_on_connect") val greetOnConnect: Boolean,
    @SerializedName("tts_speech_rate") val ttsSpeechRate: Float,
    @SerializedName("assistant_volume") val assistantVolume: Float,
    @SerializedName("resume_after_call") val resumeAfterCall: Boolean,
    @SerializedName("onboarding_complete") val onboardingComplete: Boolean,
    /**
     * Added in v1.3.6 — optional so older v2/v1 backups still parse; missing values
     * restore to the AppSettings default (2.0s). Not a schema-version bump.
     */
    @SerializedName("listen_pace_seconds") val listenPaceSeconds: Float? = null,
    /**
     * Added in v1.3.8 — spec B2 "คุยต่อเนื่องหลังตอบ" toggle. Optional so older
     * backups keep working; missing = AppSettings default (true).
     */
    @SerializedName("followup_enabled") val followupEnabled: Boolean? = null,
    val favorites: List<Favorite>,
    @SerializedName("last_station") val lastStation: LastStation?,
) {
    /**
     * @param phoneNumber Added in schema v2 (v1.3.5). Nullable so v1 backups still
     *   parse cleanly — an old backup without phone numbers restores fine, the user
     *   just loses the phone fallback until they re-add each favorite.
     */
    data class Favorite(
        @SerializedName("contact_id") val contactId: String,
        @SerializedName("display_name") val displayName: String,
        @SerializedName("phone_number") val phoneNumber: String? = null,
    )

    data class LastStation(
        val url: String,
        val name: String?,
        val frequency: Double?,
    )

    companion object {
        /**
         * v1 (v1.3.3 → v1.3.4) — favorites stored contact_id + display_name only.
         * v2 (v1.3.5+) — added phone_number so the pipeline can dial via the stored
         * number when contact-ID resolution fails post-sync. v1 backups still parse:
         * phone_number defaults to null and the favorite works without the fallback.
         */
        const val CURRENT_VERSION = 2
        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

        fun snapshot(context: Context): SettingsBackup {
            val s = AppSettings(context)
            val fav = FavoritesStore(context).list()
                .map { Favorite(it.contactId, it.displayName, it.phoneNumber) }
            val memory = AppMemory(context)
            val station = memory.lastStationUrl?.let { url ->
                LastStation(url, memory.lastStationName, memory.lastStationFrequency)
            }
            return SettingsBackup(
                webhookUrl = s.webhookUrl,
                timeoutSeconds = s.timeoutSeconds,
                llmMode = s.llmMode,
                confirmBeforeCall = s.confirmBeforeCall,
                askBeforeYoutube = s.askBeforeYoutube,
                greetOnConnect = s.greetOnConnect,
                ttsSpeechRate = s.ttsSpeechRate,
                assistantVolume = s.assistantVolume,
                resumeAfterCall = s.resumeAfterCall,
                onboardingComplete = s.onboardingComplete,
                listenPaceSeconds = s.listenPaceSeconds,
                followupEnabled = s.followupEnabled,
                favorites = fav,
                lastStation = station,
            )
        }

        fun toJson(backup: SettingsBackup): String = gson.toJson(backup)

        /**
         * @throws IllegalArgumentException if the JSON is malformed or an unsupported
         *   [version]. Caller (SettingsActivity) turns this into a Toast so the rider
         *   doesn't get a stack trace on the notification shade.
         */
        fun fromJson(json: String): SettingsBackup {
            val parsed = try {
                gson.fromJson(json, SettingsBackup::class.java)
                    ?: throw IllegalArgumentException("empty or null backup")
            } catch (e: JsonSyntaxException) {
                throw IllegalArgumentException("ไฟล์ไม่ใช่ backup ที่ถูกต้อง", e)
            }
            if (parsed.version > CURRENT_VERSION) {
                throw IllegalArgumentException(
                    "backup version ${parsed.version} newer than app supports ($CURRENT_VERSION)"
                )
            }
            return parsed
        }

        fun restore(context: Context, backup: SettingsBackup) {
            val s = AppSettings(context)
            s.webhookUrl = backup.webhookUrl
            s.timeoutSeconds = backup.timeoutSeconds
            s.llmMode = backup.llmMode
            s.confirmBeforeCall = backup.confirmBeforeCall
            s.askBeforeYoutube = backup.askBeforeYoutube
            s.greetOnConnect = backup.greetOnConnect
            s.ttsSpeechRate = backup.ttsSpeechRate
            s.assistantVolume = backup.assistantVolume
            s.resumeAfterCall = backup.resumeAfterCall
            s.onboardingComplete = backup.onboardingComplete
            backup.listenPaceSeconds?.let { s.listenPaceSeconds = it }
            backup.followupEnabled?.let { s.followupEnabled = it }

            val favStore = FavoritesStore(context)
            favStore.clear()
            backup.favorites.forEach { fav ->
                favStore.add(FavoritesStore.Favorite(fav.contactId, fav.displayName, fav.phoneNumber))
            }

            backup.lastStation?.let { st ->
                AppMemory(context).rememberStation(st.url, st.name, st.frequency)
            }
        }
    }
}

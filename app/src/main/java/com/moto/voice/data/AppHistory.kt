package com.moto.voice.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.util.concurrent.CopyOnWriteArrayList

/**
 * User-facing action log — the "what did the assistant do for me lately" list.
 * Kept separate from [com.moto.voice.debug.DebugLog] because that one is a developer
 * artefact (partial STT / timing / errors). This log needs to be readable at a glance
 * and each row should be re-executable.
 *
 * Persisted as JSON in a private SharedPreferences slot (single key). Not encrypted
 * because it contains no secrets — just phone numbers and video/station names.
 * Numbers are the same ones already visible in the OS call log.
 */
data class HistoryEntry(
    val timestamp: Long,
    /** What the STT captured (raw). */
    val heard: String,
    /** What TTS said back. */
    val spoken: String,
    val action: HistoryAction,
)

sealed class HistoryAction {
    /** `type` field is only used to steer Gson polymorphic (de)serialization below. */
    abstract val type: String

    data class Call(val name: String, val number: String) : HistoryAction() {
        override val type get() = TYPE_CALL
    }
    data class YoutubeOpen(
        @SerializedName("video_id") val videoId: String,
        val title: String,
    ) : HistoryAction() { override val type get() = TYPE_YT }
    data class FmPlay(
        @SerializedName("stream_url") val streamUrl: String,
        @SerializedName("station_name") val stationName: String,
        val frequency: Double?,
    ) : HistoryAction() { override val type get() = TYPE_FM }
    object Stop : HistoryAction() { override val type get() = TYPE_STOP }
    data class Speak(val text: String) : HistoryAction() {
        override val type get() = TYPE_SPEAK
    }

    companion object {
        const val TYPE_CALL = "call"
        const val TYPE_YT = "youtube_open"
        const val TYPE_FM = "fm_play"
        const val TYPE_STOP = "stop"
        const val TYPE_SPEAK = "speak"
    }
}

/**
 * Ring buffer of [HistoryEntry] backed by SharedPreferences. Thread-safe writes via
 * a CopyOnWriteArrayList — the assistant only writes on the main pipeline thread, but
 * the UI can read at any time.
 */
class AppHistory(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val gson = GsonBuilder()
        .registerTypeAdapter(HistoryAction::class.java, HistoryActionAdapter())
        .create()
    private val entries: CopyOnWriteArrayList<HistoryEntry> =
        CopyOnWriteArrayList(load())

    fun record(entry: HistoryEntry) {
        entries.add(0, entry)
        while (entries.size > MAX) entries.removeAt(entries.size - 1)
        persist()
    }

    fun entries(): List<HistoryEntry> = entries.toList()

    fun clear() {
        entries.clear()
        prefs.edit().remove(KEY).apply()
    }

    private fun persist() {
        val json = gson.toJson(entries.toList())
        prefs.edit().putString(KEY, json).apply()
    }

    private fun load(): List<HistoryEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            gson.fromJson(raw, Array<HistoryEntry>::class.java).toList()
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val FILE = "moto_voice_history"
        const val KEY = "entries"
        const val MAX = 50
    }
}

/**
 * Polymorphic Gson adapter for [HistoryAction]. We use a discriminator field `type`
 * because Kotlin sealed classes don't survive Gson round-tripping otherwise.
 */
private class HistoryActionAdapter :
    com.google.gson.JsonSerializer<HistoryAction>,
    com.google.gson.JsonDeserializer<HistoryAction> {

    private val g = Gson()

    override fun serialize(
        src: HistoryAction,
        typeOfSrc: java.lang.reflect.Type,
        ctx: com.google.gson.JsonSerializationContext,
    ): com.google.gson.JsonElement {
        val obj = g.toJsonTree(src).asJsonObject
        obj.addProperty("type", src.type)
        return obj
    }

    override fun deserialize(
        json: com.google.gson.JsonElement,
        typeOfT: java.lang.reflect.Type,
        ctx: com.google.gson.JsonDeserializationContext,
    ): HistoryAction {
        val obj = json.asJsonObject
        return when (obj.get("type")?.asString) {
            HistoryAction.TYPE_CALL -> g.fromJson(obj, HistoryAction.Call::class.java)
            HistoryAction.TYPE_YT -> g.fromJson(obj, HistoryAction.YoutubeOpen::class.java)
            HistoryAction.TYPE_FM -> g.fromJson(obj, HistoryAction.FmPlay::class.java)
            HistoryAction.TYPE_STOP -> HistoryAction.Stop
            HistoryAction.TYPE_SPEAK -> g.fromJson(obj, HistoryAction.Speak::class.java)
            else -> HistoryAction.Stop  // unrecognized; safe fallback
        }
    }
}

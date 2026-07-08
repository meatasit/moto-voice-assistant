package com.moto.voice.data

import android.content.Context

/**
 * Persists up to [MAX] favorite contacts (slot 0..4) that the fuzzy matcher will
 * boost so the rider's most-frequent calls are less likely to be misheard AND that
 * the voice intercept "โทรหารายการโปรดหนึ่ง..ห้า" can dial directly.
 *
 * Field-test report (v1.3.4): after adding a favorite, closing/reopening the app or
 * rebooting the phone made the list appear empty. Two things fixed here:
 *
 *   1. **`commit()` instead of `apply()` on writes.** apply() writes async; a
 *      force-stop right after picking a contact could lose the write. Favorites
 *      change infrequently and the user is stationary when editing — a synchronous
 *      write is the right trade-off.
 *   2. **Phone number stored alongside contact ID** (spec §2.3). Contact _ID can
 *      change after account sync / merge; the stored phone becomes the fallback
 *      that lets [com.moto.voice.pipeline.VoiceCommandPipeline.handleFavoriteCall]
 *      still dial the right number even when ID resolution fails.
 *
 * Also refactored: the SharedPreferences access is now behind [KeyValueStorage] so
 * JVM tests (no Robolectric in this project) can simulate a full "close app → reopen
 * app" cycle by re-creating the store with the same in-memory storage instance.
 */
class FavoritesStore internal constructor(private val storage: KeyValueStorage) {

    /**
     * @param phoneNumber Nullable so we can still read entries written by v1.3.4 or
     *   earlier — pre-migration favorites have no phone. Future adds always include one.
     */
    data class Favorite(
        val contactId: String,
        val displayName: String,
        val phoneNumber: String? = null,
    )

    constructor(context: Context) : this(SharedPreferencesStorage(context))

    fun list(): List<Favorite> = (0 until MAX).mapNotNull { slot ->
        val id = storage.getString(keyId(slot)) ?: return@mapNotNull null
        val name = storage.getString(keyName(slot)) ?: return@mapNotNull null
        val phone = storage.getString(keyPhone(slot))
        Favorite(id, name, phone)
    }

    fun ids(): Set<String> = list().mapTo(mutableSetOf()) { it.contactId }

    fun isFull(): Boolean = list().size >= MAX
    fun isEmpty(): Boolean = list().isEmpty()

    /** Add [fav] if not already present and there's room. Returns true if added. */
    fun add(fav: Favorite): Boolean {
        val current = list()
        if (current.any { it.contactId == fav.contactId }) return false
        if (current.size >= MAX) return false
        val slot = firstFreeSlot() ?: return false
        storage.writeAll(mapOf(
            keyId(slot) to fav.contactId,
            keyName(slot) to fav.displayName,
            keyPhone(slot) to fav.phoneNumber,
        ))
        return true
    }

    fun remove(contactId: String) {
        (0 until MAX).forEach { slot ->
            if (storage.getString(keyId(slot)) == contactId) {
                storage.writeAll(mapOf(
                    keyId(slot) to null,
                    keyName(slot) to null,
                    keyPhone(slot) to null,
                ))
            }
        }
    }

    fun clear() = storage.clear()

    private fun firstFreeSlot(): Int? =
        (0 until MAX).firstOrNull { storage.getString(keyId(it)) == null }

    private fun keyId(slot: Int) = "fav_${slot}_id"
    private fun keyName(slot: Int) = "fav_${slot}_name"
    private fun keyPhone(slot: Int) = "fav_${slot}_phone"

    /**
     * The persistence adapter FavoritesStore delegates to. Behind an interface so
     * JVM tests can verify round-trip logic without Robolectric — see
     * `FavoritesStorePersistenceTest.InMemoryStorage`.
     *
     * writeAll applies a batch atomically (single editor commit). A null value means
     * "remove this key". Batching matters: an add is 3 keys, and we don't want a
     * half-written record surviving a process kill mid-write.
     */
    interface KeyValueStorage {
        fun getString(key: String): String?
        fun writeAll(pairs: Map<String, String?>)
        fun clear()
    }

    /**
     * The production storage — [android.content.SharedPreferences] with **`commit()`**
     * on every write. apply() would be async and could lose the write on force-stop;
     * favorites are edited once in a while by a stationary user, so the perf cost of
     * synchronous disk write is invisible and the durability win is worth it.
     */
    private class SharedPreferencesStorage(context: Context) : KeyValueStorage {
        private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        override fun getString(key: String): String? = prefs.getString(key, null)

        override fun writeAll(pairs: Map<String, String?>) {
            val editor = prefs.edit()
            pairs.forEach { (k, v) ->
                if (v == null) editor.remove(k) else editor.putString(k, v)
            }
            editor.commit()
        }

        override fun clear() {
            prefs.edit().clear().commit()
        }
    }

    companion object {
        const val MAX = 5
        private const val FILE = "moto_voice_favorites"
    }
}

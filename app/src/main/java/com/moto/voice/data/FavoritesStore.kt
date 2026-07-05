package com.moto.voice.data

import android.content.Context

/**
 * Persists up to [MAX] favorite contacts (by contact-id) that the fuzzy matcher will
 * boost so the rider's most-frequent calls are less likely to be misheard.
 *
 * Design choice: we store contact IDs, not phone numbers. The user re-adds if they
 * change contacts. Cheap and avoids stale-number bugs.
 */
class FavoritesStore(context: Context) {

    data class Favorite(val contactId: String, val displayName: String)

    companion object { const val MAX = 5 }

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun list(): List<Favorite> = (0 until MAX).mapNotNull { slot ->
        val id = prefs.getString(keyId(slot), null) ?: return@mapNotNull null
        val name = prefs.getString(keyName(slot), null) ?: return@mapNotNull null
        Favorite(id, name)
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
        prefs.edit()
            .putString(keyId(slot), fav.contactId)
            .putString(keyName(slot), fav.displayName)
            .apply()
        return true
    }

    fun remove(contactId: String) {
        (0 until MAX).forEach { slot ->
            if (prefs.getString(keyId(slot), null) == contactId) {
                prefs.edit().remove(keyId(slot)).remove(keyName(slot)).apply()
            }
        }
    }

    fun clear() = prefs.edit().clear().apply()

    private fun firstFreeSlot(): Int? =
        (0 until MAX).firstOrNull { prefs.getString(keyId(it), null) == null }

    private fun keyId(slot: Int) = "fav_${slot}_id"
    private fun keyName(slot: Int) = "fav_${slot}_name"

    private companion object { const val FILE = "moto_voice_favorites" }
}

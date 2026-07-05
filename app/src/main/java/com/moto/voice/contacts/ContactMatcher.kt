package com.moto.voice.contacts

import android.Manifest
import android.content.Context
import android.provider.ContactsContract
import androidx.annotation.RequiresPermission
import com.moto.voice.data.FavoritesStore
import com.moto.voice.nlu.ThaiNormalizer

/**
 * Loads contacts and fuzzy-matches them to a spoken query. Purely Android-glue —
 * all string-similarity logic lives in [ThaiNormalizer].
 */
class ContactMatcher(private val context: Context) {

    companion object {
        /** Score multiplier applied to Favorites-listed contacts (spec §7). Capped at 1.0. */
        private const val FAVORITE_BOOST = 1.5f
        private const val MIN_KEEP_SCORE = 0.45f
    }

    @RequiresPermission(Manifest.permission.READ_CONTACTS)
    fun findMatches(query: String): List<MatchResult> {
        val normalizedQuery = ThaiNormalizer.normalize(query)
        val favoriteIds = FavoritesStore(context).ids()
        val contacts = loadContacts()
        return contacts
            .map { contact ->
                val base = ThaiNormalizer.similarity(normalizedQuery, ThaiNormalizer.normalize(contact.displayName))
                val boosted = if (contact.id in favoriteIds) (base * FAVORITE_BOOST).coerceAtMost(1.0f)
                              else base
                MatchResult(contact = contact, score = boosted)
            }
            .filter { it.score >= MIN_KEEP_SCORE }
            .sortedByDescending { it.score }
    }

    @RequiresPermission(Manifest.permission.READ_CONTACTS)
    private fun loadContacts(): List<ContactEntry> {
        val result = mutableListOf<ContactEntry>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection, null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIdx) ?: continue
                val name = cursor.getString(nameIdx)?.takeIf { it.isNotBlank() } ?: continue
                val number = cursor.getString(numIdx) ?: continue
                val cleaned = number.filter { it.isDigit() || it == '+' }
                if (cleaned.count { it.isDigit() } < 3) continue
                result.add(ContactEntry(id, name, cleaned))
            }
        }
        // Keep first phone number per contact.
        return result.distinctBy { it.id }
    }
}

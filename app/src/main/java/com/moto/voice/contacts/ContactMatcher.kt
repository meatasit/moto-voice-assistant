package com.moto.voice.contacts

import android.Manifest
import android.content.Context
import android.provider.ContactsContract
import androidx.annotation.RequiresPermission
import com.moto.voice.data.FavoritesStore
import com.moto.voice.nlu.ThaiNormalizer

/**
 * Loads contacts and fuzzy-matches them to a spoken query. String-similarity math
 * lives in [ThaiNormalizer]; this class layers Thai-specific candidate generation
 * so that STT-mis-hearing "กุลวดี" as "คุณวดี" (or vice-versa) still resolves to
 * the correct contact instead of dropping into disambiguation.
 *
 * Match hierarchy (best → worst):
 *   1. Exact match of any candidate variant against contact display name.
 *   2. StartsWith (query is a prefix of the contact name).
 *   3. Contains (query appears somewhere in the contact name).
 *   4. Levenshtein similarity above [MIN_KEEP_SCORE].
 *
 * If a UNIQUE contact scores in tier 1, the caller can skip disambiguation entirely
 * ([findMatches] returns just that one entry with score 1.0).
 */
class ContactMatcher(private val context: Context) {

    companion object {
        /** Score multiplier applied to Favorites-listed contacts (spec §7). Capped at 1.0. */
        private const val FAVORITE_BOOST = 1.5f
        private const val MIN_KEEP_SCORE = 0.45f
        /** Any score at or above this is considered a "definite" match. */
        internal const val EXACT_SCORE = 1.0f
        internal const val STARTS_WITH_SCORE = 0.95f
        internal const val CONTAINS_SCORE = 0.9f

        /**
         * Build every plausible spelling of [query] that STT might have produced.
         * Extracted to the companion so unit tests can call it without a Context.
         * Currently:
         *   - the raw query
         *   - the normalised (prefix-stripped, lowercased) form
         *   - initial-syllable homophone swaps: คุณ ↔ กุล
         *
         * Kept small on purpose — every variant multiplies matcher work, and each
         * addition needs a paired unit test showing it fixes a real observed case.
         */
        internal fun variantsOf(query: String): Set<String> {
            val out = linkedSetOf<String>()
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return out
            out.add(trimmed)
            out.add(ThaiNormalizer.normalize(trimmed))
            when {
                trimmed.startsWith("คุณ") -> out.add("กุล" + trimmed.removePrefix("คุณ"))
                trimmed.startsWith("กุล") -> out.add("คุณ" + trimmed.removePrefix("กุล"))
            }
            return out
        }
    }

    @RequiresPermission(Manifest.permission.READ_CONTACTS)
    fun findMatches(query: String): List<MatchResult> {
        val variants = ContactMatcher.variantsOf(query)
        val favoriteIds = FavoritesStore(context).ids()
        val contacts = loadContacts()

        val scored = contacts.map { contact ->
            val normalized = ThaiNormalizer.normalize(contact.displayName)
            // Take the BEST score across all variants.
            val bestVariantScore = variants.maxOf { v -> scoreOne(v, normalized) }
            val boosted = if (contact.id in favoriteIds) (bestVariantScore * FAVORITE_BOOST).coerceAtMost(1.0f)
                          else bestVariantScore
            MatchResult(contact = contact, score = boosted)
        }.filter { it.score >= MIN_KEEP_SCORE }
        .sortedByDescending { it.score }

        // Short-circuit if a UNIQUE contact scored an exact-full match. This is the
        // "คุณวดี → กุลวดี" case: user said คุณวดี, one contact is exactly กุลวดี, the
        // homophone variant matched perfectly. Skip disambig — go straight to confirm.
        val topExact = scored.filter { it.score >= EXACT_SCORE }
        if (topExact.size == 1) return listOf(topExact.first())

        return scored
    }

    /**
     * Score one query-variant against a normalised contact name. Returns the best
     * tier that matches (exact > starts-with > contains > Levenshtein similarity).
     */
    private fun scoreOne(variant: String, normalizedContact: String): Float {
        val v = ThaiNormalizer.normalize(variant)
        if (v == normalizedContact) return EXACT_SCORE
        if (normalizedContact.startsWith(v) || v.startsWith(normalizedContact)) return STARTS_WITH_SCORE
        if (normalizedContact.contains(v) || v.contains(normalizedContact)) return CONTAINS_SCORE
        return ThaiNormalizer.similarity(v, normalizedContact)
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

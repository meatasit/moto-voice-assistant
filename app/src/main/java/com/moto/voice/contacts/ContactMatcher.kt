package com.moto.voice.contacts

import android.Manifest
import android.content.Context
import android.provider.ContactsContract
import androidx.annotation.RequiresPermission
import com.moto.voice.data.FavoritesStore

class ContactMatcher(private val context: Context) {

    companion object {
        private val THAI_PREFIXES = listOf(
            "คุณ", "พี่", "น้อง",
            "นาย", "นางสาว", "นาง", "เด็กชาย", "เด็กหญิง",
            "ดร.", "ศ.", "รศ.", "ผศ.", "พล.", "พ.ต.", "ร.ต.", "ส.ต.",
            "Mr.", "Mrs.", "Ms.", "Dr.", "Prof.",
        )
        private const val HIGH_CONFIDENCE = 0.75f
        /** Multiplier applied to score for contacts in the Favorites list (spec §7). */
        private const val FAVORITE_BOOST = 1.5f
    }

    @RequiresPermission(Manifest.permission.READ_CONTACTS)
    fun findMatches(query: String): List<MatchResult> {
        val normalizedQuery = normalize(query)
        val favoriteIds = FavoritesStore(context).ids()
        val contacts = loadContacts()
        return contacts
            .map { contact ->
                val base = similarity(normalizedQuery, normalize(contact.displayName))
                val boosted = if (contact.id in favoriteIds) (base * FAVORITE_BOOST).coerceAtMost(1.0f)
                              else base
                MatchResult(contact = contact, score = boosted)
            }
            .filter { it.score >= 0.45f }
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
        // One entry per contact (keep first phone number)
        return result.distinctBy { it.id }
    }

    private fun normalize(name: String): String {
        var s = name.trim()
        for (prefix in THAI_PREFIXES) {
            if (s.startsWith(prefix)) {
                s = s.removePrefix(prefix).trim()
            }
        }
        return s.lowercase()
    }

    private fun similarity(a: String, b: String): Float {
        if (a == b) return 1.0f
        if (b.contains(a) || a.contains(b)) return 0.9f
        val distance = levenshtein(a, b)
        val maxLen = maxOf(a.length, b.length)
        return if (maxLen == 0) 1.0f else (1.0f - distance.toFloat() / maxLen).coerceAtLeast(0f)
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        return dp[a.length][b.length]
    }
}

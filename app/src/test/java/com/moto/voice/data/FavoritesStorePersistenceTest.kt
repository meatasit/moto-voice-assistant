package com.moto.voice.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field-test bug (v1.3.4): favorites disappeared after close-and-reopen or reboot.
 * Root cause candidates addressed:
 *   1. `apply()` async writes could lose data on force-stop. Fixed by moving to
 *      `commit()` in [FavoritesStore.SharedPreferencesStorage].
 *   2. No fallback when the contact _ID changed post-sync. Fixed by persisting the
 *      phone number alongside id + name.
 *
 * These JVM tests use an [InMemoryStorage] instance so we can simulate the exact
 * "process died, new instance created, storage re-read" cycle without Robolectric.
 */
class FavoritesStorePersistenceTest {

    /**
     * Test-only adapter that shares a Map across FavoritesStore instances — exactly
     * mirrors what SharedPreferences does on disk. A fresh FavoritesStore(storage) is
     * the JVM-side equivalent of "process restarted and re-read the prefs file".
     */
    private class InMemoryStorage : FavoritesStore.KeyValueStorage {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun writeAll(pairs: Map<String, String?>) {
            pairs.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
        }
        override fun clear() = map.clear()
    }

    // ─── The specific field-test symptom (spec §2.5): reopen must not lose data ─

    @Test fun favoriteSurvivesSimulatedProcessRestart() {
        val storage = InMemoryStorage()
        val before = FavoritesStore(storage)
        assertTrue(before.add(FavoritesStore.Favorite("42", "แม่", "0812345678")))

        // Simulate process restart — new FavoritesStore instance, same underlying storage.
        val after = FavoritesStore(storage)
        val list = after.list()
        assertEquals(1, list.size)
        assertEquals("42", list[0].contactId)
        assertEquals("แม่", list[0].displayName)
        assertEquals("0812345678", list[0].phoneNumber)
    }

    @Test fun fiveFavoritesRestoredInOrder() {
        val storage = InMemoryStorage()
        val a = FavoritesStore(storage)
        a.add(FavoritesStore.Favorite("1", "แม่", "0800000001"))
        a.add(FavoritesStore.Favorite("2", "พ่อ", "0800000002"))
        a.add(FavoritesStore.Favorite("3", "พี่", "0800000003"))
        a.add(FavoritesStore.Favorite("4", "น้อง", "0800000004"))
        a.add(FavoritesStore.Favorite("5", "เพื่อน", "0800000005"))

        val b = FavoritesStore(storage)
        val list = b.list()
        assertEquals(5, list.size)
        assertEquals("แม่", list[0].displayName)
        assertEquals("เพื่อน", list[4].displayName)
    }

    // ─── Phone-number persistence — the spec §2.3 fallback data ─────────────

    @Test fun phoneNumberIsPersisted() {
        val storage = InMemoryStorage()
        FavoritesStore(storage).add(FavoritesStore.Favorite("42", "แม่", "0812345678"))
        assertEquals("0812345678", FavoritesStore(storage).list().first().phoneNumber)
    }

    @Test fun favoriteWithoutPhoneStillPersists() {
        // Backward compat with the v1.3.4 shape (phone field didn't exist).
        val storage = InMemoryStorage()
        FavoritesStore(storage).add(FavoritesStore.Favorite("42", "แม่", phoneNumber = null))
        val list = FavoritesStore(storage).list()
        assertEquals(1, list.size)
        assertEquals("42", list[0].contactId)
        assertNull(list[0].phoneNumber)
    }

    @Test fun v1LegacyEntryReadsBackWithoutPhone() {
        // Simulates a favorites SharedPreferences file that was written by v1.3.4 —
        // no fav_0_phone key. FavoritesStore must not choke and must return null phone.
        val storage = InMemoryStorage()
        storage.map["fav_0_id"] = "99"
        storage.map["fav_0_name"] = "ลุง"
        // fav_0_phone deliberately absent — pre-migration state.

        val list = FavoritesStore(storage).list()
        assertEquals(1, list.size)
        assertEquals("99", list[0].contactId)
        assertNull("phone must be null when legacy record has no phone key", list[0].phoneNumber)
    }

    // ─── Semantic invariants ─────────────────────────────────────────────────

    @Test fun duplicateContactIdIsRejected() {
        val storage = InMemoryStorage()
        val s = FavoritesStore(storage)
        assertTrue(s.add(FavoritesStore.Favorite("42", "แม่", "0800000001")))
        assertFalse(s.add(FavoritesStore.Favorite("42", "แม่", "0800000002")))
        assertEquals(1, s.list().size)
    }

    @Test fun addStopsAtMax() {
        val storage = InMemoryStorage()
        val s = FavoritesStore(storage)
        repeat(FavoritesStore.MAX) { i -> s.add(FavoritesStore.Favorite("$i", "c$i", null)) }
        assertTrue(s.isFull())
        assertFalse(s.add(FavoritesStore.Favorite("overflow", "c99", null)))
    }

    @Test fun removeClearsThreeKeys() {
        val storage = InMemoryStorage()
        val s = FavoritesStore(storage)
        s.add(FavoritesStore.Favorite("42", "แม่", "0812345678"))
        s.remove("42")

        // A restarted store must see the entry as gone AND the phone slot cleared.
        val restored = FavoritesStore(storage)
        assertTrue(restored.list().isEmpty())
        assertNull(storage.map["fav_0_id"])
        assertNull(storage.map["fav_0_name"])
        assertNull(storage.map["fav_0_phone"])
    }

    @Test fun idsHelperMatchesList() {
        val storage = InMemoryStorage()
        val s = FavoritesStore(storage)
        s.add(FavoritesStore.Favorite("A", "one", "0800000001"))
        s.add(FavoritesStore.Favorite("B", "two", "0800000002"))
        assertEquals(setOf("A", "B"), s.ids())
    }

    // ─── The 3-key batch write happens atomically ────────────────────────────

    @Test fun addWritesAllThreeKeysInOneBatch() {
        var batches = 0
        val counting = object : FavoritesStore.KeyValueStorage {
            val map = mutableMapOf<String, String>()
            override fun getString(key: String): String? = map[key]
            override fun writeAll(pairs: Map<String, String?>) {
                batches++
                pairs.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
            }
            override fun clear() = map.clear()
        }
        FavoritesStore(counting).add(FavoritesStore.Favorite("42", "แม่", "0812345678"))
        assertEquals("one writeAll call = atomic batch, not 3 separate writes", 1, batches)
    }
}

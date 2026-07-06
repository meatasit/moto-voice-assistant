package com.moto.voice.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Static tests for the on-disk backup schema. The snapshot / restore paths need a
 * Context and are covered by manual + instrumentation testing on device; the JSON
 * round-trip logic is pure and tested here.
 */
class SettingsBackupJsonTest {

    private val sample = SettingsBackup(
        version = SettingsBackup.CURRENT_VERSION,
        webhookUrl = "https://n8n.example.com/webhook/x",
        timeoutSeconds = 15,
        llmMode = true,
        confirmBeforeCall = false,
        askBeforeYoutube = true,
        greetOnConnect = false,
        ttsSpeechRate = 1.2f,
        assistantVolume = 0.9f,
        resumeAfterCall = true,
        onboardingComplete = true,
        favorites = listOf(
            SettingsBackup.Favorite("42", "แม่"),
            SettingsBackup.Favorite("43", "พ่อ"),
        ),
        lastStation = SettingsBackup.LastStation("https://s/live", "Cool FM", 91.5),
    )

    @Test fun roundTripPreservesAllFields() {
        val json = SettingsBackup.toJson(sample)
        val back = SettingsBackup.fromJson(json)
        assertEquals(sample, back)
    }

    @Test fun roundTripPreservesFavorites() {
        val json = SettingsBackup.toJson(sample)
        val back = SettingsBackup.fromJson(json)
        assertEquals(2, back.favorites.size)
        assertEquals("แม่", back.favorites[0].displayName)
    }

    @Test fun roundTripPreservesLastStation() {
        val json = SettingsBackup.toJson(sample)
        val back = SettingsBackup.fromJson(json)
        assertNotNull(back.lastStation)
        assertEquals(91.5, back.lastStation!!.frequency!!, 0.0001)
    }

    @Test fun nullStationRoundTrips() {
        val stripped = sample.copy(lastStation = null)
        val back = SettingsBackup.fromJson(SettingsBackup.toJson(stripped))
        assertNull(back.lastStation)
    }

    @Test fun emptyFavoritesRoundTrips() {
        val stripped = sample.copy(favorites = emptyList())
        val back = SettingsBackup.fromJson(SettingsBackup.toJson(stripped))
        assertTrue(back.favorites.isEmpty())
    }

    @Test fun malformedJsonThrowsIllegalArgument() {
        try {
            SettingsBackup.fromJson("{not json")
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { /* ok */ }
    }

    @Test fun emptyJsonThrowsIllegalArgument() {
        try {
            SettingsBackup.fromJson("")
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { /* ok */ }
    }

    @Test fun futureVersionRejected() {
        val futureJson = SettingsBackup.toJson(sample).replace(
            "\"version\": ${SettingsBackup.CURRENT_VERSION}",
            "\"version\": ${SettingsBackup.CURRENT_VERSION + 100}"
        )
        try {
            SettingsBackup.fromJson(futureJson)
            fail("expected IllegalArgumentException on future-version backup")
        } catch (_: IllegalArgumentException) { /* ok */ }
    }

    @Test fun noAuthTokenInSchema() {
        // Explicit contract check: the JSON schema must never contain the auth token
        // — the user re-enters it after import per spec §8.
        val json = SettingsBackup.toJson(sample)
        assertTrue("backup JSON must not contain 'auth_token' — got: $json",
            !json.contains("auth_token") && !json.contains("authToken"))
    }

    @Test fun jsonIncludesReadableFieldNames() {
        val json = SettingsBackup.toJson(sample)
        // Sanity: fields are snake_cased per SerializedName annotations.
        assertTrue(json.contains("webhook_url"))
        assertTrue(json.contains("timeout_seconds"))
        assertTrue(json.contains("tts_speech_rate"))
    }
}

package com.moto.voice.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0 — the brain selector. Two things must never drift:
 *
 *  * the wire values, because the n8n workflow (`Parse Request`) switches on the literal
 *    strings — `"local"` goes to Ollama, anything else goes to the cloud;
 *  * the backup schema, because a restored backup must bring the choice back with it.
 *
 * `AppSettings` itself needs a Context, so the default is pinned here by contract and
 * exercised on device.
 */
class LlmProviderTest {

    @Test fun wireValuesMatchTheWorkflow() {
        assertEquals("api", AppSettings.LLM_API)
        assertEquals("local", AppSettings.LLM_LOCAL)
    }

    @Test fun backupCarriesTheProvider() {
        val backup = SettingsBackup(
            webhookUrl = "https://x/y",
            timeoutSeconds = 15,
            llmMode = true,
            confirmBeforeCall = true,
            askBeforeYoutube = false,
            greetOnConnect = true,
            ttsSpeechRate = 1.0f,
            assistantVolume = 1.0f,
            resumeAfterCall = true,
            onboardingComplete = true,
            llmProvider = AppSettings.LLM_LOCAL,
            favorites = emptyList(),
            lastStation = null,
        )
        val json = SettingsBackup.toJson(backup)
        assertTrue("snake_case key on disk: $json", json.contains("\"llm_provider\": \"local\""))
        assertEquals(AppSettings.LLM_LOCAL, SettingsBackup.fromJson(json).llmProvider)
    }

    @Test fun olderBackupsWithoutTheFieldStillParse() {
        // A v1.3.x export has no llm_provider — restore must leave the AppSettings default
        // (api) alone rather than fail or force "local".
        val v13 = """
            {
              "version": 2,
              "webhook_url": "https://x/y",
              "timeout_seconds": 15,
              "llm_mode": true,
              "confirm_before_call": true,
              "ask_before_youtube": false,
              "greet_on_connect": true,
              "tts_speech_rate": 1.0,
              "assistant_volume": 1.0,
              "resume_after_call": true,
              "onboarding_complete": true,
              "favorites": [],
              "last_station": null
            }
        """.trimIndent()
        assertNull(SettingsBackup.fromJson(v13).llmProvider)
    }
}

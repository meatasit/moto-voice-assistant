package com.moto.voice.media

import com.moto.voice.network.WebhookResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.2 — the exact titles from field log 1789518388540, entry 1789518322022.
 *
 * The workflow sends `video_title` cut to 60 characters for TTS; YouTube's session reports
 * the full title. The verifier's prefix rule (≥70% coverage, there to keep two episodes of
 * one series apart) rejects 60/101, so the app kept polling a video that was already
 * playing and then told the rider it had failed. Verifying against the full title fixes it
 * without loosening the rule.
 */
class FullTitleVerifyTest {

    private val spoken = "Pop Music 2025 - Top Pop Songs 2025 - Billboard Top 100 🎧🔥"
    private val session = "Pop Music 2025 - Top Pop Songs 2025 - Billboard Top 100 🎧🔥 Justin Bieber Billie Eilish Miley Cyrus"

    @Test fun theTruncatedTitleReallyDoesNotMatch() {
        // Documents the failure, so nobody "fixes" it by weakening MIN_PREFIX_RATIO.
        assertFalse(YoutubeVerify.titlesMatch(spoken, session))
        assertEquals(
            YoutubeVerify.Verdict.SWITCHED,
            YoutubeVerify.classify(currentTitle = session, priorTitle = null, expectedTitle = spoken),
        )
    }

    @Test fun theFullTitleConfirms() {
        assertEquals(
            YoutubeVerify.Verdict.CONFIRMED_TARGET,
            YoutubeVerify.classify(currentTitle = session, priorTitle = null, expectedTitle = session),
        )
    }

    @Test fun verifyTitlePrefersFullAndFallsBackToSpoken() {
        assertEquals(session, WebhookResponse.Video("E0Y8OEo_zOc", spoken, session).verifyTitle)
        assertEquals(spoken, WebhookResponse.Video("E0Y8OEo_zOc", spoken, null).verifyTitle)
        assertEquals(spoken, WebhookResponse.Video("E0Y8OEo_zOc", spoken, "  ").verifyTitle)
        assertTrue(WebhookResponse.Video("x", "").verifyTitle.isEmpty())
    }
}

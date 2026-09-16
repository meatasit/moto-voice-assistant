package com.moto.voice.media

import com.moto.voice.network.WebhookResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * v1.4.4 — the v1.4.2 full-title fix reached the two `openYoutube` call sites but not the
 * memory that "เล่นต่อ" re-fires from: `rememberYoutube` stored the 60-char speech title and
 * `playContinue` verified against it, reproducing field log 1789518388540 on that path
 * (review finding). Memory now keeps a separate verify title; the speech title is untouched
 * because "เมื่อกี้อะไร" reads it aloud.
 */
class VerifyTitleMemoryTest {

    @Before fun reset() = MediaSessionMemory.resetForTest()
    @After fun tearDown() = MediaSessionMemory.resetForTest()

    private val spoken = "Pop Music 2025 - Top Pop Songs 2025 - Billboard Top 100 🎧🔥"
    private val full = "Pop Music 2025 - Top Pop Songs 2025 - Billboard Top 100 🎧🔥 Justin Bieber Billie Eilish Miley Cyrus"
    private val first = WebhookResponse.Video("E0Y8OEo_zOc", spoken, full)
    private val second = WebhookResponse.Video("-CXDKsZY80I", "Top 20 Pop Songs 2025 ♫ Bruno Mars, Lady Gaga, Dua Lipa, Ade",
        "Top 20 Pop Songs 2025 ♫ Bruno Mars, Lady Gaga, Dua Lipa, Adele, Ed Sheeran, The Weeknd #18")

    @Test fun rememberKeepsSpeechAndVerifyTitlesApart() {
        MediaSessionMemory.rememberYoutube(listOf(first, second), first.id, first.title, first.verifyTitle)
        assertEquals(spoken, MediaSessionMemory.currentTitle())
        assertEquals(full, MediaSessionMemory.currentVerifyTitle())
    }

    @Test fun verifyTitleFallsBackToSpeechTitle() {
        // Old workflow builds send no title_full: verifying against the speech title is the
        // pre-v1.4.2 behaviour, not a regression.
        MediaSessionMemory.rememberYoutube(listOf(first), first.id, spoken)
        assertEquals(spoken, MediaSessionMemory.currentVerifyTitle())
    }

    @Test fun advanceToCarriesTheFullTitle() {
        MediaSessionMemory.rememberYoutube(listOf(first, second), first.id, first.title, first.verifyTitle)
        MediaSessionMemory.advanceTo(second)
        assertEquals(second.title, MediaSessionMemory.currentTitle())
        assertEquals(second.titleFull, MediaSessionMemory.currentVerifyTitle())
    }

    @Test fun theRefireWouldNowConfirm() {
        MediaSessionMemory.rememberYoutube(listOf(first), first.id, first.title, first.verifyTitle)
        assertEquals(
            YoutubeVerify.Verdict.CONFIRMED_TARGET,
            YoutubeVerify.classify(
                currentTitle = full,
                priorTitle = null,
                expectedTitle = MediaSessionMemory.currentVerifyTitle(),
            ),
        )
    }

    @Test fun fmClearsTheVerifyTitle() {
        MediaSessionMemory.rememberYoutube(listOf(first), first.id, first.title, first.verifyTitle)
        MediaSessionMemory.rememberFm("FM 95.5")
        assertEquals("FM 95.5", MediaSessionMemory.currentVerifyTitle())
    }
}

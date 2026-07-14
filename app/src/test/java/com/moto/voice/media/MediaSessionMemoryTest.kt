package com.moto.voice.media

import com.moto.voice.network.WebhookResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Spec v1.3.8 B5 — in-memory session record of the current media context. Backs
 * the "อันต่อไป" / "เมื่อกี้อะไร" intercepts. Singleton so state is reset between
 * tests to avoid leaking across the JVM run.
 */
class MediaSessionMemoryTest {

    @Before fun reset() = MediaSessionMemory.resetForTest()
    @After fun tearDown() = MediaSessionMemory.resetForTest()

    private fun v(id: String, title: String = "title-$id") = WebhookResponse.Video(id, title)
    private val sample = listOf(v("a"), v("b"), v("c"), v("d"))

    // ─── rememberYoutube → currentIndex tracks the played id ─────────────

    @Test fun rememberYoutubeSetsIndexToPlayedId() {
        MediaSessionMemory.rememberYoutube(sample, playedId = "b", playedTitle = "title-b")
        // Next after 'b' is 'c'.
        val next = MediaSessionMemory.nextVideo()
        assertNotNull(next)
        assertEquals("c", next!!.id)
    }

    @Test fun rememberYoutubePlayingFirstIdGivesSecondNext() {
        MediaSessionMemory.rememberYoutube(sample, playedId = "a", playedTitle = "title-a")
        assertEquals("b", MediaSessionMemory.nextVideo()?.id)
    }

    @Test fun rememberYoutubeWithUnknownIdFallsToFirst() {
        MediaSessionMemory.rememberYoutube(sample, playedId = "not-in-list", playedTitle = "?")
        // indexOfFirst returns -1 → coerced to 0 → next is index 1 (b).
        assertEquals("b", MediaSessionMemory.nextVideo()?.id)
    }

    // ─── Exhaustion — playing the last item returns null next ───────────

    @Test fun nextAfterLastVideoIsNull() {
        MediaSessionMemory.rememberYoutube(sample, playedId = "d", playedTitle = "title-d")
        assertNull("no next after the last item", MediaSessionMemory.nextVideo())
    }

    @Test fun nextWithEmptyListIsNull() {
        MediaSessionMemory.rememberYoutube(emptyList(), playedId = "x", playedTitle = "x")
        assertNull(MediaSessionMemory.nextVideo())
    }

    @Test fun nextWithSingletonListIsNull() {
        MediaSessionMemory.rememberYoutube(listOf(v("only")), playedId = "only", playedTitle = "only")
        assertNull(MediaSessionMemory.nextVideo())
    }

    // ─── advanceTo — walking the list step by step ──────────────────────

    @Test fun advanceThenNextWalksTheList() {
        MediaSessionMemory.rememberYoutube(sample, playedId = "a", playedTitle = "title-a")
        val n1 = MediaSessionMemory.nextVideo()!!
        MediaSessionMemory.advanceTo(n1)
        assertEquals("b", n1.id)
        val n2 = MediaSessionMemory.nextVideo()!!
        MediaSessionMemory.advanceTo(n2)
        assertEquals("c", n2.id)
        val n3 = MediaSessionMemory.nextVideo()!!
        MediaSessionMemory.advanceTo(n3)
        assertEquals("d", n3.id)
        assertNull("exhausted after d", MediaSessionMemory.nextVideo())
    }

    @Test fun advanceUpdatesCurrentTitle() {
        MediaSessionMemory.rememberYoutube(sample, playedId = "a", playedTitle = "title-a")
        val next = MediaSessionMemory.nextVideo()!!
        MediaSessionMemory.advanceTo(next)
        assertEquals("title-b", MediaSessionMemory.currentTitle())
    }

    @Test fun advanceToUnknownVideoLeavesIndexAlone() {
        MediaSessionMemory.rememberYoutube(sample, playedId = "a", playedTitle = "title-a")
        MediaSessionMemory.advanceTo(v("outside-the-list", "external"))
        // Index unchanged → next stays 'b'.
        assertEquals("b", MediaSessionMemory.nextVideo()?.id)
        // But currentTitle updated to the non-blank passed value.
        assertEquals("external", MediaSessionMemory.currentTitle())
    }

    // ─── rememberFm — clears videos, keeps title queryable ──────────────

    @Test fun rememberFmClearsVideosAndSetsStationName() {
        MediaSessionMemory.rememberYoutube(sample, playedId = "a", playedTitle = "title-a")
        MediaSessionMemory.rememberFm("ครอบครัวข่าว FM 106")
        assertNull("videos cleared when switching to FM", MediaSessionMemory.nextVideo())
        assertEquals("ครอบครัวข่าว FM 106", MediaSessionMemory.currentTitle())
    }

    // ─── hasContext / currentTitle when nothing has played ──────────────

    @Test fun freshMemoryHasNoContext() {
        assertFalse(MediaSessionMemory.hasContext())
        assertEquals("", MediaSessionMemory.currentTitle())
    }

    @Test fun rememberFmMarksContext() {
        MediaSessionMemory.rememberFm("FM 106")
        assertTrue(MediaSessionMemory.hasContext())
    }

    @Test fun rememberYoutubeMarksContext() {
        MediaSessionMemory.rememberYoutube(sample, playedId = "a", playedTitle = "title-a")
        assertTrue(MediaSessionMemory.hasContext())
    }

    // ─── v1.3.20 sprint — lastOpenedApp + lastVideoId for MediaOrchestrator ───

    @Test fun freshMemoryHasNoLastOpenedApp() {
        assertNull(MediaSessionMemory.lastOpenedApp())
        assertNull(MediaSessionMemory.lastVideoId())
    }

    @Test fun rememberYoutubeSetsLastOpenedAppAndVideoId() {
        MediaSessionMemory.rememberYoutube(sample, playedId = "VID123", playedTitle = "title")
        assertEquals(MediaSessions.YOUTUBE_PKG, MediaSessionMemory.lastOpenedApp())
        assertEquals("VID123", MediaSessionMemory.lastVideoId())
    }

    @Test fun rememberFmClearsLastOpenedApp() {
        MediaSessionMemory.rememberYoutube(sample, playedId = "VID123", playedTitle = "title")
        MediaSessionMemory.rememberFm("FM 88")
        // FM is our own service, not a deep-linkable app — refire target must be null.
        assertNull(MediaSessionMemory.lastOpenedApp())
        assertNull(MediaSessionMemory.lastVideoId())
    }

    @Test fun rememberOpenedAppRecordsArbitraryPackage() {
        MediaSessionMemory.rememberOpenedApp(MediaOrchestrator.SPOTIFY_PKG, videoId = null)
        assertEquals(MediaOrchestrator.SPOTIFY_PKG, MediaSessionMemory.lastOpenedApp())
        assertNull(MediaSessionMemory.lastVideoId())
    }

    @Test fun rememberOpenedAppOverwritesPreviousYoutube() {
        MediaSessionMemory.rememberYoutube(sample, playedId = "VID123", playedTitle = "title")
        MediaSessionMemory.rememberOpenedApp(MediaOrchestrator.SPOTIFY_PKG, videoId = null)
        assertEquals(MediaOrchestrator.SPOTIFY_PKG, MediaSessionMemory.lastOpenedApp())
        // Rider switched apps — no YouTube videoId to refire anymore.
        assertNull(MediaSessionMemory.lastVideoId())
    }
}

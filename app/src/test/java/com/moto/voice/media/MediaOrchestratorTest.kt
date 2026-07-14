package com.moto.voice.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * v1.3.20 sprint — pure-JVM tests for the target-resolution logic that anchors
 * iron rule #2 ("every op has an explicit targetPkg"). The [Handler] / nudge /
 * deep-link paths inside [MediaOrchestrator] require Android instrumentation
 * and live in the acceptance suite (see ACCEPTANCE.md).
 *
 * These tests lock:
 *   * an explicit rider hint ("youtube" / "spotify" / Thai variants) wins over
 *     any [MediaSessionMemory.lastOpenedApp];
 *   * with no hint, resolution falls back to whatever we last deep-linked into;
 *   * with neither, we return null → caller (playContinue) decides what to do
 *     (default YouTube, refire deep link if we have a lastVideoId, etc.).
 */
class MediaOrchestratorTest {

    @Before fun reset() {
        MediaSessionMemory.resetForTest()
        MediaOrchestrator.resetForTest()
    }

    @After fun tearDown() {
        MediaSessionMemory.resetForTest()
        MediaOrchestrator.resetForTest()
    }

    // ─── Explicit rider hint wins ────────────────────────────────────────────

    @Test fun ytHintReturnsYoutubePkg() {
        assertEquals(MediaSessions.YOUTUBE_PKG, MediaOrchestrator.resolveTarget("youtube"))
    }

    @Test fun spotifyHintReturnsSpotifyPkg() {
        assertEquals(MediaOrchestrator.SPOTIFY_PKG, MediaOrchestrator.resolveTarget("spotify"))
    }

    @Test fun thaiYoutubeHintReturnsYoutubePkg() {
        assertEquals(MediaSessions.YOUTUBE_PKG, MediaOrchestrator.resolveTarget("ยูทูป"))
        assertEquals(MediaSessions.YOUTUBE_PKG, MediaOrchestrator.resolveTarget("ยูทูบ"))
    }

    @Test fun thaiSpotifyHintReturnsSpotifyPkg() {
        assertEquals(MediaOrchestrator.SPOTIFY_PKG, MediaOrchestrator.resolveTarget("สปอติฟาย"))
    }

    @Test fun hintCaseIsNormalized() {
        assertEquals(MediaSessions.YOUTUBE_PKG, MediaOrchestrator.resolveTarget("YouTube"))
        assertEquals(MediaOrchestrator.SPOTIFY_PKG, MediaOrchestrator.resolveTarget("SPOTIFY"))
    }

    // ─── Fallback to MediaSessionMemory.lastOpenedApp ────────────────────────

    @Test fun noHintFallsToLastOpenedYoutube() {
        MediaSessionMemory.rememberOpenedApp(MediaSessions.YOUTUBE_PKG, "VID123")
        assertEquals(MediaSessions.YOUTUBE_PKG, MediaOrchestrator.resolveTarget(null))
    }

    @Test fun noHintFallsToLastOpenedSpotify() {
        MediaSessionMemory.rememberOpenedApp(MediaOrchestrator.SPOTIFY_PKG, videoId = null)
        assertEquals(MediaOrchestrator.SPOTIFY_PKG, MediaOrchestrator.resolveTarget(null))
    }

    @Test fun noHintNoMemoryReturnsNull() {
        // Fresh install / process restart: nothing was ever opened.
        assertNull(MediaOrchestrator.resolveTarget(null))
    }

    // ─── Explicit hint overrides memory ──────────────────────────────────────

    @Test fun explicitHintOverridesLastOpened() {
        MediaSessionMemory.rememberOpenedApp(MediaSessions.YOUTUBE_PKG, "VID123")
        // Rider explicitly says "spotify" — even though YouTube was last, honour the ask.
        assertEquals(MediaOrchestrator.SPOTIFY_PKG, MediaOrchestrator.resolveTarget("spotify"))
    }

    @Test fun unknownHintFallsThroughToNull() {
        // Rider mumbles a name we don't recognise — no memory, no fallback.
        assertNull(MediaOrchestrator.resolveTarget("netflix"))
    }

    @Test fun unknownHintDoesNotFallBackToMemory() {
        // If the rider named an app we don't handle, we DON'T silently substitute
        // the last-opened app — that would violate rule #2 (target must be explicit).
        MediaSessionMemory.rememberOpenedApp(MediaSessions.YOUTUBE_PKG, "VID123")
        assertNull(MediaOrchestrator.resolveTarget("netflix"))
    }

    // ─── Rule #1 constants — spot check the SPOTIFY_PKG constant ────────────

    @Test fun spotifyPkgIsTheAndroidSpotifyPackage() {
        // Sanity check — SPOTIFY_PKG is referenced by resolveTarget and
        // MediaSessionMemory.rememberOpenedApp callers; must match the actual
        // Play Store package so controllerFor() finds the session.
        assertEquals("com.spotify.music", MediaOrchestrator.SPOTIFY_PKG)
    }
}

package com.moto.voice.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.4 — two pure decisions inside the nudge that the code-review audit showed were wrong.
 *
 * 1. `sessionLost` ("it opened and then stopped") must only be declared when the poll saw a
 *    session that was demonstrably the NEW video. On a warm switch YouTube already has a
 *    session for the OLD video at tick 1, so "any controller" is not evidence.
 * 2. Our own FmPlayerService registers a media3 session under the app's package; the
 *    per-tick "re-pause foreign players" loop must not count it as foreign, or the radio
 *    dies every 500 ms while a YouTube nudge is still polling.
 */
class NudgeLifecycleContractTest {

    private val prior = "Top Hits 2026 Playlist ~ Trending Music 2026"

    // ─── sessionLost evidence ────────────────────────────────────────────────

    @Test fun coldLaunchAnySessionCountsAsNew() {
        for (v in YoutubeVerify.Verdict.values()) {
            assertTrue("cold $v", MediaOrchestrator.countsAsNewSession(v, priorTitle = null))
        }
    }

    @Test fun warmSwitchPriorSessionIsNotEvidence() {
        assertFalse(MediaOrchestrator.countsAsNewSession(YoutubeVerify.Verdict.STILL_PRIOR, prior))
        // Blank title on a warm target: could still be the old session — not evidence either.
        assertFalse(MediaOrchestrator.countsAsNewSession(YoutubeVerify.Verdict.UNKNOWN, prior))
    }

    @Test fun warmSwitchNewTitleIsEvidence() {
        assertTrue(MediaOrchestrator.countsAsNewSession(YoutubeVerify.Verdict.CONFIRMED_TARGET, prior))
        assertTrue(MediaOrchestrator.countsAsNewSession(YoutubeVerify.Verdict.SWITCHED, prior))
    }

    // ─── who is "foreign" ────────────────────────────────────────────────────

    @Test fun ourOwnRadioIsNotForeign() {
        assertFalse(MediaOrchestrator.isForeignPackage("com.moto.voice", selfPkg = "com.moto.voice"))
    }

    @Test fun theTargetIsNotForeign() {
        assertFalse(MediaOrchestrator.isForeignPackage(MediaSessions.YOUTUBE_PKG, selfPkg = "com.moto.voice"))
    }

    @Test fun spotifyIsForeign() {
        assertTrue(MediaOrchestrator.isForeignPackage(MediaOrchestrator.SPOTIFY_PKG, selfPkg = "com.moto.voice"))
    }

    @Test fun nullPackageIsNeverPaused() {
        assertFalse(MediaOrchestrator.isForeignPackage(null, selfPkg = "com.moto.voice"))
    }
}

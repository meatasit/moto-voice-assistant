package com.moto.voice.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v1.4.5 — pins the scope of the headless start added in [YoutubeMediaBrowser].
 *
 * The new path exists for exactly one launch shape: COLD YouTube behind a LOCKED screen,
 * which field log 1789561893967 shows failing and then succeeding 40 seconds later purely
 * because the first attempt left YouTube warm. Everything else in the field logs already
 * works, and "already works" is what these tests defend — a regression here would trade a
 * proven-good deep link for a search that cannot name a video id.
 *
 * The connect / bind / playFromSearch half needs Android instrumentation and belongs to the
 * Acceptance Suite (see ACCEPTANCE.md), same as the nudge polling and deep-link timing.
 */
class MediaBrowserGateTest {

    @Before fun reset() {
        MediaSessionMemory.resetForTest()
        MediaOrchestrator.resetForTest()
    }

    @After fun tearDown() {
        MediaSessionMemory.resetForTest()
        MediaOrchestrator.resetForTest()
    }

    // ─── The one case the deep link cannot do ────────────────────────────────

    @Test fun lockedAndColdTakesTheHeadlessPath() {
        assertTrue(
            MediaOrchestrator.shouldTryMediaBrowser(
                enabled = true, locked = true, priorTitle = null,
            )
        )
    }

    // ─── Cases that already work must keep the deep link ─────────────────────

    @Test fun unlockedNeverTakesIt() {
        // Field log 1786763666528: unlocked launches land. The player gets a visible window,
        // YouTube plays, and a deep link can name the exact video id.
        assertFalse(
            MediaOrchestrator.shouldTryMediaBrowser(
                enabled = true, locked = false, priorTitle = null,
            )
        )
    }

    @Test fun warmSwitchNeverTakesIt() {
        // A non-null priorTitle means YouTube's playback service is already alive, so the new
        // video reaches it with no UI needed. Warm switches land locked or not.
        assertFalse(
            MediaOrchestrator.shouldTryMediaBrowser(
                enabled = true, locked = true, priorTitle = "crazy chill song playlist",
            )
        )
    }

    @Test fun unknownLockStateNeverTakesIt() {
        // KeyguardManager unavailable — isScreenLocked() returned null. Don't guess: the deep
        // link is the behaviour we have evidence for.
        assertFalse(
            MediaOrchestrator.shouldTryMediaBrowser(
                enabled = true, locked = null, priorTitle = null,
            )
        )
    }

    @Test fun riderCanSwitchItOff() {
        // AppSettings.youtubeMediaBrowser off ⇒ exactly v1.4.4 behaviour, no new build needed.
        assertFalse(
            MediaOrchestrator.shouldTryMediaBrowser(
                enabled = false, locked = true, priorTitle = null,
            )
        )
    }

    // ─── What the headless start searches for ────────────────────────────────

    @Test fun fullTitleBeatsTheRidersPhrasing() {
        // "เพลงสากลชิวๆ" would return anything at all; the title is what identifies the video.
        assertEquals(
            "crazy chill song playlist - lauv,lany,keshi,austin.ect",
            MediaOrchestrator.browserSearchTerm(
                expectedTitle = "crazy chill song playlist - lauv,lany,keshi,austin.ect",
                query = "chill english songs playlist",
            )
        )
    }

    @Test fun queryIsTheFallbackWhenNoTitleCameThrough() {
        assertEquals(
            "chill english songs playlist",
            MediaOrchestrator.browserSearchTerm(
                expectedTitle = null, query = "chill english songs playlist",
            )
        )
    }

    @Test fun blankTitleFallsThroughRatherThanSearchingNothing() {
        assertEquals(
            "chill english songs playlist",
            MediaOrchestrator.browserSearchTerm(
                expectedTitle = "   ", query = "chill english songs playlist",
            )
        )
    }

    @Test fun nothingToSearchIsNull() {
        // Caller reads null as "no headless start possible" and fires the deep link, which
        // still has the video id even when no title or query survived.
        assertNull(MediaOrchestrator.browserSearchTerm(expectedTitle = null, query = null))
        assertNull(MediaOrchestrator.browserSearchTerm(expectedTitle = "", query = "  "))
    }
}

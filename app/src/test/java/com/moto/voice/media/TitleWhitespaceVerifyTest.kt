package com.moto.voice.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.3 — the exact titles from field log 1789524511710, entry 1789522604183.
 *
 * YouTube's MediaSession reported the live-stream title with a NO-BREAK SPACE (U+00A0)
 * between "16" and "กันยายน"; the YouTube Data API (via n8n) sent the same title with an
 * ordinary space. Both looked identical in the log. `titlesMatch` compared them character
 * by character, said no, and the app declared `launchBlocked(wrongVideo)` on a stream that
 * was playing — the rider then told the assistant to "note that it said it hadn't switched
 * but the clip had actually opened".
 */
class TitleWhitespaceVerifyTest {

    private val fromWorkflow = "Live \"กรรมกรข่าว คุยนอกจอ\" 16 กันยายน 2569"
    private val fromSession = "Live \"กรรมกรข่าว คุยนอกจอ\" 16 กันยายน 2569"

    @Test fun theTwoTitlesReallyDiffer() {
        // Documents the trap: same on screen, different in memory.
        assertFalse(fromWorkflow == fromSession)
    }

    @Test fun noBreakSpaceMatchesPlainSpace() {
        assertTrue(YoutubeVerify.titlesMatch(fromWorkflow, fromSession))
        assertEquals(
            YoutubeVerify.Verdict.CONFIRMED_TARGET,
            YoutubeVerify.classify(
                currentTitle = fromSession,
                priorTitle = "Top Hits 2026 Playlist ~ Trending Music 2026 🎵 Spotify Mix ~ Best TikTok Songs (Hits Collection)",
                expectedTitle = fromWorkflow,
            ),
        )
    }

    @Test fun whitespaceRunsAndZeroWidthCharactersAreIgnored() {
        assertEquals("a b c", YoutubeVerify.normalize(" A  b\t​c "))
        assertTrue(YoutubeVerify.titlesMatch("ข่าวเช้า  วันนี้", "ข่าวเช้า วันนี้"))
    }

    @Test fun whitespaceNormalizationDoesNotLoosenTheEpisodeRule() {
        // The 70% prefix rule from field log 1784078976959 must survive untouched.
        assertFalse(
            YoutubeVerify.titlesMatch(
                "ถ่ายทอดสด เรื่องเล่าเช้านี้ วันที่ 9 กันยายน 2569",
                "ถ่ายทอดสด เรื่องเล่าเช้านี้ วันที่ 15 กันยายน 2569",
            ),
        )
    }
}

package com.moto.voice.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.20 sprint rule #2 — the "เล่นต่อ" intercept must be caught locally BEFORE
 * the webhook so [com.moto.voice.media.MediaOrchestrator] picks the target from
 * [com.moto.voice.media.MediaSessionMemory.lastOpenedApp] rather than the LLM
 * guessing. If the rider names the app in the sentence ("youtube" / "spotify"),
 * the hint wins.
 *
 * Also asserts that the play-continue regex does NOT cannibalise adjacent
 * intercepts: bare "ต่อไป" / "อันต่อไป" are still NextVideo (spec v1.3.8 B5),
 * "หยุด" is still Stop, and a full webhook-bound "เปิด youtube กรรมกรข่าว" still
 * falls through to Intercept.None.
 */
class LocalInterceptPlayContinueTest {

    // ─── Base positive patterns ──────────────────────────────────────────────

    @Test fun bareLenTaawIsPlayContinueWithNullHint() {
        val i = LocalIntercept.match("เล่นต่อ") as LocalIntercept.Intercept.PlayContinue
        assertEquals(null, i.appHint)
    }

    @Test fun kotLenTaawIsPlayContinueWithNullHint() {
        val i = LocalIntercept.match("กดเล่นต่อ") as LocalIntercept.Intercept.PlayContinue
        assertEquals(null, i.appHint)
    }

    @Test fun peurdTaawIsPlayContinueWithNullHint() {
        val i = LocalIntercept.match("เปิดต่อ") as LocalIntercept.Intercept.PlayContinue
        assertEquals(null, i.appHint)
    }

    // ─── App-hint capture — hint in the middle wins ─────────────────────────

    @Test fun youtubeMiddleHintCaptured() {
        val i = LocalIntercept.match("เล่น youtube ต่อ") as LocalIntercept.Intercept.PlayContinue
        assertEquals("youtube", i.appHint)
    }

    @Test fun spotifyMiddleHintCaptured() {
        val i = LocalIntercept.match("เล่น spotify ต่อ") as LocalIntercept.Intercept.PlayContinue
        assertEquals("spotify", i.appHint)
    }

    @Test fun thaiYoutubeSpellingCaptured() {
        val i = LocalIntercept.match("เล่นยูทูปต่อ") as LocalIntercept.Intercept.PlayContinue
        assertEquals("youtube", i.appHint)
    }

    @Test fun thaiYoutubeSpellingAlt() {
        val i = LocalIntercept.match("เล่นยูทูบต่อ") as LocalIntercept.Intercept.PlayContinue
        assertEquals("youtube", i.appHint)
    }

    @Test fun thaiSpotifySpellingCaptured() {
        val i = LocalIntercept.match("เล่นสปอติฟายต่อ") as LocalIntercept.Intercept.PlayContinue
        assertEquals("spotify", i.appHint)
    }

    @Test fun peurdWithYoutubeHint() {
        val i = LocalIntercept.match("เปิด youtube ต่อ") as LocalIntercept.Intercept.PlayContinue
        assertEquals("youtube", i.appHint)
    }

    // ─── Non-match guards — sibling intercepts must still win ──────────────

    @Test fun bareTaawPaiIsStillNextVideo() =
        assertSame(LocalIntercept.Intercept.NextVideo, LocalIntercept.match("ต่อไป"))

    @Test fun anTaawPaiIsStillNextVideo() =
        assertSame(LocalIntercept.Intercept.NextVideo, LocalIntercept.match("อันต่อไป"))

    @Test fun thatPaiIsStillNextVideo() =
        assertSame(LocalIntercept.Intercept.NextVideo, LocalIntercept.match("ถัดไป"))

    @Test fun yudIsStillStop() =
        assertSame(LocalIntercept.Intercept.Stop, LocalIntercept.match("หยุด"))

    @Test fun peurdYoutubeWithQueryStillGoesToWebhook() {
        // A full "open youtube [query]" without ต่อ must NOT be swallowed as
        // PlayContinue — the webhook picks the video.
        assertSame(LocalIntercept.Intercept.None, LocalIntercept.match("เปิด youtube กรรมกรข่าว"))
    }

    @Test fun callDoesNotMatchPlayContinue() {
        // "โทรหาแม่" — must reach the webhook. ต่อ is nowhere in this sentence.
        assertSame(LocalIntercept.Intercept.None, LocalIntercept.match("โทรหาแม่"))
    }

    // ─── Regression guard — hint must be extracted BEFORE the intercept fires ──

    @Test fun playContinueIsIndeedPlayContinueClass() {
        assertTrue(LocalIntercept.match("เล่นต่อ") is LocalIntercept.Intercept.PlayContinue)
        assertTrue(LocalIntercept.match("เล่น youtube ต่อ") is LocalIntercept.Intercept.PlayContinue)
    }
}

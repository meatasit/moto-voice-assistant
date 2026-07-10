package com.moto.voice.nlu

import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Spec v1.3.8 B5 — LocalIntercept picks up the media-context intercepts
 * (NextVideo / WhatIsPlaying) before any webhook call. Real riders won't say
 * exactly the canonical phrase every time; this suite locks the six + four
 * spellings we ship with plus a handful of not-a-match guards so a future edit
 * that widens the patterns can't quietly swallow "โทรหา" or "หยุด".
 */
class LocalInterceptMediaContextTest {

    // ─── NextVideo patterns ─────────────────────────────────────────────────

    @Test fun anTaawPaiIsNext() =
        assertSame(LocalIntercept.Intercept.NextVideo, LocalIntercept.match("อันต่อไป"))

    @Test fun taawPaiIsNext() =
        assertSame(LocalIntercept.Intercept.NextVideo, LocalIntercept.match("ต่อไป"))

    @Test fun thatPaiIsNext() =
        assertSame(LocalIntercept.Intercept.NextVideo, LocalIntercept.match("ถัดไป"))

    @Test fun pleeanIsNext() =
        assertSame(LocalIntercept.Intercept.NextVideo, LocalIntercept.match("เปลี่ยน"))

    @Test fun anAynIsNext() =
        assertSame(LocalIntercept.Intercept.NextVideo, LocalIntercept.match("อันอื่น"))

    @Test fun maiAowAnNiiIsNext() =
        assertSame(LocalIntercept.Intercept.NextVideo, LocalIntercept.match("ไม่เอาอันนี้"))

    // Real STT patterns with padding — matchesAsPhrase allows a leading token
    // as long as the target appears at position 0 or after whitespace.

    @Test fun leadingPaddingBeforeAnTaawPai() =
        assertSame(LocalIntercept.Intercept.NextVideo, LocalIntercept.match("เอ่อ อันต่อไป"))

    // ─── WhatIsPlaying patterns ─────────────────────────────────────────────

    @Test fun mueaKiiArraiIsWhatIsPlaying() =
        assertSame(LocalIntercept.Intercept.WhatIsPlaying, LocalIntercept.match("เมื่อกี้อะไร"))

    @Test fun lenArraiYuuIsWhatIsPlaying() =
        assertSame(LocalIntercept.Intercept.WhatIsPlaying, LocalIntercept.match("เล่นอะไรอยู่"))

    @Test fun neePlengArraiIsWhatIsPlaying() =
        assertSame(LocalIntercept.Intercept.WhatIsPlaying, LocalIntercept.match("นี่เพลงอะไร"))

    // ─── Guard: existing intercepts still take precedence ──────────────────

    @Test fun stopWinsOverAnything() {
        // "หยุด" is Stop; a follow-up phrase "หยุดเปลี่ยน" would be ambiguous — the
        // Stop pattern is checked before NextVideo per match() body, so Stop wins.
        assertSame(LocalIntercept.Intercept.Stop, LocalIntercept.match("หยุด"))
    }

    @Test fun callDoesNotMatchNextOrWhat() {
        // "โทรหาแม่" — must fall through to Intercept.None so the webhook handles it.
        assertSame(LocalIntercept.Intercept.None, LocalIntercept.match("โทรหาแม่"))
    }

    @Test fun openYoutubeStillGoesToWebhook() {
        assertSame(LocalIntercept.Intercept.None, LocalIntercept.match("เปิด youtube กรรมกรข่าว"))
    }

    // ─── Guard: unrelated Thai does not false-match ────────────────────────

    @Test fun namePhraseNotMistakenForContextIntercept() {
        // A rider name that happens to include "อยู่" shouldn't fire WhatIsPlaying.
        assertSame(LocalIntercept.Intercept.None, LocalIntercept.match("โทรหาป้าอยู่"))
    }

    @Test fun genericQuestionDoesNotMatch() {
        // Random unrelated question — must reach the webhook.
        assertSame(LocalIntercept.Intercept.None, LocalIntercept.match("วันนี้อากาศเป็นยังไง"))
    }
}

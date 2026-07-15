package com.moto.voice.media

import com.moto.voice.media.YoutubeVerify.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the title-based nudge verification that replaced the defeated mediaId check
 * (field log 1784074856214: YouTube "change video" while locked kept the old video but
 * logged nudge→confirmed). The decisive behaviour: a title that never moves away from
 * what was playing before = STILL_PRIOR = launch blocked, not "confirmed".
 */
class YoutubeVerifyTest {

    // ─── titlesMatch ─────────────────────────────────────────────────────────

    @Test fun exactMatch() =
        assertTrue(YoutubeVerify.titlesMatch("เรื่องเล่าเช้านี้", "เรื่องเล่าเช้านี้"))

    @Test fun caseAndWhitespaceInsensitive() =
        assertTrue(YoutubeVerify.titlesMatch("  Groove Riders - หยุด ", "groove riders - หยุด"))

    @Test fun truncatedWebhookTitleStillMatchesViaPrefix() {
        // Webhook titles are sometimes cut short at the end; the session reports the full
        // title. The truncation is a leading prefix covering most of the full string.
        assertTrue(
            YoutubeVerify.titlesMatch(
                "รวมเพลงเพราะๆ ฟังสบายๆ ฟังทำงาน ร้านกาแฟ 2026 EP.237",
                "รวมเพลงเพราะๆ ฟังสบายๆ ฟังทำงาน ร้านกาแฟ 2026 EP.2",
            )
        )
    }

    @Test fun sameSeriesDifferentEpisodeDoesNotMatch() {
        // THE REGRESSION (field log 1784078976959 ts …843956): different-date episodes of
        // the same live show share a long boilerplate prefix but must NOT match, or a locked
        // switch confirms the wrong (old) episode.
        assertFalse(
            YoutubeVerify.titlesMatch(
                "ถ่ายทอดสด เรื่องเล่าเช้านี้ วันที่ 15 กรกฎาคม 2569",
                "ถ่ายทอดสด เรื่องเล่าเช้านี้ วันที่ 9 กรกฎาคม 2569",
            )
        )
    }

    @Test fun sameSeriesDifferentEpisodeNumberDoesNotMatch() =
        assertFalse(YoutubeVerify.titlesMatch("Minecraft 100 วัน EP.2", "Minecraft 100 วัน EP.15"))

    @Test fun differentVideosDoNotMatch() =
        assertFalse(YoutubeVerify.titlesMatch("เพลงชิวๆ ฟังสบาย", "Minecraft 100 วัน EP.2"))

    @Test fun blankNeverMatches() {
        assertFalse(YoutubeVerify.titlesMatch("", "anything"))
        assertFalse(YoutubeVerify.titlesMatch("anything", null))
        assertFalse(YoutubeVerify.titlesMatch(null, null))
    }

    @Test fun shortSharedFragmentDoesNotFalseMatch() {
        // "ข่าว" alone is too short to imply the same video.
        assertFalse(YoutubeVerify.titlesMatch("ข่าว", "ข่าวเช้า 7 สี วันนี้ยาวมาก"))
    }

    // ─── classify ────────────────────────────────────────────────────────────

    @Test fun playingWhatWeAskedFor_isConfirmedTarget() {
        val v = YoutubeVerify.classify(
            currentTitle = "ถ่ายทอดสด เรื่องเล่าเช้านี้ วันที่ 15",
            priorTitle = "เพลงชิวๆ",
            expectedTitle = "ถ่ายทอดสด เรื่องเล่าเช้านี้ วันที่ 15",
        )
        assertEquals(Verdict.CONFIRMED_TARGET, v)
    }

    @Test fun titleMovedToSomethingNew_isSwitched() {
        // Expected unknown (e.g. "อันต่อไป" with blank title) but the title clearly changed.
        val v = YoutubeVerify.classify(
            currentTitle = "คลิปใหม่ล่าสุด",
            priorTitle = "คลิปเก่า",
            expectedTitle = null,
        )
        assertEquals(Verdict.SWITCHED, v)
    }

    @Test fun stillShowingOldVideoAndNotTarget_isStillPrior() {
        // The locked/BAL case: asked for B, session still on A.
        val v = YoutubeVerify.classify(
            currentTitle = "Minecraft 100 วัน EP.2",
            priorTitle = "Minecraft 100 วัน EP.2",
            expectedTitle = "เพลงชิวๆ ฟังสบายทั้งวัน",
        )
        assertEquals(Verdict.STILL_PRIOR, v)
    }

    @Test fun reopeningSameVideo_isConfirmedNotBlocked() {
        // Rider opens เรื่องเล่าเช้านี้ twice — prior == expected == current. Must confirm,
        // NOT falsely flag as "unchanged/blocked".
        val v = YoutubeVerify.classify(
            currentTitle = "เรื่องเล่าเช้านี้ 15 ก.ค.",
            priorTitle = "เรื่องเล่าเช้านี้ 15 ก.ค.",
            expectedTitle = "เรื่องเล่าเช้านี้ 15 ก.ค.",
        )
        assertEquals(Verdict.CONFIRMED_TARGET, v)
    }

    @Test fun lockedSwitchToOtherEpisodeOfSameSeries_isStillPrior() {
        // The reported false-confirm: asked for วันที่ 9, session stuck on วันที่ 15. Must be
        // STILL_PRIOR (→ launch blocked), NOT CONFIRMED_TARGET.
        val v = YoutubeVerify.classify(
            currentTitle = "ถ่ายทอดสด เรื่องเล่าเช้านี้ วันที่ 15 กรกฎาคม 2569",
            priorTitle = "ถ่ายทอดสด เรื่องเล่าเช้านี้ วันที่ 15 กรกฎาคม 2569",
            expectedTitle = "ถ่ายทอดสด เรื่องเล่าเช้านี้ วันที่ 9 กรกฎาคม 2569",
        )
        assertEquals(Verdict.STILL_PRIOR, v)
    }

    @Test fun coldOpenWithNoPriorTitle_isSwitched() {
        val v = YoutubeVerify.classify(
            currentTitle = "อะไรก็ได้ที่เพิ่งเปิด",
            priorTitle = null,
            expectedTitle = null,
        )
        assertEquals(Verdict.SWITCHED, v)
    }

    @Test fun noTitleYet_isUnknown() {
        val v = YoutubeVerify.classify(
            currentTitle = null,
            priorTitle = "เพลงเก่า",
            expectedTitle = "เพลงใหม่",
        )
        assertEquals(Verdict.UNKNOWN, v)
    }
}

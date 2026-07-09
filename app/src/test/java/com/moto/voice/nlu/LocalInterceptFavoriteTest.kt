package com.moto.voice.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec §2.1 (voice-call favorites) regex matrix: every combination of
 * verb (โทรหา / โทร), favorite word (รายการโปรด / เบอร์โปรด / favorite / เฟเวอริท),
 * and number (Thai หนึ่ง-ห้า / Arabic 1-5) must resolve to the correct zero-based slot.
 */
class LocalInterceptFavoriteTest {

    // ─── Verb variants ───────────────────────────────────────────────────────

    @Test fun thoRhaVerbHandled() = assertEquals(0, LocalIntercept.favoriteSlotOrNull("โทรหารายการโปรดหนึ่ง"))
    @Test fun thoAloneVerbHandled() = assertEquals(0, LocalIntercept.favoriteSlotOrNull("โทรรายการโปรดหนึ่ง"))

    // ─── Favorite word variants ──────────────────────────────────────────────

    @Test fun rakanproChomWord() = assertEquals(0, LocalIntercept.favoriteSlotOrNull("โทรหารายการโปรดหนึ่ง"))
    @Test fun berProdWord() = assertEquals(0, LocalIntercept.favoriteSlotOrNull("โทรหาเบอร์โปรดหนึ่ง"))
    @Test fun englishFavoriteWord() = assertEquals(0, LocalIntercept.favoriteSlotOrNull("โทรหาfavorite หนึ่ง"))
    @Test fun thaiFewaritWord() = assertEquals(0, LocalIntercept.favoriteSlotOrNull("โทรหาเฟเวอริทหนึ่ง"))

    // ─── Number 1..5 in Thai ─────────────────────────────────────────────────

    @Test fun slotOneThai() = assertEquals(0, LocalIntercept.favoriteSlotOrNull("โทรหารายการโปรดหนึ่ง"))
    @Test fun slotTwoThai() = assertEquals(1, LocalIntercept.favoriteSlotOrNull("โทรหารายการโปรดสอง"))
    @Test fun slotThreeThai() = assertEquals(2, LocalIntercept.favoriteSlotOrNull("โทรหารายการโปรดสาม"))
    @Test fun slotFourThai() = assertEquals(3, LocalIntercept.favoriteSlotOrNull("โทรหารายการโปรดสี่"))
    @Test fun slotFiveThai() = assertEquals(4, LocalIntercept.favoriteSlotOrNull("โทรหารายการโปรดห้า"))

    // ─── Number 1..5 in Arabic ───────────────────────────────────────────────

    @Test fun slotOneArabic() = assertEquals(0, LocalIntercept.favoriteSlotOrNull("โทรหารายการโปรด 1"))
    @Test fun slotTwoArabic() = assertEquals(1, LocalIntercept.favoriteSlotOrNull("โทรหาfavorite 2"))
    @Test fun slotFiveArabic() = assertEquals(4, LocalIntercept.favoriteSlotOrNull("โทรหาเฟเวอริท 5"))

    // ─── Non-matches ─────────────────────────────────────────────────────────

    @Test fun regularCallNameIsNotFavorite() {
        assertNull(LocalIntercept.favoriteSlotOrNull("โทรหาแม่"))
    }

    @Test fun unrelatedTextIsNotFavorite() {
        assertNull(LocalIntercept.favoriteSlotOrNull("เปิดวิทยุ"))
    }

    @Test fun favoriteWithoutNumberIsNotMatched() {
        assertNull(LocalIntercept.favoriteSlotOrNull("โทรหารายการโปรด"))
    }

    @Test fun slotSixIsNotMatched() {
        // Six isn't in the allowed set — regex must not match.
        assertNull(LocalIntercept.favoriteSlotOrNull("โทรหารายการโปรดหก"))
    }

    // ─── Full pipeline integration: LocalIntercept.match ─────────────────────

    @Test fun matchReturnsCallFavorite() {
        val r = LocalIntercept.match("โทรหารายการโปรดสาม")
        assertTrue("expected CallFavorite, got $r", r is LocalIntercept.Intercept.CallFavorite)
        assertEquals(2, (r as LocalIntercept.Intercept.CallFavorite).zeroBasedSlot)
    }

    @Test fun matchReturnsCallFavoriteArabic() {
        val r = LocalIntercept.match("โทรหาfavorite 4")
        assertTrue(r is LocalIntercept.Intercept.CallFavorite)
        assertEquals(3, (r as LocalIntercept.Intercept.CallFavorite).zeroBasedSlot)
    }

    // ─── Field-log v1.3.5 regressions (log 1783581952116) ────────────────────
    // These two sentences BOTH fell through to the webhook pre-v1.3.6: "ที่"
    // separator + optional "ออก" verb prefix weren't in the pattern. Locked in
    // as regression tests so future regex edits can't re-break them.

    @Test fun realFieldSentenceWithThi() {
        assertEquals(0, LocalIntercept.favoriteSlotOrNull("โทรหารายการโปรดที่ 1"))
    }

    @Test fun realFieldSentenceWithOokAndThi() {
        assertEquals(0, LocalIntercept.favoriteSlotOrNull("โทรออกหารายการโปรดที่ 1"))
    }

    // ─── Verb prefix variants added in v1.3.6 ─────────────────────────────

    @Test fun ookVerbAlone() = assertEquals(0, LocalIntercept.favoriteSlotOrNull("โทรออกรายการโปรดหนึ่ง"))
    @Test fun paiVerbAlone() = assertEquals(0, LocalIntercept.favoriteSlotOrNull("โทรไปรายการโปรดหนึ่ง"))
    @Test fun paiRhaVerb() = assertEquals(0, LocalIntercept.favoriteSlotOrNull("โทรไปหารายการโปรดหนึ่ง"))
    @Test fun ookRhaVerb() = assertEquals(0, LocalIntercept.favoriteSlotOrNull("โทรออกหารายการโปรดหนึ่ง"))

    // ─── Separator variants added in v1.3.6 ───────────────────────────────

    @Test fun thiSeparator() = assertEquals(1, LocalIntercept.favoriteSlotOrNull("โทรหารายการโปรดที่ 2"))
    @Test fun mailekSeparator() = assertEquals(2, LocalIntercept.favoriteSlotOrNull("โทรหารายการโปรดหมายเลข 3"))
    @Test fun berSeparator() = assertEquals(3, LocalIntercept.favoriteSlotOrNull("โทรหาfavorite เบอร์ 4"))
    @Test fun andaapSeparator() = assertEquals(4, LocalIntercept.favoriteSlotOrNull("โทรหาเฟเวอริท อันดับ 5"))
    @Test fun lamdapSeparator() = assertEquals(0, LocalIntercept.favoriteSlotOrNull("โทรหารายการโปรด ลำดับ 1"))

    // ─── Full pipeline via LocalIntercept.match for the field sentences ───

    @Test fun matchReturnsCallFavoriteForFieldSentence() {
        val r = LocalIntercept.match("โทรหารายการโปรดที่ 1")
        assertTrue("expected CallFavorite, got $r", r is LocalIntercept.Intercept.CallFavorite)
        assertEquals(0, (r as LocalIntercept.Intercept.CallFavorite).zeroBasedSlot)
    }

    @Test fun matchReturnsCallFavoriteForOokFieldSentence() {
        val r = LocalIntercept.match("โทรออกหารายการโปรดที่ 1")
        assertTrue("expected CallFavorite, got $r", r is LocalIntercept.Intercept.CallFavorite)
        assertEquals(0, (r as LocalIntercept.Intercept.CallFavorite).zeroBasedSlot)
    }
}

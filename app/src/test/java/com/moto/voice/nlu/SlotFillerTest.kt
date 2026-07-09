package com.moto.voice.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detection + synthesis contract for the slot-filler (spec v1.3.6 §2).
 *
 * The pipeline test in this project can't run without Robolectric, so these unit
 * tests cover the pure logic layer: (a) which bare opener produces which [Need],
 * (b) that a payload prevents detection, (c) the combined sentence spec §2.6
 * requires must be exactly "เปิด youtube กรรมกรข่าว" for the field-report case.
 */
class SlotFillerTest {

    // ─── Bare-opener detection ───────────────────────────────────────────────

    @Test fun bareThoDetectsCall() = assertSame(SlotFiller.Need.CallTarget, SlotFiller.detect("โทร"))
    @Test fun bareThoRhaDetectsCall() = assertSame(SlotFiller.Need.CallTarget, SlotFiller.detect("โทรหา"))
    @Test fun bareThoOokDetectsCall() = assertSame(SlotFiller.Need.CallTarget, SlotFiller.detect("โทรออก"))
    @Test fun bareThoOokRhaDetectsCall() = assertSame(SlotFiller.Need.CallTarget, SlotFiller.detect("โทรออกหา"))
    @Test fun bareThoPaiRhaDetectsCall() = assertSame(SlotFiller.Need.CallTarget, SlotFiller.detect("โทรไปหา"))

    @Test fun bareYutupDetectsYoutube() = assertSame(SlotFiller.Need.YoutubeQuery, SlotFiller.detect("เปิดยูทูป"))
    @Test fun bareYutubDetectsYoutube() = assertSame(SlotFiller.Need.YoutubeQuery, SlotFiller.detect("เปิดยูทูบ"))
    @Test fun bareYoutubeLowerDetectsYoutube() = assertSame(SlotFiller.Need.YoutubeQuery, SlotFiller.detect("เปิด youtube"))
    @Test fun bareYoutubeNoSpaceDetectsYoutube() = assertSame(SlotFiller.Need.YoutubeQuery, SlotFiller.detect("เปิดyoutube"))
    @Test fun searchYutupDetectsYoutube() = assertSame(SlotFiller.Need.YoutubeQuery, SlotFiller.detect("ค้นหายูทูป"))

    @Test fun bareRadioDetectsRadio() = assertSame(SlotFiller.Need.RadioStation, SlotFiller.detect("เปิดวิทยุ"))
    @Test fun bareRadioSpacedDetectsRadio() = assertSame(SlotFiller.Need.RadioStation, SlotFiller.detect("เปิด วิทยุ"))
    @Test fun bareKhluenDetectsRadio() = assertSame(SlotFiller.Need.RadioStation, SlotFiller.detect("เปิดคลื่น"))

    // ─── Payload present ⇒ NO slot-fill ──────────────────────────────────────

    @Test fun callWithNameIsNotBare() = assertSame(SlotFiller.Need.None, SlotFiller.detect("โทรหาแม่"))
    @Test fun youtubeWithQueryIsNotBare() = assertSame(SlotFiller.Need.None, SlotFiller.detect("เปิด youtube กรรมกรข่าว"))
    @Test fun radioWithFrequencyIsNotBare() = assertSame(SlotFiller.Need.None, SlotFiller.detect("เปิดวิทยุ fm 106"))
    @Test fun stopIsNotBare() = assertSame(SlotFiller.Need.None, SlotFiller.detect("หยุด"))
    @Test fun helpIsNotBare() = assertSame(SlotFiller.Need.None, SlotFiller.detect("ทำอะไรได้บ้าง"))
    @Test fun emptyStringIsNotBare() = assertSame(SlotFiller.Need.None, SlotFiller.detect(""))

    // ─── The prompt lines the pipeline speaks ────────────────────────────────

    @Test fun callPromptIsWhoCall() = assertEquals("โทรหาใครคะ", SlotFiller.promptFor(SlotFiller.Need.CallTarget))
    @Test fun youtubePromptIsWhatOpen() = assertEquals("เปิดอะไรดีคะ", SlotFiller.promptFor(SlotFiller.Need.YoutubeQuery))
    @Test fun radioPromptIsWhatStation() = assertEquals("คลื่นอะไรคะ", SlotFiller.promptFor(SlotFiller.Need.RadioStation))

    // ─── Spec §2.6 combine invariant — the field-test example ───────────────

    @Test fun combineYoutubeMatchesSpecExample() {
        // Spec literal: "เปิด youtube กรรมกรข่าว"
        assertEquals(
            "เปิด youtube กรรมกรข่าว",
            SlotFiller.combine(SlotFiller.Need.YoutubeQuery, "กรรมกรข่าว")
        )
    }

    @Test fun combineCallProducesFullSentence() {
        assertEquals("โทรหาแม่", SlotFiller.combine(SlotFiller.Need.CallTarget, "แม่"))
    }

    @Test fun combineRadioProducesFullSentence() {
        assertEquals("เปิดวิทยุ ครอบครัวข่าว", SlotFiller.combine(SlotFiller.Need.RadioStation, "ครอบครัวข่าว"))
    }

    @Test fun combineTrimsWhitespace() {
        // Recognizer sometimes returns padded strings; the combined output must be clean.
        assertEquals("โทรหาแม่", SlotFiller.combine(SlotFiller.Need.CallTarget, "  แม่  "))
    }

    // ─── Sanity: normalize + detect round-trip on real STT output ───────────

    @Test fun realFieldOpenerYoutubeThroughNormalize() {
        // What the recognizer actually captured in field log: "เปิด YouTube" (mixed
        // case). LocalIntercept.normalize will lowercase it — must still detect.
        val normalized = LocalIntercept.normalize("เปิด YouTube")
        assertTrue("normalizer lowercased: $normalized", normalized == "เปิด youtube")
        assertSame(SlotFiller.Need.YoutubeQuery, SlotFiller.detect(normalized))
    }

    // ─── isCancelAnswer — v1.3.7 tightening of the substring check ──────────

    // Whole-answer matches (spec: exact equality after trim+lowercase)

    @Test fun yokelekAloneCancels() = assertTrue(SlotFiller.isCancelAnswer("ยกเลิก"))
    @Test fun maiAowAloneCancels() = assertTrue(SlotFiller.isCancelAnswer("ไม่เอา"))
    @Test fun maiAloneCancels() = assertTrue(SlotFiller.isCancelAnswer("ไม่"))

    @Test fun yokelekWithTrailingSpaceCancels() = assertTrue(SlotFiller.isCancelAnswer("  ยกเลิก  "))

    // Starts-with matches WHEN answer is short

    @Test fun yokelekKhrabIsCancel() = assertTrue(SlotFiller.isCancelAnswer("ยกเลิกครับ"))
    @Test fun yokelekKhaIsCancel() = assertTrue(SlotFiller.isCancelAnswer("ยกเลิกค่ะ"))
    @Test fun maiAowLaewIsCancel() = assertTrue(SlotFiller.isCancelAnswer("ไม่เอาแล้ว"))

    // Starts-with but too long → NOT cancel (protects real answers that happen to begin with "ไม่")

    @Test fun maiAowAllCoveredIsNotCancel() {
        // "ไม่เอาอะไรเลย" = 13 chars — over the 10-char guard.
        assertFalse(SlotFiller.isCancelAnswer("ไม่เอาอะไรเลย"))
    }

    // The substring false-positives spec v1.3.7 §2 called out

    @Test fun dontCancelMeToCallMomIsNotCancel() {
        // "อย่ายกเลิกโทรหาแม่" contains "ยกเลิก" but as a substring, not start of
        // the sentence. Pre-v1.3.7 the contains() check would have false-positived.
        assertFalse(SlotFiller.isCancelAnswer("อย่ายกเลิกโทรหาแม่"))
    }

    @Test fun callToCancelUrgentIsNotCancel() {
        // "โทรยกเลิกด่วน" also contains "ยกเลิก" but starts with "โทร".
        assertFalse(SlotFiller.isCancelAnswer("โทรยกเลิกด่วน"))
    }

    @Test fun midStringMaiIsNotCancel() {
        // A rider naming a Thai contact whose first name starts with a partial that
        // matches midway must not trigger — the answer is a name, not a cancel.
        assertFalse(SlotFiller.isCancelAnswer("แม่ไม่ค่อยว่าง"))
    }

    // Blank / edge cases

    @Test fun blankAnswerIsNotCancel() {
        // Spec: blank is the caller's separate "no reply at all" branch, not cancel.
        assertFalse(SlotFiller.isCancelAnswer(""))
        assertFalse(SlotFiller.isCancelAnswer("   "))
    }

    @Test fun unrelatedShortAnswerIsNotCancel() {
        assertFalse(SlotFiller.isCancelAnswer("แม่"))
        assertFalse(SlotFiller.isCancelAnswer("fm 106"))
    }

    @Test fun trimmedExactMatchStillCancels() {
        // Real STT often returns padded strings — the trim() inside isCancelAnswer
        // must reach the exact-match branch, not fall into the startsWith path.
        assertTrue(SlotFiller.isCancelAnswer("\tยกเลิก\n"))
    }
}

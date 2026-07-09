package com.moto.voice.nlu

import org.junit.Assert.assertEquals
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
}

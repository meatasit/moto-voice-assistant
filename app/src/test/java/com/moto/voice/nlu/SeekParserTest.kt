package com.moto.voice.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.3.11 §2.3 — [SeekParser] regex matrix. The pipeline dispatches the
 * signed-seconds delta to either MediaController.seekTo (with permission) or
 * KEYCODE_MEDIA_FAST_FORWARD/REWIND (fallback); either way, this parser is what
 * decides "forward or backward" and "how many seconds".
 */
class SeekParserTest {

    // ─── Forward — the four synonyms plus the "no direction" ellipsis ───────

    @Test fun lonNaha30WiIsForward30() =
        assertEquals(30, SeekParser.parse("เลื่อนหน้า 30 วิ")?.deltaSeconds)

    @Test fun lonPaiNaha20WinatiIsForward20() =
        assertEquals(20, SeekParser.parse("เลื่อนไปข้างหน้า 20 วินาที")?.deltaSeconds)

    @Test fun kamPaiNahaN15() =
        assertEquals(15, SeekParser.parse("ข้ามไปข้างหน้า 15 วิ")?.deltaSeconds)

    @Test fun skip5Wi() =
        assertEquals(5, SeekParser.parse("skip 5 วิ")?.deltaSeconds)

    @Test fun kroaNaha() =
        assertEquals(SeekParser.DEFAULT_SECONDS, SeekParser.parse("กรอหน้า")?.deltaSeconds)

    // ─── Backward ──────────────────────────────────────────────────────────

    @Test fun thoyKlab30() =
        assertEquals(-30, SeekParser.parse("ถอยกลับ 30 วิ")?.deltaSeconds)

    @Test fun yonKlab15Winati() =
        assertEquals(-15, SeekParser.parse("ย้อนกลับ 15 วินาที")?.deltaSeconds)

    @Test fun yonLangDefaultTen() =
        assertEquals(-SeekParser.DEFAULT_SECONDS, SeekParser.parse("ย้อนหลัง")?.deltaSeconds)

    @Test fun thoyAlone() =
        assertEquals(-SeekParser.DEFAULT_SECONDS, SeekParser.parse("ถอย")?.deltaSeconds)

    // ─── Unit: นาที multiplied by 60 ───────────────────────────────────────

    @Test fun forwardOneMinute() =
        assertEquals(60, SeekParser.parse("เลื่อน 1 นาที")?.deltaSeconds)

    @Test fun forwardThreeMinutes() =
        assertEquals(180, SeekParser.parse("เลื่อน 3 นาที")?.deltaSeconds)

    @Test fun backwardTwoMinutes() =
        assertEquals(-120, SeekParser.parse("ย้อน 2 นาที")?.deltaSeconds)

    // ─── Default (no number, no unit) ──────────────────────────────────────

    @Test fun forwardNoNumberDefaults() =
        assertEquals(SeekParser.DEFAULT_SECONDS, SeekParser.parse("เลื่อนไปหน้า")?.deltaSeconds)

    @Test fun backwardNoNumberDefaults() =
        assertEquals(-SeekParser.DEFAULT_SECONDS, SeekParser.parse("ถอยหลัง")?.deltaSeconds)

    @Test fun skipAloneDefaults() =
        assertEquals(SeekParser.DEFAULT_SECONDS, SeekParser.parse("skip")?.deltaSeconds)

    // ─── Whitespace tolerance ──────────────────────────────────────────────

    @Test fun extraSpacesCollapsed() =
        assertEquals(20, SeekParser.parse("  เลื่อน   หน้า   20   วิ  ")?.deltaSeconds)

    @Test fun noSpaceBetweenNumberAndUnit() {
        // "เลื่อน30วิ" — Google STT sometimes returns tokens glued together.
        // The regex's \\s* between number and unit allows zero whitespace.
        assertEquals(30, SeekParser.parse("เลื่อน30วิ")?.deltaSeconds)
    }

    // ─── Non-seek text ─────────────────────────────────────────────────────

    @Test fun unrelatedTextIsNull() = assertNull(SeekParser.parse("โทรหาแม่"))
    @Test fun emptyStringIsNull() = assertNull(SeekParser.parse(""))
    @Test fun blankIsNull() = assertNull(SeekParser.parse("   "))

    @Test fun openYoutubeIsNotSeek() =
        assertNull(SeekParser.parse("เปิด youtube เพลง"))

    @Test fun stopIsNotSeek() = assertNull(SeekParser.parse("หยุด"))

    // ─── Precedence — backward tokens win over forward ────────────────────

    @Test fun mixedTokenPrefersBackward() {
        // "ย้อนกลับเลื่อน 10 วิ" — the leading "ย้อน" is backward; the regex
        // should not fall through to forward and return +10.
        assertEquals(-10, SeekParser.parse("ย้อนกลับเลื่อน 10 วิ")?.deltaSeconds)
    }
}

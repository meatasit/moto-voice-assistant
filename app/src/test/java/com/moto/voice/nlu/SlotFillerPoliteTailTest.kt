package com.moto.voice.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * v1.4.5 — a bare opener wearing a Thai politeness suffix is still a bare opener.
 *
 * Field log 1789561893967, entries 1789559709013 and 1789561561232: "เปิด YouTube ให้ฟังหน่อย"
 * missed [SlotFiller.detect] (the opener regexes are end-anchored), went to the cloud, and
 * came back `action=chat` / "อยากฟังอะไรดีคะ" after ~4 s — the same question the slot filler
 * asks for free. Twice in one session.
 *
 * The other half of this test file is the part that matters more: the sentences from the
 * SAME log that carry a real payload must still reach the webhook.
 */
class SlotFillerPoliteTailTest {

    // ─── The field-log misses ────────────────────────────────────────────────

    @Test fun politeYoutubeOpenerIsCaughtLocally() = assertSame(
        SlotFiller.Need.YoutubeQuery, SlotFiller.detect("เปิด youtube ให้ฟังหน่อย"),
    )

    @Test fun politeTailStacksAreStripped() = assertSame(
        SlotFiller.Need.YoutubeQuery, SlotFiller.detect("เปิด youtube ให้หน่อยครับ"),
    )

    @Test fun politeRadioOpenerIsCaughtLocally() = assertSame(
        SlotFiller.Need.RadioStation, SlotFiller.detect("เปิดวิทยุหน่อย"),
    )

    @Test fun politeCallOpenerIsCaughtLocally() = assertSame(
        SlotFiller.Need.CallTarget, SlotFiller.detect("โทรหาหน่อยค่ะ"),
    )

    // ─── The guard: a payload still wins ─────────────────────────────────────

    @Test fun namedChannelIsNotAnOpener() = assertSame(
        "1789559608775 — this one has a payload and must reach the webhook",
        SlotFiller.Need.None, SlotFiller.detect("เปิด youtube ช่องในอาร์มให้ฟังหน่อย"),
    )

    @Test fun songRequestIsNotAnOpener() = assertSame(
        "1789559482592 — 'เปิดเพลงจาก youtube' is a request, not a bare opener",
        SlotFiller.Need.None, SlotFiller.detect("เปิดเพลงจาก youtube ให้ฟังหน่อย"),
    )

    @Test fun spotifyRequestIsNotAnOpener() = assertSame(
        "1789559688055 — must still reach the webhook, which answers spotify_play",
        SlotFiller.Need.None, SlotFiller.detect("เปิดเพลงจาก spotify ได้ไหม"),
    )

    @Test fun namedContactIsNotAnOpener() = assertSame(
        SlotFiller.Need.None, SlotFiller.detect("โทรหาแม่หน่อย"),
    )

    // ─── stripPoliteTail itself ──────────────────────────────────────────────

    @Test fun neverStripsTheWholeSentence() = assertEquals(
        "หน่อย alone must survive — an empty result would look like every opener at once",
        "หน่อย", SlotFiller.stripPoliteTail("หน่อย"),
    )

    @Test fun leavesASentenceWithoutATailAlone() = assertEquals(
        "เปิด youtube", SlotFiller.stripPoliteTail("เปิด youtube"),
    )

    @Test fun stripsTheLongestTailWhole() = assertEquals(
        "ให้ฟังหน่อย must not leave ให้ฟัง behind",
        "เปิด youtube", SlotFiller.stripPoliteTail("เปิด youtube ให้ฟังหน่อย"),
    )
}

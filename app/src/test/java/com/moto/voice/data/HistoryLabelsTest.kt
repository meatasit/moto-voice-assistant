package com.moto.voice.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.35 — the home list and [com.moto.voice.HistoryActivity] now render from the same
 * mapping. Lock the wording so a change in one surface can't silently diverge from the
 * other, and pin the replayable set the home screen uses to decide whether a row gets a
 * click listener at all.
 */
class HistoryLabelsTest {

    private fun entry(action: HistoryAction, heard: String = "เปิดเพลง") =
        HistoryEntry(timestamp = 0L, heard = heard, spoken = "", action = action)

    @Test fun callShowsContactName() {
        assertEquals("โทรหาสมชาย", HistoryLabels.title(HistoryAction.Call("สมชาย", "0812345678")))
        assertEquals("📞", HistoryLabels.icon(HistoryAction.Call("สมชาย", "0812345678")))
    }

    @Test fun youtubeFallsBackWhenTitleBlank() {
        assertEquals("เปิด YouTube: กรรมกรข่าว", HistoryLabels.title(HistoryAction.YoutubeOpen("abc123", "กรรมกรข่าว")))
        assertEquals("เปิด YouTube", HistoryLabels.title(HistoryAction.YoutubeOpen("abc123", "")))
    }

    @Test fun fmShowsStationName() {
        assertEquals("เปิดวิทยุสวพ. 91", HistoryLabels.title(HistoryAction.FmPlay("http://x", "สวพ. 91", 91.0)))
    }

    @Test fun everyActionHasAnIcon() {
        listOf(
            HistoryAction.Call("a", "1"),
            HistoryAction.YoutubeOpen("v", "t"),
            HistoryAction.FmPlay("u", "s", 91.0),
            HistoryAction.Stop,
            HistoryAction.Speak("x"),
            HistoryAction.Chat("x"),
        ).forEach { assertTrue("no icon for $it", HistoryLabels.icon(it).isNotBlank()) }
    }

    @Test fun subtitleMarksEmptyStt() {
        assertEquals("คุณพูด: เปิดเพลง", HistoryLabels.subtitle(entry(HistoryAction.Stop)))
        assertEquals("คุณพูด: (ไม่ได้จับความ)", HistoryLabels.subtitle(entry(HistoryAction.Stop, heard = "")))
    }

    @Test fun speakAndChatAreNotReplayable() {
        // Nothing to re-run — home skips the click listener so the tap isn't a dead end.
        assertFalse(HistoryLabels.isReplayable(HistoryAction.Speak("x")))
        assertFalse(HistoryLabels.isReplayable(HistoryAction.Chat("x")))
    }

    @Test fun mediaActionsAreReplayable() {
        assertTrue(HistoryLabels.isReplayable(HistoryAction.Call("a", "1")))
        assertTrue(HistoryLabels.isReplayable(HistoryAction.YoutubeOpen("v", "t")))
        assertTrue(HistoryLabels.isReplayable(HistoryAction.FmPlay("u", "s", null)))
        assertTrue(HistoryLabels.isReplayable(HistoryAction.Stop))
    }
}

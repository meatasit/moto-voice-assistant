package com.moto.voice.nlu

import org.junit.Assert.assertSame
import org.junit.Test

/**
 * v1.4.6 — a next-item phrase buried mid-sentence still means "อันต่อไป".
 *
 * `matchesAsPhrase` accepts a pattern only at index 0 or after a SPACE, and Thai does not
 * put spaces between words, so any next-item phrase with something in front of it was
 * invisible. Field log 1789637279880, entry 1789610009315:
 *
 * ```
 * sttFinal: "เปิดรายการถัดไปของไอ้อาร์"
 * webhookResponse: action=youtube_play, video_id=null, videos=[]
 *                  speak="หาวิดีโอไม่เจอ เปิดหน้าค้นหาให้แทนนะคะ"
 * ```
 *
 * It went to the cloud, which searched for the phrase literally and found nothing — while
 * MediaSessionMemory still held that channel's list from entry 1789608604885 and would have
 * answered "หนี้ทางเทคนิค (Technical Debt)".
 *
 * The half of this file that matters more is the second: "เล่นต่อไป" is a RESUME and must
 * keep falling through to PlayContinue. Widening `matchesAsPhrase` instead would have taken
 * it, which is why the fix asks for a noun in front of ถัดไป/ต่อไป.
 */
class NextItemInterceptTest {

    private fun match(text: String) = LocalIntercept.match(text)

    // ─── The field-log miss ──────────────────────────────────────────────────

    @Test fun buriedNextItemIsCaught() = assertSame(
        LocalIntercept.Intercept.NextVideo, match("เปิดรายการถัดไปของไอ้อาร์"),
    )

    @Test fun nextClipIsCaught() = assertSame(
        LocalIntercept.Intercept.NextVideo, match("เปิดคลิปต่อไป"),
    )

    @Test fun nextSongIsCaught() = assertSame(
        LocalIntercept.Intercept.NextVideo, match("ขอเพลงถัดไปหน่อย"),
    )

    @Test fun nextEpisodeIsCaught() = assertSame(
        LocalIntercept.Intercept.NextVideo, match("เอาตอนต่อไปเลย"),
    )

    // ─── The guard: a resume is not a skip ───────────────────────────────────

    @Test fun playContinueIsNotASkip() {
        val i = match("เล่นต่อไป")
        assertSame(
            "'เล่นต่อไป' is resume — skipping to the next video instead would be the worst" +
                " possible reading of it",
            LocalIntercept.Intercept.PlayContinue::class.java, i.javaClass,
        )
    }

    @Test fun bareResumeStillResumes() {
        assertSame(
            LocalIntercept.Intercept.PlayContinue::class.java, match("เล่นต่อ").javaClass,
        )
    }

    @Test fun namedAppResumeStillResumes() {
        assertSame(
            LocalIntercept.Intercept.PlayContinue::class.java, match("เปิด youtube ต่อ").javaClass,
        )
    }

    // ─── The guard: ordinary commands from this same log are untouched ───────

    @Test fun ordinaryOpenCommandsAreNotSkips() {
        listOf(
            "เปิดเพลงให้ฟังหน่อย",
            "เปิดเพลงบรรเลงสากล",
            "เปิดรายการเรื่องเล่าเช้านี้",
            "ถ้าเปิดกรรมกรข่าวคุยนอกจอ",
            "เปิด youtube ช่องในอาร์มที่สะกดเลข 9 a r m",
        ).forEach {
            assertSame("must still reach the webhook: $it", LocalIntercept.Intercept.None, match(it))
        }
    }
}

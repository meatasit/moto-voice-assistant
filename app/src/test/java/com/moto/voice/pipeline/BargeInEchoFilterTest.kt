package com.moto.voice.pipeline

import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Spec v1.3.9 §2.2.ข + §2.2.ฉ — the classifier that decides whether an STT partial
 * captured DURING TTS speech is (a) the assistant's own tone bleeding into the mic
 * (drop it, keep TTS going), (b) the rider actually answering (stop TTS, use it),
 * or (c) too short to tell yet.
 *
 * Pure similarity math (ThaiNormalizer + Levenshtein) so it's testable without
 * Robolectric.
 */
class BargeInEchoFilterTest {

    private val confirmPrompt = "โทรหากุลวดี ใช่ไหมคะ"
    private val disambigPrompt = "มี 3 รายชื่อ หนึ่ง แม่ สอง พ่อ สาม พี่ พูด หนึ่ง สอง หรือ สาม คะ"

    // ─── §2.2.ฉ contract — echo dropped, real answer promoted ───────────────

    @Test fun exactPromptEcho_isEcho() {
        // Perfect echo of the prompt — 1.0 similarity, well above threshold.
        assertSame(
            TtsEchoFilter.BargeInClass.ECHO,
            TtsEchoFilter.classifyDuringTts(confirmPrompt, confirmPrompt)
        )
    }

    @Test fun nearPromptEcho_isEcho() {
        // What the STT typically hears back on a speaker echo — one or two chars off.
        assertSame(
            TtsEchoFilter.BargeInClass.ECHO,
            TtsEchoFilter.classifyDuringTts("โทรหากุลวดีใช่ไหมค่า", confirmPrompt)
        )
    }

    @Test fun oneWordCancel_isRealAnswer() {
        // Rider cuts in mid-question with a one-word bail-out — clearly not an echo.
        assertSame(
            TtsEchoFilter.BargeInClass.REAL_ANSWER,
            TtsEchoFilter.classifyDuringTts("ยกเลิก", confirmPrompt)
        )
    }

    @Test fun oneWordYes_isRealAnswer() {
        assertSame(
            TtsEchoFilter.BargeInClass.REAL_ANSWER,
            TtsEchoFilter.classifyDuringTts("ใช่", confirmPrompt)
        )
    }

    @Test fun numberAnswerDuringDisambig_isRealAnswer() {
        // Disambig case — rider says the slot number while the prompt is still reading.
        assertSame(
            TtsEchoFilter.BargeInClass.REAL_ANSWER,
            TtsEchoFilter.classifyDuringTts("สอง", disambigPrompt)
        )
    }

    // ─── UNKNOWN when the partial is too short to trust ─────────────────────

    @Test fun singleCharPartial_isUnknown() {
        // A single character can plausibly be either an echo starting to form OR the
        // beginning of a real answer — the classifier says UNKNOWN so TTS keeps going.
        assertSame(
            TtsEchoFilter.BargeInClass.UNKNOWN,
            TtsEchoFilter.classifyDuringTts("โ", confirmPrompt)
        )
    }

    @Test fun blankPartial_isUnknown() {
        assertSame(
            TtsEchoFilter.BargeInClass.UNKNOWN,
            TtsEchoFilter.classifyDuringTts("", confirmPrompt)
        )
    }

    @Test fun whitespaceOnly_isUnknown() {
        assertSame(
            TtsEchoFilter.BargeInClass.UNKNOWN,
            TtsEchoFilter.classifyDuringTts("   ", confirmPrompt)
        )
    }

    // ─── Robustness: TTS text unknown → treat partial as real ──────────────

    @Test fun nullTtsText_treatsPartialAsRealAnswer() {
        // If TtsRecentSpeech has no current utterance (edge case: race where TTS
        // hasn't started emitting yet) we can't classify, so play it safe by
        // assuming the rider spoke.
        assertSame(
            TtsEchoFilter.BargeInClass.REAL_ANSWER,
            TtsEchoFilter.classifyDuringTts("โทรหาแม่", null)
        )
    }

    @Test fun blankTtsText_treatsPartialAsRealAnswer() {
        assertSame(
            TtsEchoFilter.BargeInClass.REAL_ANSWER,
            TtsEchoFilter.classifyDuringTts("โทรหาแม่", "")
        )
    }

    // ─── Threshold discipline — the same 0.75 as post-listen isEcho ────────

    @Test fun nearPerfectMatchesAgreeAcrossBothFilters() {
        // Whole-prompt-length near-echoes classify the same in both filters — the
        // strict / substring-friendly variants only diverge on short partials that
        // happen to be substrings of a much longer prompt. Lock that they agree on
        // the non-divergent case.
        val nearWholePromptEcho = "โทรหากุลวดี ใช่ไหมค่า"  // one char off from the prompt
        val hardEcho = TtsEchoFilter.isEcho(nearWholePromptEcho, confirmPrompt)
        val bargeInSaysEcho = TtsEchoFilter.classifyDuringTts(nearWholePromptEcho, confirmPrompt) ==
            TtsEchoFilter.BargeInClass.ECHO
        assert(hardEcho && bargeInSaysEcho) {
            "near-whole-prompt echo must trigger both filters; " +
                "isEcho=$hardEcho, classifyDuringTts=echo=$bargeInSaysEcho"
        }
    }

    @Test fun shortAnswerInsidePromptDivergesIntentionally() {
        // isEcho uses ThaiNormalizer.similarity which gives a substring 0.9 to help
        // fuzzy contact-name matching ("สม" inside "สมชาย"). That's the wrong
        // semantic for barge-in: "ใช่" is a legit short answer even though it's a
        // substring of the confirm prompt. classifyDuringTts uses strict similarity
        // (Levenshtein-only) so it correctly flags the short answer as REAL_ANSWER
        // while isEcho — used for post-listen contact fuzz — flags it as echo.
        val shortAnswer = "ใช่"
        assert(TtsEchoFilter.isEcho(shortAnswer, confirmPrompt)) {
            "isEcho substring boost should still fire (contact-match semantic)"
        }
        assert(TtsEchoFilter.classifyDuringTts(shortAnswer, confirmPrompt) ==
            TtsEchoFilter.BargeInClass.REAL_ANSWER) {
            "classifyDuringTts must NOT treat short substring as echo — that's the fix"
        }
    }
}

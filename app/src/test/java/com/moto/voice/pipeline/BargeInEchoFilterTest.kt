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

    @Test fun sameThresholdAsIsEcho() {
        // If a future refactor lifts one threshold without the other the barge-in
        // decision and the post-listen echo-drop decision would drift apart. Lock
        // the two by construction: the classifier uses TtsEchoFilter.ECHO_SIMILARITY_THRESHOLD.
        val roughlySimilar = "โทรหากุลวดีใช่ไหม"
        val hardEcho = TtsEchoFilter.isEcho(roughlySimilar, confirmPrompt)
        val bargeInSaysEcho = TtsEchoFilter.classifyDuringTts(roughlySimilar, confirmPrompt) ==
            TtsEchoFilter.BargeInClass.ECHO
        // Both must agree: either both flag as echo or neither does.
        assert(hardEcho == bargeInSaysEcho) {
            "isEcho and classifyDuringTts must agree on threshold; " +
                "isEcho=$hardEcho, classifyDuringTts=echo=$bargeInSaysEcho"
        }
    }
}

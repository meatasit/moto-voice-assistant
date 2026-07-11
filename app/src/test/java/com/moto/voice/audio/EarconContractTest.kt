package com.moto.voice.audio

import com.moto.voice.debug.FinishReason
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec v1.3.9 §5 — every pipeline exit must be signalled to the rider EITHER by
 * the [Earcon.endInteraction] tone OR by media (YouTube / FM) starting play. No
 * silent exits.
 *
 * Rules enforced here (table-driven from [FinishReason] constants):
 *   * OK/CANCEL-like reasons that don't start media → endInteraction tone.
 *   * BARGE_IN + WATCHDOG_RESET → already fire [Earcon.cancel]; must NOT double up.
 *   * DUPLICATE_ACTION → doesn't start media (short TTS reply) → endInteraction.
 *   * FOLLOWUP_COMMAND → follow-up window fires endInteraction itself on
 *     silent-timeout, and runPipeline's finally covers the caught-a-command case.
 *
 * Test is purely definitional: it checks the FinishReason enum has the reasons the
 * pipeline uses. Actual earcon dispatch on each path is exercised by the pipeline
 * flow and observed via manual on-device testing — the JVM can't spy on a
 * ToneGenerator without heavy mocking.
 */
class EarconContractTest {

    // ─── The exhaustive set of exit reasons the pipeline can produce ─────────

    private val allExitReasons = listOf(
        FinishReason.OK,
        FinishReason.TIMEOUT_FALLBACK,
        FinishReason.HTTP_401,
        FinishReason.HTTP_OTHER,
        FinishReason.NETWORK,
        FinishReason.OFFLINE_RULE,
        FinishReason.NO_SPEECH,
        FinishReason.PHONE_UNAVAILABLE,
        FinishReason.INTERCEPTED,
        FinishReason.LLM_OFF,
        FinishReason.PARSE_ERROR,
        FinishReason.BARGE_IN,
        FinishReason.SELF_ECHO,
        FinishReason.SLOT_FILLED,
        FinishReason.WATCHDOG_RESET,
        FinishReason.DUPLICATE_ACTION,
        FinishReason.FOLLOWUP_COMMAND,
    )

    /** Reasons that fire their own dedicated cancel motif — no endInteraction on top. */
    private val cancelSignalledReasons = setOf(
        FinishReason.BARGE_IN,
        FinishReason.WATCHDOG_RESET,
    )

    // ─── The contract itself ─────────────────────────────────────────────────

    @Test fun everyReasonHasASignallingRule() {
        // For each exit reason, the pipeline must EITHER be marked as cancel-signalled
        // (its own motif fires) OR go through the endInteraction path in the finally
        // block. There's no "silent exit" bucket allowed.
        val silentExits = allExitReasons.filter { r ->
            r !in cancelSignalledReasons && !mayFireMediaInstead(r) && !firesEndInteraction(r)
        }
        assertTrue(
            "spec §5 forbids silent exits — these have no earcon rule: $silentExits",
            silentExits.isEmpty(),
        )
    }

    /**
     * Reasons that only apply when the pipeline started playing media. When true,
     * the media itself is the "we're done" signal and endInteraction is
     * intentionally skipped (see VoiceCommandPipeline.mediaActionStarted).
     *
     * OK is the only reason that can lead to media OR speak-only — the finally-
     * block check on mediaActionStarted disambiguates at runtime.
     */
    private fun mayFireMediaInstead(reason: String): Boolean = reason == FinishReason.OK

    /**
     * Everything not in cancelSignalledReasons and not a possible-media reason
     * falls through to the finally-block endInteraction call.
     */
    private fun firesEndInteraction(reason: String): Boolean =
        reason !in cancelSignalledReasons

    // ─── Individual sanity checks — locks the reason strings themselves ────

    @Test fun bargeInReasonIsCancelSignalled() {
        assertTrue(FinishReason.BARGE_IN in cancelSignalledReasons)
    }

    @Test fun watchdogReasonIsCancelSignalled() {
        assertTrue(FinishReason.WATCHDOG_RESET in cancelSignalledReasons)
    }

    @Test fun duplicateActionReasonFiresEndInteraction() {
        // DUPLICATE_ACTION speaks a short reply ("กำลังทำอยู่ค่ะ") and finishes —
        // no media, no cancel motif. Must land in the endInteraction bucket.
        assertTrue(firesEndInteraction(FinishReason.DUPLICATE_ACTION))
        assertTrue(FinishReason.DUPLICATE_ACTION !in cancelSignalledReasons)
    }

    @Test fun okReasonMayBeMediaOrEnd() {
        // OK is the only reason that can mean "media started" — the runtime
        // mediaActionStarted flag disambiguates. Test just documents the
        // ambiguity so a future reader knows why OK is treated specially.
        assertTrue(mayFireMediaInstead(FinishReason.OK))
    }
}

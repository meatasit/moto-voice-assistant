package com.moto.voice.pipeline

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Spec v1.3.8 A5 — the pipeline guard against helmet-button double-taps and STT-echo
 * repeats. Field pattern: rider says "โทรหาแม่", STT lag makes the display refresh
 * feel slow, they say it again → two calls placed. Or the helmet BVRA button
 * triggers twice from a single squeeze.
 *
 * Pure state, no Context — takes an explicit `nowMs` so time is deterministic.
 */
class DedupeGuardTest {

    @Before fun reset() = DedupeGuard.resetForTest()
    @After fun tearDown() = DedupeGuard.resetForTest()

    // ─── Same key inside the window ─────────────────────────────────────────

    @Test fun firstCallIsNeverDuplicate() {
        assertFalse(DedupeGuard.isRecentDuplicate("call|แม่", nowMs = 1_000))
    }

    @Test fun sameKeyImmediatelyAfterMarkIsDuplicate() {
        DedupeGuard.markExecuted("call|แม่", nowMs = 1_000)
        assertTrue(DedupeGuard.isRecentDuplicate("call|แม่", nowMs = 1_500))
    }

    @Test fun sameKeyAtEdgeOfWindowIsDuplicate() {
        DedupeGuard.markExecuted("call|แม่", nowMs = 1_000)
        // WINDOW_MS = 3_000 → mark at 1000, check at 4000 = exactly on the edge.
        assertTrue(DedupeGuard.isRecentDuplicate("call|แม่", nowMs = 1_000 + DedupeGuard.WINDOW_MS))
    }

    @Test fun sameKeyBeyondWindowIsNotDuplicate() {
        DedupeGuard.markExecuted("call|แม่", nowMs = 1_000)
        assertFalse(DedupeGuard.isRecentDuplicate("call|แม่", nowMs = 1_000 + DedupeGuard.WINDOW_MS + 1))
    }

    // ─── Different key = not duplicate ─────────────────────────────────────

    @Test fun differentContactSameActionIsNotDuplicate() {
        DedupeGuard.markExecuted(DedupeGuard.keyOf("call", "แม่"), nowMs = 1_000)
        assertFalse(DedupeGuard.isRecentDuplicate(DedupeGuard.keyOf("call", "พ่อ"), nowMs = 1_500))
    }

    @Test fun differentActionSameContactIsNotDuplicate() {
        DedupeGuard.markExecuted(DedupeGuard.keyOf("call", "แม่"), nowMs = 1_000)
        assertFalse(DedupeGuard.isRecentDuplicate(DedupeGuard.keyOf("youtube_play", "แม่"), nowMs = 1_500))
    }

    // ─── keyOf composition ─────────────────────────────────────────────────

    @Test fun keyIsStableForSameInput() {
        assertEquals(DedupeGuard.keyOf("call", "แม่"), DedupeGuard.keyOf("call", "แม่"))
    }

    @Test fun keyTrimsWhitespace() {
        // STT sometimes returns padded strings — we don't want "โทร|แม่" and "โทร| แม่ "
        // to be counted as different actions.
        assertEquals(DedupeGuard.keyOf("call", "แม่"), DedupeGuard.keyOf("call", "  แม่  "))
    }

    @Test fun keyIncludesPayloadForDisambiguation() {
        // Different payload = different key even for the same action.
        assertFalse(DedupeGuard.keyOf("call", "แม่") == DedupeGuard.keyOf("call", "พ่อ"))
    }

    @Test fun keyHandlesNullPayload() {
        // Some actions (e.g. stop) have no payload — keyOf must not crash.
        DedupeGuard.markExecuted(DedupeGuard.keyOf("stop", null), nowMs = 1_000)
        assertTrue(DedupeGuard.isRecentDuplicate(DedupeGuard.keyOf("stop", null), nowMs = 1_500))
    }

    // ─── Window constant is the number the pipeline advertises ─────────────

    @Test fun windowIsThreeSeconds() {
        // Locks the constant so a bump requires deliberate intent + spec revision.
        assertEquals(3_000L, DedupeGuard.WINDOW_MS)
    }

    // ─── Re-mark refreshes the window ──────────────────────────────────────

    @Test fun reMarkExtendsTheWindow() {
        // Rider says "โทรหาแม่" at t=1000, then again at t=2500 (still inside window).
        // The re-mark should reset the window from t=2500, not t=1000.
        DedupeGuard.markExecuted("call|แม่", nowMs = 1_000)
        DedupeGuard.markExecuted("call|แม่", nowMs = 2_500)
        // At t=5000 that's 2500ms after the latest mark — still inside 3s window.
        assertTrue(DedupeGuard.isRecentDuplicate("call|แม่", nowMs = 5_000))
        // At t=5600 that's >3s from the latest mark — outside the window.
        assertFalse(DedupeGuard.isRecentDuplicate("call|แม่", nowMs = 5_600))
    }
}

package com.moto.voice.bt

import com.moto.voice.network.WebhookClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.37 — the helmet-connect warm-up. Rider's rule: *"อุ่นเครื่องตอนต่อหมวกก็พอ ถ้าต่อหมวกแล้ว
 * LLM ไม่ทำงานให้รายงาน"* — so the only thing that must never happen is chatter on a healthy
 * connect, and the only thing that must always happen is a word when the LLM is not usable.
 */
class LlmWarmupTest {

    private fun outcome(
        success: Boolean,
        kind: WebhookClient.Kind? = null,
        configured: Boolean = true,
        timedOutLocally: Boolean = false,
    ) = LlmWarmup.outcomeFor(configured, success, kind, timedOutLocally)

    // ─── Classification ──────────────────────────────────────────────────────

    @Test fun successMeansWarm() {
        assertEquals(LlmWarmup.Outcome.Warm, outcome(success = true))
    }

    @Test fun timeoutMeansTheModelIsLoading() {
        // 26s of model load is what started this whole feature (field log 1786178611552).
        assertEquals(LlmWarmup.Outcome.Loading, outcome(false, WebhookClient.Kind.Timeout))
    }

    @Test fun ourOwnTimeoutAlsoMeansLoading() {
        assertEquals(LlmWarmup.Outcome.Loading, outcome(false, null, timedOutLocally = true))
    }

    @Test fun networkFailureMeansUnreachable() {
        // "ไม่ได้เปิด Com LLM ไว้" lands here.
        assertEquals(LlmWarmup.Outcome.Unreachable, outcome(false, WebhookClient.Kind.Network))
    }

    @Test fun unauthorizedIsItsOwnOutcome() {
        assertEquals(LlmWarmup.Outcome.Rejected, outcome(false, WebhookClient.Kind.Http401))
    }

    @Test fun otherFailuresAreBroken() {
        assertEquals(LlmWarmup.Outcome.Broken, outcome(false, WebhookClient.Kind.HttpOther))
        assertEquals(LlmWarmup.Outcome.Broken, outcome(false, WebhookClient.Kind.Parse))
    }

    @Test fun noWebhookConfiguredIsNotAProblemToAnnounce() {
        assertEquals(LlmWarmup.Outcome.NotConfigured, outcome(false, null, configured = false))
    }

    // ─── What gets spoken ────────────────────────────────────────────────────

    @Test fun healthyConnectSaysNothing() {
        assertNull(LlmWarmup.lineFor(LlmWarmup.Outcome.Warm))
        assertNull(LlmWarmup.lineFor(LlmWarmup.Outcome.NotConfigured))
    }

    @Test fun everyProblemOutcomeSpeaks() {
        listOf(
            LlmWarmup.Outcome.Loading,
            LlmWarmup.Outcome.Unreachable,
            LlmWarmup.Outcome.Rejected,
            LlmWarmup.Outcome.Broken,
        ).forEach {
            val line = LlmWarmup.lineFor(it)
            assertNotNull("$it must be announced", line)
            assertTrue("$it line must not be empty", line!!.isNotBlank())
        }
    }

    @Test fun problemLinesAreDistinct() {
        // The rider should be able to tell "box is off" from "model still loading" by ear.
        val lines = listOf(
            LlmWarmup.Outcome.Loading,
            LlmWarmup.Outcome.Unreachable,
            LlmWarmup.Outcome.Rejected,
            LlmWarmup.Outcome.Broken,
        ).map { LlmWarmup.lineFor(it) }
        assertEquals(lines.size, lines.toSet().size)
    }

    // ─── Re-warm cooldown ────────────────────────────────────────────────────

    @Test fun firstConnectAlwaysWarms() {
        assertTrue(LlmWarmup.shouldWarm(nowMs = 1_000L, lastWarmOkAtMs = null))
    }

    @Test fun reconnectInsideCooldownDoesNotRePing() {
        // Helmet dropping and re-pairing at a light must not fire a model load each time.
        val now = 10 * 60 * 1000L
        assertFalse(LlmWarmup.shouldWarm(now, lastWarmOkAtMs = now - 60_000L))
    }

    @Test fun warmsAgainAfterTheCooldown() {
        val now = 10 * 60 * 60 * 1000L
        assertTrue(LlmWarmup.shouldWarm(now, lastWarmOkAtMs = now - LlmWarmup.REWARM_AFTER_MS))
    }

    @Test fun pingTimeoutAbsorbsAColdModelLoad() {
        // The observed cold load was 26s; the ping budget has to be comfortably past it or
        // the warm-up reports "loading" on every single connect.
        assertTrue(LlmWarmup.PING_TIMEOUT_SEC > 30)
    }
}

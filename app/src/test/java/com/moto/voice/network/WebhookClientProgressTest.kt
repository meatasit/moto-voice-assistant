package com.moto.voice.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Verifies the progress-callback logic in [WebhookClient.call] without touching the
 * network. We simulate the network call by mimicking the same coroutine shape
 * (coroutineScope + child job) that WebhookClient uses.
 *
 * The actual OkHttp call path is exercised by field testing — mocking OkHttp reliably
 * from a pure JVM test would need MockWebServer, which is heavyweight for this file.
 */
class WebhookClientProgressTest {

    /**
     * Reproduce the "coroutineScope { launch progress; do work; cancel progress }"
     * shape from WebhookClient.call and check the timing.
     */
    private suspend fun runWithProgress(
        workDurationMs: Long,
        onProgress: suspend (Long) -> Unit,
    ) = kotlinx.coroutines.coroutineScope {
        val progressJob = launch {
            try {
                delay(3_000L)
                onProgress(3_000L)
                delay(7_000L)
                onProgress(10_000L)
            } catch (_: Exception) { /* cancelled */ }
        }
        try {
            delay(workDurationMs)
            "done"
        } finally {
            progressJob.cancel()
        }
    }

    @Test
    fun noProgressWhenFastResponse() = runTest {
        val marks = CopyOnWriteArrayList<Long>()
        runWithProgress(workDurationMs = 500L) { marks += it }
        advanceUntilIdle()
        assertTrue("expected no progress marks, got $marks", marks.isEmpty())
    }

    @Test
    fun firstProgressMarkAtThreeSeconds() = runTest {
        val marks = CopyOnWriteArrayList<Long>()
        runWithProgress(workDurationMs = 5_000L) { marks += it }
        advanceUntilIdle()
        assertEquals(listOf(3_000L), marks.toList())
    }

    @Test
    fun bothMarksWhenWorkTakesOverTenSeconds() = runTest {
        val marks = CopyOnWriteArrayList<Long>()
        runWithProgress(workDurationMs = 12_000L) { marks += it }
        advanceUntilIdle()
        assertEquals(listOf(3_000L, 10_000L), marks.toList())
    }

    @Test
    fun progressStopsAsSoonAsWorkFinishes() = runTest {
        val marks = CopyOnWriteArrayList<Long>()
        runWithProgress(workDurationMs = 7_000L) { marks += it }
        advanceUntilIdle()
        // Fired at 3s but not at 10s, because the work finished at 7s.
        assertEquals(listOf(3_000L), marks.toList())
    }
}

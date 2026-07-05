package com.moto.voice.data

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide state machine that guarantees the "โหมดออฟไลน์" TTS notice fires at most
 * once per outage. Consecutive fallbacks stay quiet; the next webhook success rearms
 * the notifier.
 *
 * In-memory (not persisted): a process restart implicitly rearms — the rider deserves
 * to hear it again after a reboot.
 */
object OfflineNotifier {
    private val announced = AtomicBoolean(false)

    /** @return true if the caller should speak "โหมดออฟไลน์" now; false to stay quiet. */
    fun shouldAnnounce(): Boolean = announced.compareAndSet(false, true)

    /** Call after any webhook success — re-arms the notice for the next outage. */
    fun onWebhookSuccess() { announced.set(false) }

    /** Test hook: reset to initial state. */
    internal fun resetForTest() { announced.set(false) }

    /** Test hook: peek current state without changing it. */
    internal fun isAnnouncedForTest(): Boolean = announced.get()
}

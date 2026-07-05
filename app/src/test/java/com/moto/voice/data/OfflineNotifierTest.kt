package com.moto.voice.data

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OfflineNotifierTest {

    @Before fun reset() = OfflineNotifier.resetForTest()
    @After fun tearDown() = OfflineNotifier.resetForTest()

    @Test
    fun firstShouldAnnounceReturnsTrue() {
        assertTrue(OfflineNotifier.shouldAnnounce())
    }

    @Test
    fun secondShouldAnnounceIsQuiet() {
        assertTrue(OfflineNotifier.shouldAnnounce())
        assertFalse(OfflineNotifier.shouldAnnounce())
        assertFalse(OfflineNotifier.shouldAnnounce())
    }

    @Test
    fun successRearmsNotifier() {
        OfflineNotifier.shouldAnnounce()
        OfflineNotifier.onWebhookSuccess()
        assertTrue(OfflineNotifier.shouldAnnounce())
    }

    @Test
    fun successWhenAlreadyArmedIsNoop() {
        OfflineNotifier.onWebhookSuccess()
        assertTrue(OfflineNotifier.shouldAnnounce())
    }

    @Test
    fun realOutageSequence() {
        // Fresh outage → announce.
        assertTrue(OfflineNotifier.shouldAnnounce())
        // Several more failures during the same outage → silent.
        repeat(5) { assertFalse(OfflineNotifier.shouldAnnounce()) }
        // Network recovers.
        OfflineNotifier.onWebhookSuccess()
        // Later, another outage → announce again.
        assertTrue(OfflineNotifier.shouldAnnounce())
    }
}

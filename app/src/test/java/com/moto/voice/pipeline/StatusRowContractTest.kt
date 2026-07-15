package com.moto.voice.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class StatusRowContractTest {

    @Test fun nineKinds() {
        // Spec §7 (v1.3.0) shipped with 7 rows; v1.3.11 §1 adds MediaCtrl as the 8th
        // (notification-listener); v1.3.24 adds LockScreenLaunch as the 9th
        // (USE_FULL_SCREEN_INTENT — open media over the lock screen). Lock so a future
        // refactor doesn't accidentally drop one.
        assertEquals(9, StatusRow.Kind.values().size)
    }

    @Test fun fourStates() {
        // Green / Yellow / Red / Pending — Pending is used while async checks run.
        assertEquals(4, StatusRow.State.values().size)
    }

    @Test fun requiredKindsPresent() {
        val kinds = StatusRow.Kind.values().toSet()
        listOf(
            StatusRow.Kind.DefaultAssistant, StatusRow.Kind.Permissions,
            StatusRow.Kind.Battery, StatusRow.Kind.Helmet,
            StatusRow.Kind.Webhook, StatusRow.Kind.Tts, StatusRow.Kind.Internet,
            StatusRow.Kind.MediaCtrl,  // v1.3.11 §1 — notification-listener access
            StatusRow.Kind.LockScreenLaunch,  // v1.3.24 — full-screen-intent over lock screen
        ).forEach { assertNotNull("missing $it", kinds.contains(it)) }
    }

    @Test fun stateColorsDistinct() {
        assertNotEquals(StatusRow.State.Green, StatusRow.State.Red)
        assertNotEquals(StatusRow.State.Green, StatusRow.State.Yellow)
        assertNotEquals(StatusRow.State.Yellow, StatusRow.State.Red)
    }
}

package com.moto.voice.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class StatusRowContractTest {

    @Test fun eightKinds() {
        // Spec §7 (v1.3.0) shipped with 7 rows; v1.3.11 §1 adds MediaCtrl as the
        // 8th (notification-listener permission). Lock so a future refactor doesn't
        // accidentally drop one.
        assertEquals(8, StatusRow.Kind.values().size)
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
        ).forEach { assertNotNull("missing $it", kinds.contains(it)) }
    }

    @Test fun stateColorsDistinct() {
        assertNotEquals(StatusRow.State.Green, StatusRow.State.Red)
        assertNotEquals(StatusRow.State.Green, StatusRow.State.Yellow)
        assertNotEquals(StatusRow.State.Yellow, StatusRow.State.Red)
    }
}

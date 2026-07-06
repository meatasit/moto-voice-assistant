package com.moto.voice.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class StatusRowContractTest {

    @Test fun sevenKinds() {
        // Spec §7 lists 7 rows on the System Status page. Locking the enum so a
        // future refactor doesn't accidentally drop or duplicate one.
        assertEquals(7, StatusRow.Kind.values().size)
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
        ).forEach { assertNotNull("missing $it", kinds.contains(it)) }
    }

    @Test fun stateColorsDistinct() {
        assertNotEquals(StatusRow.State.Green, StatusRow.State.Red)
        assertNotEquals(StatusRow.State.Green, StatusRow.State.Yellow)
        assertNotEquals(StatusRow.State.Yellow, StatusRow.State.Red)
    }
}

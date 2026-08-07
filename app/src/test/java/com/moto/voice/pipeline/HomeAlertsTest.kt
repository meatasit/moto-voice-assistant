package com.moto.voice.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.35 — the home screen only earns its space if it stays quiet when the install is
 * healthy and shouts exactly once when a grant goes missing after an upgrade.
 *
 * Rows are built with `fixIntent = null` throughout: constructing a real
 * `android.content.Intent` is not possible in a pure-JVM test (see CLAUDE.md test
 * conventions), which is why [HomeAlerts] keys off kind + state rather than fixIntent.
 */
class HomeAlertsTest {

    private fun row(kind: StatusRow.Kind, state: StatusRow.State) =
        StatusRow(kind, kind.name, state)

    @Test fun greenIsNeverAnAlert() {
        StatusRow.Kind.values().forEach { kind ->
            assertFalse("$kind green must not alert", HomeAlerts.isAlert(kind, StatusRow.State.Green))
        }
    }

    @Test fun pendingIsNeverAnAlert() {
        // Home renders checkSync() only, where Webhook/Tts are Pending placeholders.
        StatusRow.Kind.values().forEach { kind ->
            assertFalse("$kind pending must not alert", HomeAlerts.isAlert(kind, StatusRow.State.Pending))
        }
    }

    @Test fun lockScreenLaunchRedAlerts() {
        // The v1.3.35 motivating case: USE_FULL_SCREEN_INTENT dropped by an APK update.
        assertTrue(HomeAlerts.isAlert(StatusRow.Kind.LockScreenLaunch, StatusRow.State.Red))
    }

    @Test fun permissionsAndAssistantRoleAlert() {
        assertTrue(HomeAlerts.isAlert(StatusRow.Kind.Permissions, StatusRow.State.Red))
        assertTrue(HomeAlerts.isAlert(StatusRow.Kind.DefaultAssistant, StatusRow.State.Red))
    }

    @Test fun optionalGrantsAlertAsYellow() {
        assertTrue(HomeAlerts.isAlert(StatusRow.Kind.MediaCtrl, StatusRow.State.Yellow))
        assertTrue(HomeAlerts.isAlert(StatusRow.Kind.Battery, StatusRow.State.Yellow))
    }

    @Test fun helmetAndInternetStayOffHome() {
        // Both are Yellow/Red during normal use (helmet off, phone indoors). Parking a
        // permanent warning on home teaches the rider to ignore the panel.
        assertFalse(HomeAlerts.isAlert(StatusRow.Kind.Helmet, StatusRow.State.Yellow))
        assertFalse(HomeAlerts.isAlert(StatusRow.Kind.Internet, StatusRow.State.Red))
    }

    @Test fun asyncRowsStayOffHome() {
        assertFalse(HomeAlerts.isAlert(StatusRow.Kind.Webhook, StatusRow.State.Red))
        assertFalse(HomeAlerts.isAlert(StatusRow.Kind.Tts, StatusRow.State.Yellow))
    }

    @Test fun healthyInstallProducesNoAlerts() {
        val rows = StatusRow.Kind.values().map { row(it, StatusRow.State.Green) }
        assertTrue(HomeAlerts.alerts(rows).isEmpty())
    }

    @Test fun alertsKeepCheckerOrder() {
        // checkSync() order is DefaultAssistant, Permissions, Battery, … — the order the
        // rider fixes them in. Filtering must not reshuffle it.
        val rows = listOf(
            row(StatusRow.Kind.DefaultAssistant, StatusRow.State.Red),
            row(StatusRow.Kind.Permissions, StatusRow.State.Red),
            row(StatusRow.Kind.Battery, StatusRow.State.Yellow),
            row(StatusRow.Kind.Helmet, StatusRow.State.Yellow),
            row(StatusRow.Kind.Internet, StatusRow.State.Green),
            row(StatusRow.Kind.LockScreenLaunch, StatusRow.State.Red),
        )
        assertEquals(
            listOf(
                StatusRow.Kind.DefaultAssistant,
                StatusRow.Kind.Permissions,
                StatusRow.Kind.Battery,
                StatusRow.Kind.LockScreenLaunch,
            ),
            HomeAlerts.alerts(rows).map { it.id },
        )
    }
}

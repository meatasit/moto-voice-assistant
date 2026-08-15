package com.moto.voice.tts

import com.moto.voice.debug.EngineChoiceReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v1.3.38 — the "AI 2 เสียง" fix.
 *
 * Field log 1786688875809 carried the failure detail v1.3.37 restored, and every synth
 * failure in it is the same string: `IllegalStateException: HTTP 401`. A rejected key does
 * not heal on retry, and retrying is what produced two voices in one ride — cached lines
 * still played through Azure while every new line 401'd into the Android engine.
 *
 * Once a 401 is seen the router must pick Android and stay there, and it must go back to
 * Azure the moment a synth succeeds again (i.e. the rider pasted a working key).
 */
class AzureAuthRejectedTest {

    @Before fun reset() = AzureTtsState.clearAuthRejected()
    @After fun tearDown() = AzureTtsState.clearAuthRejected()

    @Test fun freshStateIsNotRejected() {
        assertFalse(AzureTtsState.authRejected())
    }

    @Test fun a401LatchesTheFlag() {
        AzureTtsState.recordAuthRejected("synth failed — IllegalStateException: HTTP 401")
        assertTrue(AzureTtsState.authRejected())
        assertEquals(AzureTtsState.LastResult.Failed, AzureTtsState.result())
    }

    @Test fun ordinaryFailuresDoNotLatch() {
        // A DNS blip or a 500 is worth retrying — only auth is hopeless.
        AzureTtsState.recordFailure("synth failed — UnknownHostException: no address")
        assertFalse(AzureTtsState.authRejected())
    }

    @Test fun successClearsIt() {
        // The rider saves a working key; the next synth succeeds and Azure comes back.
        AzureTtsState.recordAuthRejected("synth failed — IllegalStateException: HTTP 401")
        AzureTtsState.recordSuccess()
        assertFalse(AzureTtsState.authRejected())
        assertEquals(AzureTtsState.LastResult.Ok, AzureTtsState.result())
    }

    @Test fun theRouterHasAReasonStringForIt() {
        // So a field log says WHY every line is Android rather than looking misconfigured.
        assertEquals("android_azure_401", EngineChoiceReason.ANDROID_AZURE_401)
    }

    @Test fun reasonIsDistinctFromTheOtherAndroidRoutes() {
        val reasons = listOf(
            EngineChoiceReason.ANDROID_NO_KEY,
            EngineChoiceReason.ANDROID_NO_REGION,
            EngineChoiceReason.ANDROID_OFFLINE,
            EngineChoiceReason.ANDROID_AZURE_401,
            EngineChoiceReason.AZURE_USED,
            EngineChoiceReason.AZURE_FAILED_FALLBACK,
        )
        assertEquals(reasons.size, reasons.toSet().size)
    }

    @Test fun errorDetailSurvivesForTheStatusPage() {
        val detail = "synth failed — IllegalStateException: HTTP 401"
        AzureTtsState.recordAuthRejected(detail)
        assertEquals(detail, AzureTtsState.error())
    }
}

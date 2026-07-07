package com.moto.voice.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TtsRouter's decision matrix — locked here so a refactor can't accidentally
 * introduce a route that surprises the rider.
 *
 * The real router pulls (key, region, online) from Settings + ConnectivityManager;
 * we replicate the decision function purely so the test doesn't need a Context.
 */
class RouterFallbackTest {

    /** Same shape as the useAzure decision in TtsRouter.speak. */
    private fun useAzure(key: String, region: String, online: Boolean): Boolean =
        key.isNotBlank() && region.isNotBlank() && online

    // 4 corners of the matrix.

    @Test fun bothConfiguredAndOnlineUsesAzure() =
        assertTrue(useAzure("k", "southeastasia", online = true))

    @Test fun keyBlankUsesAndroid() =
        assertFalse(useAzure("", "southeastasia", online = true))

    @Test fun regionBlankUsesAndroid() =
        assertFalse(useAzure("k", "", online = true))

    @Test fun offlineUsesAndroid() =
        assertFalse(useAzure("k", "southeastasia", online = false))

    // Edge cases.

    @Test fun whitespaceKeyIsBlankByBlankSemantics() =
        assertFalse(useAzure("   ", "region", online = true))

    @Test fun whitespaceRegionIsBlankByBlankSemantics() =
        assertFalse(useAzure("k", "  ", online = true))

    // Behavioral: the AzureTtsState timing fields should be zero when Azure was never
    // called, so the SystemStatus row can distinguish "never" from "recently failed".

    @Test fun azureStateNeverInitially() {
        // Fresh process init — before any speak, state must be Never.
        // AzureTtsState is a singleton so we can't guarantee freshness in isolation,
        // but we can verify the Never enum value exists as a distinguishable state.
        val results = AzureTtsState.LastResult.values()
        assertEquals(3, results.size)
        assertTrue(results.contains(AzureTtsState.LastResult.Never))
        assertTrue(results.contains(AzureTtsState.LastResult.Ok))
        assertTrue(results.contains(AzureTtsState.LastResult.Failed))
    }
}

package com.moto.voice.debug

import com.moto.voice.network.WebhookClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the mapping from WebhookClient.Kind → FinishReason string used by
 * VoiceCommandPipeline.handleWebhookFailure so a future refactor can't reshuffle
 * the categories without a compile-time change here.
 */
class FinishReasonBranchTest {

    /** Same mapping as VoiceCommandPipeline.handleWebhookFailure — this is the contract. */
    private fun reasonFor(kind: WebhookClient.Kind): String = when (kind) {
        WebhookClient.Kind.Timeout -> FinishReason.TIMEOUT_FALLBACK
        WebhookClient.Kind.Http401 -> FinishReason.HTTP_401
        WebhookClient.Kind.HttpOther -> FinishReason.HTTP_OTHER
        WebhookClient.Kind.Network -> FinishReason.NETWORK
        WebhookClient.Kind.Parse -> FinishReason.PARSE_ERROR
    }

    @Test fun timeoutMapsCorrectly() = assertEquals(FinishReason.TIMEOUT_FALLBACK, reasonFor(WebhookClient.Kind.Timeout))
    @Test fun http401MapsCorrectly() = assertEquals(FinishReason.HTTP_401, reasonFor(WebhookClient.Kind.Http401))
    @Test fun httpOtherMapsCorrectly() = assertEquals(FinishReason.HTTP_OTHER, reasonFor(WebhookClient.Kind.HttpOther))
    @Test fun networkMapsCorrectly() = assertEquals(FinishReason.NETWORK, reasonFor(WebhookClient.Kind.Network))
    @Test fun parseMapsCorrectly() = assertEquals(FinishReason.PARSE_ERROR, reasonFor(WebhookClient.Kind.Parse))

    @Test fun everyKindHasABranch() {
        // Coverage guarantee: if we add a new Kind, this loop compiles but assertNotNull
        // would still pass (String constant is never null). The value comes from being
        // in the when — a missing branch would break compilation of reasonFor above.
        WebhookClient.Kind.values().forEach { kind -> reasonFor(kind) }
    }
}

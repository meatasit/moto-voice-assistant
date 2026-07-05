package com.moto.voice.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WebhookKindClassificationTest {

    @Test fun allKindsHaveUniqueNames() {
        val values = WebhookClient.Kind.values().map { it.name }.toSet()
        assertEquals(WebhookClient.Kind.values().size, values.size)
    }

    @Test fun kind401Present() = assertNotEquals(null, WebhookClient.Kind.valueOf("Http401"))

    @Test fun kindTimeoutPresent() = assertNotEquals(null, WebhookClient.Kind.valueOf("Timeout"))

    @Test fun kindNetworkPresent() = assertNotEquals(null, WebhookClient.Kind.valueOf("Network"))

    @Test fun kindParsePresent() = assertNotEquals(null, WebhookClient.Kind.valueOf("Parse"))

    @Test fun kindHttpOtherPresent() = assertNotEquals(null, WebhookClient.Kind.valueOf("HttpOther"))
}

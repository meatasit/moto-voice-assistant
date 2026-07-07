package com.moto.voice.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AzureSsmlTest {

    // ─── XML escape (spec §2.3) ──────────────────────────────────────────────

    @Test fun escapeAmpersand() = assertEquals("A &amp; B", AzureSsml.escapeXml("A & B"))
    @Test fun escapeLessThan() = assertEquals("A &lt; B", AzureSsml.escapeXml("A < B"))
    @Test fun escapeGreaterThan() = assertEquals("A &gt; B", AzureSsml.escapeXml("A > B"))
    @Test fun escapeDoubleQuote() = assertEquals("say &quot;hi&quot;", AzureSsml.escapeXml("say \"hi\""))
    @Test fun escapeApostrophe() = assertEquals("it&apos;s ok", AzureSsml.escapeXml("it's ok"))

    @Test fun escapeAllTogether() =
        assertEquals("&amp;&lt;&gt;&quot;&apos;", AzureSsml.escapeXml("&<>\"'"))

    @Test fun thaiPassesThrough() {
        // Thai code points aren't XML-special; must be preserved byte-for-byte.
        assertEquals("โทรหาแม่", AzureSsml.escapeXml("โทรหาแม่"))
    }

    @Test fun emptyStringYieldsEmpty() = assertEquals("", AzureSsml.escapeXml(""))

    // ─── Rate mapping ────────────────────────────────────────────────────────

    @Test fun rateOneMapsToZeroPercent() = assertEquals("+0%", AzureSsml.rateAttr(1.0f))
    @Test fun rateOnePointFiveMapsToPlusFifty() = assertEquals("+50%", AzureSsml.rateAttr(1.5f))
    @Test fun ratePointEightMapsToMinusTwenty() = assertEquals("-20%", AzureSsml.rateAttr(0.8f))
    @Test fun rateClampedAtMinusFifty() = assertEquals("-50%", AzureSsml.rateAttr(0.2f))
    @Test fun rateClampedAtPlusHundred() = assertEquals("+100%", AzureSsml.rateAttr(3.0f))

    // ─── Full SSML build ─────────────────────────────────────────────────────

    @Test fun buildIncludesVoiceAndText() {
        val ssml = AzureSsml.build("สวัสดี", "th-TH-PremwadeeNeural", 1.0f)
        assertTrue("expected voice tag: $ssml", ssml.contains("th-TH-PremwadeeNeural"))
        assertTrue("expected text: $ssml", ssml.contains("สวัสดี"))
        assertTrue("expected prosody: $ssml", ssml.contains("prosody rate='+0%'"))
        assertTrue("expected lang: $ssml", ssml.contains("xml:lang='th-TH'"))
    }

    @Test fun buildEscapesText() {
        val ssml = AzureSsml.build("A & B", "th-TH-PremwadeeNeural", 1.0f)
        // Raw ampersand would break XML parse — must be escaped.
        assertFalse("must not contain raw &: $ssml", ssml.contains("A & B"))
        assertTrue("must contain &amp;: $ssml", ssml.contains("A &amp; B"))
    }
}

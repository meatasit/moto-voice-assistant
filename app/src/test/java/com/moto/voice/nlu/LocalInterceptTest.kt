package com.moto.voice.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalInterceptTest {

    @Test fun stopMatchesExact() = assertSame(LocalIntercept.Intercept.Stop, LocalIntercept.match("หยุด"))
    @Test fun stopMatchesPadded() = assertSame(LocalIntercept.Intercept.Stop, LocalIntercept.match(" เอ่อ หยุด "))
    @Test fun stopMatchesPo() = assertSame(LocalIntercept.Intercept.Stop, LocalIntercept.match("พอแล้ว"))
    @Test fun stopMatchesQuiet() = assertSame(LocalIntercept.Intercept.Stop, LocalIntercept.match("เงียบ"))

    @Test fun helpMatches() =
        assertSame(LocalIntercept.Intercept.Help, LocalIntercept.match("ทำอะไรได้บ้าง"))

    @Test fun repeatMatches() =
        assertSame(LocalIntercept.Intercept.RepeatLast, LocalIntercept.match("พูดอีกที"))

    @Test fun callBackMatches() =
        assertSame(LocalIntercept.Intercept.CallBackLast, LocalIntercept.match("โทรกลับ"))

    @Test fun resumeRadioMatchesBareCommand() =
        assertSame(LocalIntercept.Intercept.ResumeLastRadio, LocalIntercept.match("เปิดวิทยุ"))

    @Test fun resumeRadioDoesNotMatchWithStation() {
        // Longer form (with a station) must go to the webhook, not intercept.
        assertSame(LocalIntercept.Intercept.None, LocalIntercept.match("เปิดวิทยุ 91.5"))
    }

    @Test fun callCommandGoesToWebhook() =
        assertSame(LocalIntercept.Intercept.None, LocalIntercept.match("โทรหาสมชาย"))

    @Test fun youtubeGoesToWebhook() =
        assertSame(LocalIntercept.Intercept.None, LocalIntercept.match("เปิด YouTube เพลงสตริง"))

    @Test fun emptyInputReturnsNone() =
        assertSame(LocalIntercept.Intercept.None, LocalIntercept.match(""))

    @Test fun whitespaceOnlyReturnsNone() =
        assertSame(LocalIntercept.Intercept.None, LocalIntercept.match("   \t  "))

    @Test fun normalizeCollapsesWhitespace() =
        assertEquals("หยุด เพลง", LocalIntercept.normalize("  หยุด   เพลง  "))

    @Test fun normalizeLowercasesEnglish() =
        assertEquals("stop please", LocalIntercept.normalize("STOP Please"))

    @Test fun helpTextIsNotBlank() = assertTrue(LocalIntercept.HELP_TEXT.isNotBlank())

    // ── Regression tests for phrase-boundary matching bug (Sprint 2) ────────
    // These caught the "ปิดวิทยุ" substring appearing inside "เปิดวิทยุ".

    @Test fun openRadioDoesNotTriggerStop() =
        assertSame(LocalIntercept.Intercept.ResumeLastRadio, LocalIntercept.match("เปิดวิทยุ"))

    @Test fun openRadioWithFrequencyGoesToWebhook() =
        assertSame(LocalIntercept.Intercept.None, LocalIntercept.match("เปิดวิทยุ 91.5"))

    @Test fun stopMusicMatches() =
        assertSame(LocalIntercept.Intercept.Stop, LocalIntercept.match("ปิดเพลง"))

    @Test fun openMusicDoesNotTriggerStop() =
        assertSame(LocalIntercept.Intercept.None, LocalIntercept.match("เปิดเพลง"))

    @Test fun callBackWithSuffix() =
        assertSame(LocalIntercept.Intercept.CallBackLast, LocalIntercept.match("โทรกลับหน่อย"))
}

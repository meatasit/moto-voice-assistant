package com.moto.voice.bt

import com.moto.voice.nlu.ErrorSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Spec v1.3.8 B4 — the hour → greeting mapping is a pure function on
 * [HelmetGreeter.pickTimeBasedGreetingFor], exposed via the companion so
 * JVM tests don't need a real Context / mocking framework.
 */
class HelmetGreeterTimeTest {

    // ─── Morning: 05:00–10:59 ───────────────────────────────────────────────

    @Test fun fiveAmIsMorning() =
        assertEquals(ErrorSpeech.GREET_MORNING, HelmetGreeter.pickTimeBasedGreetingFor(5))

    @Test fun sevenAmIsMorning() =
        assertEquals(ErrorSpeech.GREET_MORNING, HelmetGreeter.pickTimeBasedGreetingFor(7))

    @Test fun tenAmIsMorning() =
        assertEquals(ErrorSpeech.GREET_MORNING, HelmetGreeter.pickTimeBasedGreetingFor(10))

    // ─── Midday: 11:00–18:59 ────────────────────────────────────────────────

    @Test fun elevenAmIsMidday() =
        assertEquals(ErrorSpeech.GREET_MIDDAY, HelmetGreeter.pickTimeBasedGreetingFor(11))

    @Test fun noonIsMidday() =
        assertEquals(ErrorSpeech.GREET_MIDDAY, HelmetGreeter.pickTimeBasedGreetingFor(12))

    @Test fun sixPmIsMidday() =
        assertEquals(ErrorSpeech.GREET_MIDDAY, HelmetGreeter.pickTimeBasedGreetingFor(18))

    // ─── Evening: 19:00–04:59 (spans midnight) ─────────────────────────────

    @Test fun sevenPmIsEvening() =
        assertEquals(ErrorSpeech.GREET_EVENING, HelmetGreeter.pickTimeBasedGreetingFor(19))

    @Test fun ninePmIsEvening() =
        assertEquals(ErrorSpeech.GREET_EVENING, HelmetGreeter.pickTimeBasedGreetingFor(21))

    @Test fun elevenPmIsEvening() =
        assertEquals(ErrorSpeech.GREET_EVENING, HelmetGreeter.pickTimeBasedGreetingFor(23))

    @Test fun midnightIsEvening() =
        assertEquals(ErrorSpeech.GREET_EVENING, HelmetGreeter.pickTimeBasedGreetingFor(0))

    @Test fun threeAmIsEvening() =
        assertEquals(ErrorSpeech.GREET_EVENING, HelmetGreeter.pickTimeBasedGreetingFor(3))

    @Test fun fourAmIsEvening() =
        assertEquals(ErrorSpeech.GREET_EVENING, HelmetGreeter.pickTimeBasedGreetingFor(4))

    // ─── Contract lock: all three greeting variants distinct ───────────────

    @Test fun threeGreetingsAreDistinct() {
        // If a future refactor collapses two into the same string this test fires —
        // the whole point is to give the rider a time-varied experience.
        assertNotEquals("morning ≠ midday", ErrorSpeech.GREET_MORNING, ErrorSpeech.GREET_MIDDAY)
        assertNotEquals("midday ≠ evening", ErrorSpeech.GREET_MIDDAY, ErrorSpeech.GREET_EVENING)
        assertNotEquals("morning ≠ evening", ErrorSpeech.GREET_MORNING, ErrorSpeech.GREET_EVENING)
    }
}

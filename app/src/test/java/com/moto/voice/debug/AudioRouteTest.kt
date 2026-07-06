package com.moto.voice.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AudioRouteTest {

    @Test fun scoValueIsStable() = assertEquals("sco", AudioRoute.SCO)
    @Test fun phoneValueIsStable() = assertEquals("phone", AudioRoute.PHONE)
    @Test fun distinct() = assertNotEquals(AudioRoute.SCO, AudioRoute.PHONE)

    @Test fun defaultIsNull() {
        // A DebugEntry created before the audio decision must not silently claim SCO.
        val e = DebugEntry()
        assertEquals(null, e.audioRoute)
    }

    @Test fun canBeSet() {
        val e = DebugEntry()
        e.audioRoute = AudioRoute.SCO
        assertEquals("sco", e.audioRoute)
    }

    @Test fun summaryIncludesRoute() {
        val e = DebugEntry(sttFinal = "โทรหาแม่").apply { audioRoute = AudioRoute.SCO }
        assertEquals(true, e.summary().contains("route:sco"))
    }

    @Test fun summaryOmitsRouteWhenUnset() {
        val e = DebugEntry(sttFinal = "โทรหาแม่")
        assertEquals(false, e.summary().contains("route:"))
    }
}

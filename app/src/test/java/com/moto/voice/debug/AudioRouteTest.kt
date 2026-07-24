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

    // ─── v1.3.32 ready-cue routing instrumentation ───────────────────────────

    @Test fun readyEarconRouteDefaultsNull() {
        // A fresh entry must not claim the ready cue landed anywhere before it fired.
        assertEquals(null, DebugEntry().readyEarconRoute)
        assertEquals(null, DebugEntry().scoColdConnect)
    }

    @Test fun summaryShowsReadyCueRoute() {
        val e = DebugEntry(sttFinal = "เปิด youtube").apply { readyEarconRoute = AudioRoute.PHONE }
        assertEquals(true, e.summary().contains("ready→phone"))
    }

    @Test fun summaryFlagsColdConnect() {
        val e = DebugEntry(sttFinal = "เปิด youtube").apply { scoColdConnect = true }
        assertEquals(true, e.summary().contains("sco:cold"))
    }

    @Test fun summaryOmitsColdWhenWarm() {
        val e = DebugEntry(sttFinal = "เปิด youtube").apply { scoColdConnect = false }
        assertEquals(false, e.summary().contains("sco:cold"))
    }
}

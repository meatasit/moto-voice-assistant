package com.moto.voice.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoStateTest {

    @Test fun connectedValueStable() = assertEquals("connected", ScoState.CONNECTED)
    @Test fun failedValueStable() = assertEquals("failed", ScoState.FAILED)
    @Test fun noHeadsetValueStable() = assertEquals("no_headset", ScoState.NO_HEADSET)
    @Test fun noPermissionValueStable() = assertEquals("no_permission", ScoState.NO_PERMISSION)

    @Test fun allDistinct() {
        val values = setOf(
            ScoState.CONNECTED, ScoState.FAILED,
            ScoState.NO_HEADSET, ScoState.NO_PERMISSION,
        )
        assertEquals(4, values.size)
    }

    @Test fun differentFromAudioRoute() {
        // ScoState is more specific than AudioRoute — must not overlap the audioRoute vocab.
        assertNotEquals(ScoState.CONNECTED, AudioRoute.SCO)
        assertNotEquals(ScoState.NO_HEADSET, AudioRoute.PHONE)
    }

    @Test fun defaultDebugEntryHasNullScoState() {
        assertEquals(null, DebugEntry().scoState)
    }

    @Test fun summaryIncludesScoState() {
        val e = DebugEntry(sttFinal = "โทรหาแม่").apply { scoState = ScoState.NO_HEADSET }
        assertTrue(e.summary().contains("sco:no_headset"))
    }

    @Test fun summaryOmitsScoStateWhenNull() {
        val e = DebugEntry(sttFinal = "x")
        assertTrue(!e.summary().contains("sco:"))
    }
}

package com.moto.voice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The CellularCheck implementation itself needs a real Context; these tests lock the
 * enum contract that other code (pipeline, tests, future SystemStatus page) depends on.
 */
class CellularCheckTest {

    @Test fun statusHasFourValues() {
        assertEquals(4, CellularCheck.Status.values().size)
    }

    @Test fun readyDistinctFromNoSim() =
        assertNotEquals(CellularCheck.Status.Ready, CellularCheck.Status.NoSim)

    @Test fun noRadioDistinctFromNoSim() =
        assertNotEquals(CellularCheck.Status.NoRadio, CellularCheck.Status.NoSim)

    @Test fun unknownDistinctFromAll() {
        val u = CellularCheck.Status.Unknown
        assertNotEquals(u, CellularCheck.Status.Ready)
        assertNotEquals(u, CellularCheck.Status.NoSim)
        assertNotEquals(u, CellularCheck.Status.NoRadio)
    }
}

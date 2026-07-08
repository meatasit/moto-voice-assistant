package com.moto.voice.debug

import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sanity checks on the export encoding path. Robolectric-free — we can't call
 * exportToFile() without a Context, but we CAN verify the serialization + byte
 * encoding rules match what field-test bug 3 requires.
 */
class ExportEncodingTest {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Test fun serializedListStartsWithOpenBracket() {
        val json = gson.toJson(emptyList<DebugEntry>())
        val firstByte = json.toByteArray(Charsets.UTF_8).first()
        // 0x5B == '['
        assertEquals("first byte must be '[', got 0x${firstByte.toInt().and(0xFF).toString(16)}",
            0x5B.toByte(), firstByte)
    }

    @Test fun noBomInUtf8Encoding() {
        val json = gson.toJson(listOf(DebugEntry(sttFinal = "hello")))
        val bytes = json.toByteArray(Charsets.UTF_8)
        // UTF-8 BOM = 0xEF 0xBB 0xBF. Must not be present at start.
        val hasBom = bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        assertTrue("Kotlin Charsets.UTF_8 must not emit BOM", !hasBom)
    }

    @Test fun thaiTextRoundTrips() {
        val entry = DebugEntry(sttFinal = "โทรหาแม่")
        val json = gson.toJson(listOf(entry))
        // Should contain the Thai literal, not \uXXXX escapes (Gson default preserves UTF-8).
        assertTrue("Thai literal preserved in JSON: $json", json.contains("โทรหาแม่"))
    }

    @Test fun jsonEndsWithClosingBracket() {
        val json = gson.toJson(listOf(DebugEntry())).trimEnd()
        assertEquals(']', json.last())
    }
}

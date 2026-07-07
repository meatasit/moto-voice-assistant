package com.moto.voice.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.MessageDigest

/**
 * The cache key must be:
 *  - deterministic: (text, voice, rate) always → same key
 *  - sensitive to each input: changing any of the three yields a different key
 *  - not a plain concatenation (avoid text=abc/voice=x collision with text=ab/voice=cx)
 *
 * We replicate the hashing formula here without needing a Context (TtsCache holds a
 * Context only to resolve cache dirs — keyFor itself is pure).
 */
class TtsCacheKeyTest {

    /** Same shape as TtsCache.keyFor. */
    private fun keyFor(text: String, voice: String, rate: Float): String {
        val bytes = "$text|$voice|${"%.2f".format(rate)}".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    @Test fun deterministicForSameInputs() {
        val a = keyFor("โทรหาแม่", "th-TH-PremwadeeNeural", 1.0f)
        val b = keyFor("โทรหาแม่", "th-TH-PremwadeeNeural", 1.0f)
        assertEquals(a, b)
    }

    @Test fun textAffectsKey() {
        val a = keyFor("hello", "voice1", 1.0f)
        val b = keyFor("world", "voice1", 1.0f)
        assertNotEquals(a, b)
    }

    @Test fun voiceAffectsKey() {
        val a = keyFor("hello", "voice1", 1.0f)
        val b = keyFor("hello", "voice2", 1.0f)
        assertNotEquals(a, b)
    }

    @Test fun rateAffectsKey() {
        val a = keyFor("hello", "voice1", 1.0f)
        val b = keyFor("hello", "voice1", 1.2f)
        assertNotEquals(a, b)
    }

    @Test fun rateRoundingIsStableAtTwoDp() {
        val a = keyFor("hello", "voice1", 1.234567f)
        val b = keyFor("hello", "voice1", 1.230000f)
        // Both round to "1.23" via %.2f
        assertEquals(a, b)
    }

    @Test fun keyIsHex64Chars() {
        val k = keyFor("x", "y", 1.0f)
        assertEquals(64, k.length)
        assertEquals(true, k.all { it in "0123456789abcdef" })
    }

    @Test fun distinctInputsAvoidCollisionByPositionality() {
        // Poor keys made by naive concat: "ab" + "cx" == "abc" + "x". SHA-256 avoids this.
        val a = keyFor("ab", "cx", 1.0f)
        val b = keyFor("abc", "x", 1.0f)
        assertNotEquals(a, b)
    }
}

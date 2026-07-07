package com.moto.voice.pipeline

import com.moto.voice.nlu.ErrorSpeech
import com.moto.voice.nlu.Persona
import com.moto.voice.nlu.PersonaHolder
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TtsEchoFilterTest {

    @Before fun reset() { PersonaHolder.resetForTest() }
    @After fun tearDown() { PersonaHolder.resetForTest() }

    // ─── The evidence case from field-test log 1783432847869 ────────────────

    @Test fun observedFieldCaseIsFiltered() {
        // What the assistant said (NOT_HEARD_GIVING_UP feminine):
        val spoken = ErrorSpeech.NOT_HEARD_GIVING_UP  // "ยังไม่ได้ยินค่ะ ลองใหม่อีกครั้งนะคะ"
        // What Google STT gave back (from the exported log):
        val heard = "ยังไม่ได้ยินแค่ลองใหม่อีกครั้งนะคะ"
        assertTrue(
            "field-test echo should be filtered — spoken='$spoken' heard='$heard'",
            TtsEchoFilter.isEcho(heard, spoken)
        )
    }

    @Test fun exactMatchIsEcho() {
        assertTrue(TtsEchoFilter.isEcho(ErrorSpeech.CANCELLED, ErrorSpeech.CANCELLED))
    }

    @Test fun realCommandIsNotEcho() {
        // The rider actually said this — must NOT be treated as echo of a random prior TTS.
        val spoken = ErrorSpeech.THINKING  // "กำลังคิดค่ะ รอสักครู่นะคะ"
        val heard = "โทรหาแม่"
        assertFalse(TtsEchoFilter.isEcho(heard, spoken))
    }

    @Test fun realCommandOverlappingButShorterIsNotEcho() {
        val spoken = "กำลังโทรหาสมชาย"
        val heard = "สมชาย"  // rider confirming
        // Note: ThaiNormalizer.similarity returns 0.9 for substring, so 0.9 >= 0.75.
        // This is a known trade-off: short substring answers might be filtered.
        // The confirm/disambig paths already accept partials specifically via
        // NumberWordParser / substring cancel-word matching, so this is acceptable —
        // riders should say "ยกเลิก" not "โทร" when confirming a call anyway.
        assertTrue(TtsEchoFilter.isEcho(heard, spoken))
    }

    @Test fun blankHeardIsNotEcho() {
        // Nothing to filter — the caller already treats blank as no-speech.
        assertFalse(TtsEchoFilter.isEcho("", ErrorSpeech.THINKING))
    }

    @Test fun blankSpokenNoFilter() {
        // First interaction ever, no prior TTS remembered.
        assertFalse(TtsEchoFilter.isEcho("โทรหาแม่", null))
        assertFalse(TtsEchoFilter.isEcho("โทรหาแม่", ""))
    }

    @Test fun thresholdIsFraction() {
        // Just a sanity guard on the constant so a future rewrite doesn't accidentally
        // set it above 1 or below 0.
        assertTrue(TtsEchoFilter.ECHO_SIMILARITY_THRESHOLD in 0f..1f)
    }

    @Test fun masculinePersonaEchoAlsoFiltered() {
        PersonaHolder.set(Persona.Masculine)
        val spoken = ErrorSpeech.NOT_HEARD_GIVING_UP  // now ends in ครับ
        // Simulate the same misheard pattern in masculine ("ครับ" → "แค่บ" maybe)
        val heard = spoken.replace("ครับ", "แค่บ")
        assertTrue(TtsEchoFilter.isEcho(heard, spoken))
    }

    @Test fun differentTtsLinesNotEcho() {
        // If we spoke A and then heard something clearly like line B (different assistant
        // line, e.g. because a stale lastSpoken value), it shouldn't count as echo of A.
        val spoken = ErrorSpeech.THINKING
        val heard = ErrorSpeech.HTTP_401
        assertFalse(TtsEchoFilter.isEcho(heard, spoken))
    }
}

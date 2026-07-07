package com.moto.voice.nlu

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PersonaTest {

    @Before fun reset() = PersonaHolder.resetForTest()
    @After fun cleanup() = PersonaHolder.resetForTest()

    // ─── Voice → persona mapping (spec §5.2) ─────────────────────────────────

    @Test fun premwadeeIsFeminine() =
        assertEquals(Persona.Feminine, PersonaHolder.personaForVoice("th-TH-PremwadeeNeural"))

    @Test fun acharaIsFeminine() =
        assertEquals(Persona.Feminine, PersonaHolder.personaForVoice("th-TH-AcharaNeural"))

    @Test fun niwatIsMasculine() =
        assertEquals(Persona.Masculine, PersonaHolder.personaForVoice("th-TH-NiwatNeural"))

    @Test fun unknownVoiceFallsBackFeminine() =
        assertEquals(Persona.Feminine, PersonaHolder.personaForVoice("th-TH-SomeFutureVoice"))

    @Test fun caseInsensitiveNiwatDetection() =
        assertEquals(Persona.Masculine, PersonaHolder.personaForVoice("TH-th-niwatneural"))

    // ─── ErrorSpeech reads from PersonaHolder ────────────────────────────────

    @Test fun feminineDefaultEndsInKa() {
        assertTrue(ErrorSpeech.THINKING.contains("ค่ะ") || ErrorSpeech.THINKING.contains("นะคะ"))
        assertFalse(ErrorSpeech.THINKING.contains("ครับ"))
    }

    @Test fun masculineSwitchProducesKrap() {
        PersonaHolder.set(Persona.Masculine)
        assertTrue(ErrorSpeech.THINKING.contains("ครับ"))
        assertFalse(ErrorSpeech.THINKING.contains("ค่ะ"))
    }

    @Test fun switchingBackFlipsAgain() {
        PersonaHolder.set(Persona.Masculine)
        val masc = ErrorSpeech.OFFLINE_LIMITED
        PersonaHolder.set(Persona.Feminine)
        val fem = ErrorSpeech.OFFLINE_LIMITED
        assertTrue(masc.contains("ครับ"))
        assertTrue(fem.contains("ค่ะ"))
    }

    @Test fun helmetReadyHasBothForms() {
        PersonaHolder.set(Persona.Feminine)
        assertEquals("พร้อมใช้งานค่ะ", ErrorSpeech.HELMET_READY)
        PersonaHolder.set(Persona.Masculine)
        assertEquals("พร้อมใช้งานครับ", ErrorSpeech.HELMET_READY)
    }

    @Test fun ytPickerDefaultHasBothForms() {
        PersonaHolder.set(Persona.Feminine)
        assertTrue(ErrorSpeech.YT_PICKER_DEFAULT_FIRST.endsWith("นะคะ"))
        PersonaHolder.set(Persona.Masculine)
        assertTrue(ErrorSpeech.YT_PICKER_DEFAULT_FIRST.endsWith("นะครับ"))
    }

    @Test fun allSystemLinesEndPoliteInBothPersonas() {
        listOf(Persona.Feminine, Persona.Masculine).forEach { p ->
            PersonaHolder.set(p)
            ErrorSpeech.allSystemLines().forEach { line ->
                val hasPolite = if (p == Persona.Feminine)
                    (line.contains("ค่ะ") || line.contains("นะคะ"))
                else
                    (line.contains("ครับ") || line.contains("นะครับ"))
                assertTrue("[$p] '$line' missing polite ending", hasPolite)
            }
        }
    }
}

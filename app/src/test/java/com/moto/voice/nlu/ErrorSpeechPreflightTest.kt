package com.moto.voice.nlu

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorSpeechPreflightTest {

    private val preflightLines = listOf(
        ErrorSpeech.PREFLIGHT_NOT_DEFAULT,
        ErrorSpeech.PREFLIGHT_MISSING_MIC,
        ErrorSpeech.PREFLIGHT_MISSING_CONTACTS,
        ErrorSpeech.PREFLIGHT_MISSING_CALL,
    )

    @Test fun allPreflightLinesNonBlank() {
        preflightLines.forEachIndexed { i, s -> assertTrue("line #$i blank", s.isNotBlank()) }
    }

    @Test fun allPreflightLinesEndPolite() {
        preflightLines.forEach { line ->
            assertTrue("expected ค่ะ/นะคะ in '$line'",
                line.contains("ค่ะ") || line.contains("นะคะ"))
        }
    }

    @Test fun allPreflightLinesMentionOpeningApp() {
        // Rider should always know the resolution path — "เปิดแอปเพื่อแก้ไข" is the
        // shared instruction across all four missing-permission cases.
        preflightLines.forEach { line ->
            assertTrue("'$line' doesn't tell rider to open app",
                line.contains("เปิดแอป"))
        }
    }

    @Test fun preflightLinesAreDistinct() {
        val seen = mutableSetOf<String>()
        preflightLines.forEach { assertTrue("duplicate '$it'", seen.add(it)) }
    }

    @Test fun micLineIdentifiesMic() =
        assertTrue(ErrorSpeech.PREFLIGHT_MISSING_MIC.contains("ไมโครโฟน"))

    @Test fun contactsLineIdentifiesContacts() =
        assertTrue(ErrorSpeech.PREFLIGHT_MISSING_CONTACTS.contains("รายชื่อ"))

    @Test fun callLineIdentifiesCall() =
        assertTrue(ErrorSpeech.PREFLIGHT_MISSING_CALL.contains("โทร"))

    @Test fun defaultAssistantLineIdentifiesRole() =
        assertTrue(ErrorSpeech.PREFLIGHT_NOT_DEFAULT.contains("Default Assistant"))

    @Test fun distinctFromExistingErrors() {
        assertNotEquals(ErrorSpeech.PREFLIGHT_MISSING_MIC, ErrorSpeech.NOT_HEARD_RETRY)
        assertNotEquals(ErrorSpeech.PREFLIGHT_NOT_DEFAULT, ErrorSpeech.HTTP_401)
    }
}

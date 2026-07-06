package com.moto.voice.pipeline

import com.moto.voice.nlu.ErrorSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The Issue data class + its Kind mapping to ErrorSpeech is the contract the notification
 * layer relies on. Instance tests here lock the mapping so a future refactor doesn't
 * accidentally cross-wire "mic missing" to "call missing" TTS.
 */
class PreflightIssueTest {

    @Test fun notDefaultIssueMapsToNotDefaultSpeech() {
        val issue = PreflightCheck.Issue(PreflightCheck.Issue.Kind.NotDefaultAssistant, ErrorSpeech.PREFLIGHT_NOT_DEFAULT)
        assertEquals(ErrorSpeech.PREFLIGHT_NOT_DEFAULT, issue.speak)
    }

    @Test fun micIssueMapsToMicSpeech() {
        val issue = PreflightCheck.Issue(PreflightCheck.Issue.Kind.MissingMic, ErrorSpeech.PREFLIGHT_MISSING_MIC)
        assertEquals(ErrorSpeech.PREFLIGHT_MISSING_MIC, issue.speak)
    }

    @Test fun contactsIssueMapsToContactsSpeech() {
        val issue = PreflightCheck.Issue(PreflightCheck.Issue.Kind.MissingContacts, ErrorSpeech.PREFLIGHT_MISSING_CONTACTS)
        assertEquals(ErrorSpeech.PREFLIGHT_MISSING_CONTACTS, issue.speak)
    }

    @Test fun callIssueMapsToCallSpeech() {
        val issue = PreflightCheck.Issue(PreflightCheck.Issue.Kind.MissingCall, ErrorSpeech.PREFLIGHT_MISSING_CALL)
        assertEquals(ErrorSpeech.PREFLIGHT_MISSING_CALL, issue.speak)
    }

    @Test fun kindsHaveFourValues() {
        assertEquals(4, PreflightCheck.Issue.Kind.values().size)
    }

    @Test fun issuesWithDifferentKindsNotEqual() {
        val a = PreflightCheck.Issue(PreflightCheck.Issue.Kind.MissingMic, "x")
        val b = PreflightCheck.Issue(PreflightCheck.Issue.Kind.MissingCall, "x")
        assertNotEquals(a, b)
    }
}

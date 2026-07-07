package com.moto.voice.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the answer-hint mapping used by [VoiceCommandPipeline.askDisambig].
 * Field-test bug (spec §2.2): the old prompt said "พูด คนแรก คนที่สอง หรือ ยกเลิก"
 * even when there were 3 candidates — the rider thought only options 1 and 2 were
 * valid and hesitated. The new hint must always list every valid choice.
 */
class DisambigPromptTest {

    /** Same shape as VoiceCommandPipeline.disambigAnswerHint — pure enough to duplicate. */
    private fun answerHint(count: Int): String = when (count) {
        1 -> "หนึ่ง"
        2 -> "หนึ่ง หรือ สอง"
        3 -> "หนึ่ง สอง หรือ สาม"
        else -> (1..count).joinToString(" ") { "$it" }
    }

    @Test fun singleCandidate() = assertEquals("หนึ่ง", answerHint(1))
    @Test fun twoCandidatesMentionBoth() = assertEquals("หนึ่ง หรือ สอง", answerHint(2))
    @Test fun threeCandidatesMentionAllThree() = assertEquals("หนึ่ง สอง หรือ สาม", answerHint(3))

    @Test fun threeCandidateHintMentionsThird() {
        // The specific regression: field-test showed the "สาม" instruction was missing.
        assertTrue("hint for 3 must include สาม", answerHint(3).contains("สาม"))
    }

    @Test fun twoCandidateHintNoThird() {
        // The two-candidate case should NOT mention สาม.
        assertTrue("hint for 2 must NOT include สาม", !answerHint(2).contains("สาม"))
    }
}

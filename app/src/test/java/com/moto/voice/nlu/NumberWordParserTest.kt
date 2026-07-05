package com.moto.voice.nlu

import com.moto.voice.nlu.NumberWordParser.Choice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberWordParserTest {

    @Test fun ordinalOneAsWord() = assertIndex(0, NumberWordParser.parse("อันแรก", 3))
    @Test fun cardinalOneAsWord() = assertIndex(0, NumberWordParser.parse("หนึ่ง", 3))
    @Test fun ordinalOneLongForm() = assertIndex(0, NumberWordParser.parse("เอาอันที่หนึ่ง", 3))

    @Test fun cardinalTwo() = assertIndex(1, NumberWordParser.parse("สอง", 3))
    @Test fun cardinalTwoPadded() = assertIndex(1, NumberWordParser.parse("เอาอันที่สอง", 3))

    @Test fun cardinalThree() = assertIndex(2, NumberWordParser.parse("สาม", 3))

    @Test fun lastMapsToListEnd() = assertIndex(2, NumberWordParser.parse("อันสุดท้าย", 3))
    @Test fun lastWithTwoItems() = assertIndex(1, NumberWordParser.parse("สุดท้าย", 2))

    @Test fun cancelExplicit() = assertSame(Choice.Cancel, NumberWordParser.parse("ยกเลิก", 3))
    @Test fun cancelAlternate() = assertSame(Choice.Cancel, NumberWordParser.parse("ไม่เอา", 3))
    @Test fun cancelEnglish() = assertSame(Choice.Cancel, NumberWordParser.parse("cancel", 3))

    @Test fun unparseableReturnsNone() =
        assertSame(Choice.None, NumberWordParser.parse("เอาอะไรก็ได้", 3))

    @Test fun emptyReturnsNone() = assertSame(Choice.None, NumberWordParser.parse("", 3))

    @Test fun outOfRangeIsClampedToNone() {
        // Asked for "สาม" but only 2 candidates → we shouldn't return index=2.
        assertSame(Choice.None, NumberWordParser.parse("สาม", 2))
    }

    @Test fun zeroSizeListAlwaysNone() =
        assertSame(Choice.None, NumberWordParser.parse("หนึ่ง", 0))

    @Test fun digitInput1() = assertIndex(0, NumberWordParser.parse("1", 3))
    @Test fun digitInput2() = assertIndex(1, NumberWordParser.parse("2", 3))

    private fun assertIndex(expected: Int, actual: Choice) {
        assertTrue("expected Index($expected), got $actual", actual is Choice.Index)
        assertEquals(expected, (actual as Choice.Index).zeroBased)
    }
}

package com.moto.voice.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

    @Test
    fun parsesThaiCallWithName() {
        val cmd = CommandParser.parse("โทรหา สมชาย")
        assertTrue(cmd is VoiceCommand.Call)
        assertEquals("สมชาย", (cmd as VoiceCommand.Call).name)
    }

    @Test
    fun parsesLongerCallPhrase() {
        val cmd = CommandParser.parse("โทรไปหาคุณ อาทิตย์")
        assertTrue(cmd is VoiceCommand.Call)
        assertEquals("อาทิตย์", (cmd as VoiceCommand.Call).name)
    }

    @Test
    fun parsesEnglishCallCaseInsensitive() {
        val cmd = CommandParser.parse("Call john")
        assertTrue(cmd is VoiceCommand.Call)
        assertEquals("john", (cmd as VoiceCommand.Call).name)
    }

    @Test
    fun trimsWhitespaceAroundCommand() {
        val cmd = CommandParser.parse("   โทรหา   แม่   ")
        assertTrue(cmd is VoiceCommand.Call)
        assertEquals("แม่", (cmd as VoiceCommand.Call).name)
    }

    @Test
    fun returnsNullForNonCommand() {
        assertNull(CommandParser.parse("สวัสดี"))
    }

    @Test
    fun returnsNullForBlankName() {
        assertNull(CommandParser.parse("โทรหา   "))
    }
}

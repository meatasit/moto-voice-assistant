package com.moto.voice.nlu

/**
 * Rule-based Thai command parser — the offline fallback when the LLM webhook is
 * unreachable. Only handles the "call" verb today; add more sealed variants + regex
 * patterns here if you need to grow it.
 */
sealed class VoiceCommand {
    data class Call(val name: String) : VoiceCommand()
}

object CommandParser {

    private val CALL_PATTERNS = listOf(
        Regex("^โทรหาคุณ\\s+(.+)$"),
        Regex("^โทรไปหาคุณ\\s+(.+)$"),
        Regex("^โทรหา\\s+(.+)$"),
        Regex("^โทรไปหา\\s+(.+)$"),
        Regex("^โทร\\s+(.+)$"),
        Regex("^call\\s+(.+)$", RegexOption.IGNORE_CASE),
    )

    fun parse(text: String): VoiceCommand? {
        val trimmed = text.trim()
        for (pattern in CALL_PATTERNS) {
            val match = pattern.find(trimmed) ?: continue
            val name = match.groupValues[1].trim()
            if (name.isNotEmpty()) return VoiceCommand.Call(name)
        }
        return null
    }
}

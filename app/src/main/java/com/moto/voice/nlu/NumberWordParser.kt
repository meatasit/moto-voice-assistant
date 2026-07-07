package com.moto.voice.nlu

/**
 * Extracts a small-ordinal choice ("อันแรก", "หนึ่ง", "สอง", "สาม", "อันสุดท้าย") or a
 * cancel intent from freeform Thai/English STT. Used by disambiguation menus
 * (contact picker, YouTube picker) — max 3 items today, so we only care about 1..3.
 *
 * Returns [Choice.None] when we can't tell — the caller then decides whether to ask
 * again or default (per spec: default to the first option and speak the persona-appropriate
 * ErrorSpeech.YT_PICKER_DEFAULT_FIRST line).
 */
object NumberWordParser {

    sealed class Choice {
        data class Index(val zeroBased: Int) : Choice()
        object Cancel : Choice()
        object None : Choice()
    }

    fun parse(text: String, listSize: Int): Choice {
        if (listSize <= 0) return Choice.None
        val t = text.trim().lowercase()
        if (t.isEmpty()) return Choice.None

        if (CANCEL_WORDS.any { it in t }) return Choice.Cancel

        // "สุดท้าย" first — "อันสุดท้าย" contains "สาม" pattern words after normalization? no,
        // but priority ensures the user's intent to pick last isn't misread.
        if ("สุดท้าย" in t) return Choice.Index(listSize - 1)

        // Ordinal wins over cardinal ("อันที่สอง" > "สอง"), same result but reads more naturally.
        if ("แรก" in t || t == "หนึ่ง" || t.startsWith("หนึ่ง ") || " หนึ่ง" in t ||
            "อันที่หนึ่ง" in t || "อันหนึ่ง" in t ||
            "อันแรก" in t || "ที่หนึ่ง" in t || " 1" in " $t" || t == "1") {
            return Choice.Index(0)
        }
        if ("สอง" in t || " 2" in " $t" || t == "2") return Choice.Index(1).coerceInto(listSize)
        if ("สาม" in t || " 3" in " $t" || t == "3") return Choice.Index(2).coerceInto(listSize)

        return Choice.None
    }

    private fun Choice.Index.coerceInto(listSize: Int): Choice =
        if (zeroBased < listSize) this else Choice.None

    private val CANCEL_WORDS = listOf("ยกเลิก", "ไม่เอา", "เลิก", "cancel")
}

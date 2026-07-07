package com.moto.voice.tts

import kotlin.math.roundToInt

/**
 * Small helpers for building Azure Speech SSML documents. Kept separate from
 * [AzureTtsEngine] so the escaping + rate-mapping logic is trivial to unit-test.
 */
object AzureSsml {

    /**
     * Escape XML special characters. Azure will reject SSML with an unescaped `&`
     * or `<`, and worse, an unescaped `"` inside an attribute breaks the parse without
     * a clear error message. All five characters listed in the XML spec are handled.
     */
    fun escapeXml(text: String): String = buildString(text.length + 16) {
        for (ch in text) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(ch)
        }
    }

    /**
     * Azure SSML `prosody rate` accepts either a percentage like "+15%" / "-20%" OR
     * a named speed. We use the percentage form so it maps linearly from our slider.
     * Slider 1.0f → 0% (default), 1.5f → +50%, 0.8f → -20%. Clamped at [-50, +100].
     *
     * Uses [roundToInt] rather than [toInt] because float subtraction turns 0.8f - 1f
     * into -0.19999999f — truncating would yield -19% instead of the -20% the rider
     * expects when they set the slider to 0.8.
     */
    fun rateAttr(sliderValue: Float): String {
        val percent = ((sliderValue - 1f) * 100f).roundToInt().coerceIn(-50, 100)
        return if (percent >= 0) "+${percent}%" else "${percent}%"
    }

    /**
     * Full SSML doc for a single [voice] speaking [text] at [rate].
     */
    fun build(text: String, voice: String, rate: Float): String {
        val escaped = escapeXml(text)
        val rateAttr = rateAttr(rate)
        return "<speak version='1.0' xml:lang='th-TH'>" +
                "<voice name='${escapeXml(voice)}'>" +
                "<prosody rate='$rateAttr'>$escaped</prosody>" +
                "</voice></speak>"
    }
}

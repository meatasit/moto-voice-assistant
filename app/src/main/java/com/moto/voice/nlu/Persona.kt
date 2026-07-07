package com.moto.voice.nlu

/**
 * Which polite-particle set the assistant uses. Feminine ends every line in ค่ะ/นะคะ;
 * masculine in ครับ/นะครับ. Set from [AppSettings.persona], which is auto-updated
 * when the user changes the Azure voice dropdown in Settings.
 */
enum class Persona { Feminine, Masculine }

/**
 * Process-wide holder for the current [Persona]. Updated at app init from
 * AppSettings, and again every time the user changes voice / gender in Settings.
 *
 * A holder (rather than passing Persona everywhere) is the least-invasive change to
 * the existing hardcoded [ErrorSpeech] call sites — code keeps writing
 * `ErrorSpeech.THINKING` and the correct gendered string comes back automatically.
 */
object PersonaHolder {
    @Volatile
    private var current: Persona = Persona.Feminine

    fun get(): Persona = current

    fun set(persona: Persona) { current = persona }

    /** Test hook: reset to feminine so tests never leak state across each other. */
    internal fun resetForTest() { current = Persona.Feminine }

    /**
     * Voice → persona mapping (spec §5.2):
     *   PremwadeeNeural, AcharaNeural → Feminine
     *   NiwatNeural → Masculine
     * Anything unrecognised falls back to Feminine so the polite ending stays consistent
     * with v1.x baseline if we ever add more voices later without updating this table.
     */
    fun personaForVoice(voice: String): Persona =
        if (voice.contains("Niwat", ignoreCase = true)) Persona.Masculine else Persona.Feminine
}

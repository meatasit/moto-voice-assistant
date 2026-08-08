package com.moto.voice.audio

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.delay

/**
 * Distinct short tones — the rider should be able to tell which listening state
 * we're in without looking at the phone. Redesigned for v1.3.9 per the "audio
 * language" spec:
 *
 *  - [ready] / [answerListen] : RISING two-tone (low→high) = "mic is open, speak now"
 *  - [endInteraction]  : one short low tone = "we stopped listening; press BVRA
 *                         again to talk". Fires on EVERY pipeline exit that
 *                         doesn't start media (media is its own signal).
 *
 * v1.3.36 — rider (7 Aug 2026): *"แยก 2 เสียงให้ชัดเจน ระหว่างเริ่มรอฟังกับหยุดฟัง เพราะตอนนี้
 * ระหว่าง AI พูด ไม่รู้ว่าพูดได้ตอนไหน"*. On a helmet speaker the old cues were too close to
 * tell apart mid-ride: [ready] was ONE beep and [endInteraction] is ONE tone, so "your
 * turn" and "we're done" sounded like the same event; [answerListen] was two beeps at the
 * SAME pitch, which reads as one longer beep through wind noise.
 *
 * The audio language is now reduced to the two states the rider actually acts on:
 *
 *   **speak now**  → two tones going UP. Both mic-opening cues share it, so there is a
 *                    single sound to learn. ([ready] fires after a BVRA press,
 *                    [answerListen] after a question — in both the required action is
 *                    identical: talk.)
 *   **stopped**    → one short low tone, unchanged.
 *
 * Direction (rising) plus count (two vs one) makes the pair distinguishable even when the
 * pitch detail is lost. Deliberately NOT a descending motif for the end tone: v1.3.13
 * shipped that and the rider rejected it ("แย่กว่าเดิม"), reverted in v1.3.14 — the end
 * tone stays exactly what he already accepted.
 *  - [error]           : short low buzz = "that didn't work"
 *  - [cancel]          : three-step descending motif = "we bailed on your request"
 *
 * All tone bodies capped at 300ms per spec §1.4. After firing any earcon that
 * precedes the mic opening, callers must observe [MIC_OPEN_GAP_MS] of silence so
 * the tone's decay tail doesn't bleed into STT.
 *
 * ToneGenerator on STREAM_MUSIC routes through the current output — helmet if SCO
 * is up, otherwise phone speaker. That's what we want: the earcon comes from
 * wherever the STT is about to listen.
 */
object Earcon {

    private const val VOLUME = 80

    /**
     * Silence gap after any earcon before the mic opens, so the tone's decay
     * tail doesn't bleed into STT. Spec v1.3.9 §1.4.
     */
    const val MIC_OPEN_GAP_MS = 150L

    /** Signal: "start speaking now" — new interaction just opened after a BVRA press. */
    suspend fun ready() = startListening()

    /**
     * Signal: "your turn to answer" — the assistant asked a question (confirm,
     * disambig, slot-fill, follow-up window) and the mic is open with no button press
     * needed. Same motif as [ready] since v1.3.36: the rider's required action is the
     * same in both cases, and one sound to recognise beats two he has to tell apart at
     * 90 km/h.
     */
    suspend fun answerListen() = startListening()

    /**
     * The single "mic is open, speak now" motif: two tones going UP.
     *
     * DTMF_1 (697+1209 Hz) → DTMF_9 (852+1477 Hz) — both components rise, so the
     * direction survives a helmet speaker and wind noise. Total body 210ms, inside the
     * spec §1.4 budget, and callers still observe [MIC_OPEN_GAP_MS] before the mic opens.
     */
    private suspend fun startListening() {
        play(ToneGenerator.TONE_DTMF_1, 70, tailMs = 85)
        play(ToneGenerator.TONE_DTMF_9, 90, tailMs = 120)
    }

    /**
     * Signal: "interaction finished, mic is closed." Single low short tone —
     * intentionally NOT the same as the rising pair [startListening] plays, so the rider
     * can tell "assistant is now silent" from "assistant just started listening" without
     * looking. Fires on OK / cancelled / timeout / error / slot_filled / followup
     * / watchdog_reset exits. Skipped when a media action (youtube_play, fm) will
     * play immediately after — the media sound itself signals "we're done".
     *
     * v1.3.14 — reverted from the descending 2-tone motif that shipped in v1.3.13.
     * Rider feedback: "แย่กว่าเดิม". Back to the original single tone.
     *
     * v1.3.37 — rider after riding v1.3.36: *"เสียงยังแยกไม่ออกระหว่างรอกับหยุดรอฟัง"*. Same
     * pitch he already accepted, but stretched to the full 300ms spec budget while the start
     * cue was shortened to two 70/90ms pips. Duration is the discriminator that survives
     * wind noise and a helmet speaker best: the pair is now **two quick pips going up** vs
     * **one long tone**, different in count, direction AND length. Still not a descending
     * motif — that is the shape v1.3.13 was rejected for.
     */
    suspend fun endInteraction() = play(ToneGenerator.TONE_DTMF_2, 300, tailMs = 330)

    /** Signal: "that didn't work." Short low buzz. */
    suspend fun error() = play(ToneGenerator.TONE_PROP_NACK, 200, tailMs = 240)

    /**
     * Signal: "the assistant just cancelled itself" (rider double-tapped BVRA or
     * the 45s watchdog fired). Three-step descending motif so the rider can tell
     * "we bailed on your request" from [endInteraction]'s single tone.
     */
    suspend fun cancel() {
        play(ToneGenerator.TONE_DTMF_5, 80, tailMs = 100)
        play(ToneGenerator.TONE_DTMF_2, 80, tailMs = 100)
        play(ToneGenerator.TONE_DTMF_S, 100, tailMs = 120)
    }

    /**
     * Play a single tone. [durationMs] is the tone length passed to ToneGenerator
     * (≤ 300ms per spec §1.4); [tailMs] is how long we wait before releasing so
     * the tone finishes cleanly.
     */
    private suspend fun play(toneType: Int, durationMs: Int, tailMs: Long) {
        require(durationMs <= 300) { "spec §1.4: earcon body must be ≤ 300ms, got $durationMs" }
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME) }.getOrNull() ?: return
        try {
            tone.startTone(toneType, durationMs)
            delay(tailMs)
        } finally {
            runCatching { tone.release() }
        }
    }
}

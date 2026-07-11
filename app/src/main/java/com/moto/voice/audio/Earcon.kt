package com.moto.voice.audio

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.delay

/**
 * Distinct short tones — the rider should be able to tell which listening state
 * we're in without looking at the phone. Redesigned for v1.3.9 per the "audio
 * language" spec:
 *
 *  - [ready]           : single rising beep = "new interaction starting, speak"
 *  - [answerListen]    : two short beeps ("ติ๊ง-ติ๊ง") = "your turn to reply to
 *                         a question (confirm / picker / slot-fill / follow-up)
 *                         — no button press needed"
 *  - [endInteraction]  : one short low tone = "we stopped listening; press BVRA
 *                         again to talk". Fires on EVERY pipeline exit that
 *                         doesn't start media (media is its own signal).
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

    /** Signal: "start speaking now" — new interaction just opened. Rising short beep. */
    suspend fun ready() = play(ToneGenerator.TONE_PROP_BEEP, 180, tailMs = 200)

    /**
     * Signal: "your turn to answer" — assistant asked a question (confirm,
     * disambig, slot-fill, follow-up window). Two crisp short pings so the rider
     * unambiguously hears it's their move, not a button-required moment.
     * Total body ≤ 250ms.
     */
    suspend fun answerListen() {
        play(ToneGenerator.TONE_PROP_ACK, 100, tailMs = 120)
        play(ToneGenerator.TONE_PROP_ACK, 100, tailMs = 120)
    }

    /**
     * Signal: "interaction finished, mic is closed." Single low short tone —
     * intentionally NOT the same as [ready]'s rising beep, so the rider can tell
     * "assistant is now silent" from "assistant just started listening" without
     * looking. Fires on OK / cancelled / timeout / error / slot_filled / followup
     * / watchdog_reset exits. Skipped when a media action (youtube_play, fm) will
     * play immediately after — the media sound itself signals "we're done".
     */
    suspend fun endInteraction() = play(ToneGenerator.TONE_DTMF_2, 140, tailMs = 160)

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

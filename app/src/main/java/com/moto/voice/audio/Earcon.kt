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

    /**
     * v1.3.38 — full scale. Rider on the v1.3.36/37 tones: *"เสียงสัญญาณ Version แรกๆ ฟังง่าย
     * และดังกว่า"*. Part of that is the tone type (DTMF tones are quieter than the PROP
     * family — see [startListening]) and part is simply this.
     */
    private const val VOLUME = 100

    /**
     * Whether SCO is carrying audio right now. Set by the pipeline; recorded only — nothing
     * reads it yet. See below for why.
     *
     * v1.3.38 routed the tones to `STREAM_VOICE_CALL` while this was true, on the theory that
     * STREAM_MUSIC does not follow the SCO link (field logs kept showing `scoState=connected`
     * alongside `readyEarconRoute=phone`). **That build was unusable with the helmet on:
     * pressing BVRA did nothing at all and no interaction ever completed.**
     *
     * The proof is field log 1786763666528 — the SAME broken build with **no helmet**:
     * `scoState=no_headset` kept this flag false, the tones took the old STREAM_MUSIC path,
     * and every command worked (`nudge→confirmed`, playing). Helmet on = dead, helmet off =
     * fine, and this flag is the only thing that differs between the two.
     *
     * So the stream switch is out. The routing problem it aimed at is real and still open,
     * but the next attempt goes behind a setting that is off by default, so it can be tried
     * while parked instead of discovered mid-ride.
     */
    @Volatile var scoActive: Boolean = false

    /**
     * v1.3.41 — set true only when SCO is live AND the rider has switched
     * `earconOnScoStream` on. See that setting for the full history; the short version is
     * that this is the fix for "the first-press cue goes to the phone speaker", shipped
     * opt-in because the unconditional version broke the app.
     */
    @Volatile var useVoiceCallStream: Boolean = false

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
     * needed.
     *
     * v1.4.0 — back to the original two short ACK beeps ("ตึ่งตึ๊ง", v1.3.9 → v1.3.35).
     * v1.3.36 folded this into [ready]'s single beep on the theory that one sound is easier
     * to learn; the rider noticed the loss and asked for it back: *"Build แรกๆ ยังมีเสียง
     * ตึ่งตึ๊งก่อนจะให้เริ่มพูดอยู่เลย หลังๆ มาเสียงหายไป"*. Two beeps here, one for [ready]
     * — the count now also tells "answer my question" from "new command".
     */
    suspend fun answerListen() {
        play(ToneGenerator.TONE_PROP_ACK, 100, tailMs = 120)
        play(ToneGenerator.TONE_PROP_ACK, 100, tailMs = 120)
    }

    /**
     * The single "mic is open, speak now" cue.
     *
     * v1.3.38 — back to the original v1.3.9 rising [ToneGenerator.TONE_PROP_BEEP], 180ms.
     * The v1.3.36/37 experiment (a rising DTMF pair, then shorter pips) was aimed at making
     * start and stop easier to tell apart, and the rider's verdict after riding it was that
     * it made things worse where it counts: *"เสียงสัญญาณ Version แรกๆ ฟังง่ายและดังกว่า"*.
     * The PROP tones are simply more audible than DTMF through a helmet.
     *
     * Separation is now carried by [endInteraction] instead — a different PROP tone at
     * 300ms against this one at 180ms — plus the routing fix ([scoActive]), which is the
     * real reason the cue was being missed.
     */
    private suspend fun startListening() = play(ToneGenerator.TONE_PROP_BEEP, 180, tailMs = 200)

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
     * v1.3.37 — stretched to the full 300ms spec budget so length carries the difference.
     *
     * v1.3.38 — and moved to [ToneGenerator.TONE_PROP_ACK], the same loud PROP family as the
     * start cue now uses, so "quieter" can't be confused with "different". The pair the rider
     * has to tell apart is a **short bright beep (180ms)** for *speak now* against a **long
     * flatter tone (300ms)** for *stopped*. Still not a descending motif — that is the shape
     * v1.3.13 was rejected for.
     */
    suspend fun endInteraction() = play(ToneGenerator.TONE_PROP_ACK, 300, tailMs = 330)

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
        val stream =
            if (useVoiceCallStream) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
        val tone = runCatching { ToneGenerator(stream, VOLUME) }.getOrNull() ?: return
        try {
            // v1.3.40 — startTone() used to sit in a bare try/finally, so anything it threw
            // propagated out of Earcon.ready() and killed the interaction before a single
            // sound was made. That is how a cosmetic tone change took the whole app down in
            // v1.3.38. A cue is decoration: failing to play one must never cost the rider a
            // command. The delay stays outside the guard so cancellation still works.
            runCatching { tone.startTone(toneType, durationMs) }
            delay(tailMs)
        } finally {
            runCatching { tone.release() }
        }
    }
}

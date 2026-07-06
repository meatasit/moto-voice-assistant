package com.moto.voice.audio

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.delay

/**
 * Three distinct short tones — the rider should be able to tell which state we're in
 * without looking at the phone:
 *  - [Ready]: single high "beep" (พูดได้เลย)
 *  - [End]  : descending two-tone (จบการฟัง / ประมวลผลอยู่)
 *  - [Error]: short low buzz (ไม่สำเร็จ)
 *
 * ToneGenerator on STREAM_MUSIC routes through the current output — which is the
 * helmet if SCO is up, or the phone speaker otherwise. That's what we want: the
 * earcon comes from wherever the STT will listen.
 */
object Earcon {

    private const val VOLUME = 80

    /** Signal: "start speaking now." Short, unambiguous rising beep. */
    suspend fun ready() = play(ToneGenerator.TONE_PROP_BEEP, 180, tailMs = 220)

    /** Signal: "we heard you, processing." Two descending notes. */
    suspend fun end() {
        play(ToneGenerator.TONE_DTMF_5, 90, tailMs = 100)
        play(ToneGenerator.TONE_DTMF_2, 120, tailMs = 140)
    }

    /** Signal: "that didn't work." Short low buzz. */
    suspend fun error() = play(ToneGenerator.TONE_PROP_NACK, 200, tailMs = 240)

    /**
     * Signal: "the assistant just cancelled itself" (rider double-tapped BVRA).
     * Deliberately different from [end]: a three-step descending motif so the rider
     * can tell "we heard you and are processing" from "we bailed out on your request".
     */
    suspend fun cancel() {
        play(ToneGenerator.TONE_DTMF_5, 90, tailMs = 110)
        play(ToneGenerator.TONE_DTMF_2, 100, tailMs = 120)
        play(ToneGenerator.TONE_DTMF_S, 140, tailMs = 160)
    }

    /**
     * Play a single tone. [durationMs] is the tone length passed to ToneGenerator;
     * [tailMs] is how long we wait before releasing so the tone finishes cleanly.
     */
    private suspend fun play(toneType: Int, durationMs: Int, tailMs: Long) {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME) }.getOrNull() ?: return
        try {
            tone.startTone(toneType, durationMs)
            delay(tailMs)
        } finally {
            runCatching { tone.release() }
        }
    }
}

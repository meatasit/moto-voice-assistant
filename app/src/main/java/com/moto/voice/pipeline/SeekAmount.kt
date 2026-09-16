package com.moto.voice.pipeline

import kotlin.math.roundToInt

/**
 * v1.4.4 — what an `action=seek` response is actually asking for.
 *
 * The workflow reuses `frequency` for signed seconds ("บวก=หน้า ลบ=หลัง, 0=สั่งเล่น"). The
 * pipeline used to read `(frequency ?: 0.0).toInt()`, which made a MISSING amount identical
 * to the explicit 0 that means "resume": with no live session that path re-fired the last
 * deep link, restarting the video from the top, while the rider was told "ย้อนกลับให้ค่ะ"
 * (review finding on field log 1789524511710, which already shows the workflow sending
 * `seek` with `frequency=0` for "เปิดคลิปต่อ"). Pure so a JVM test can pin the split.
 */
object SeekAmount {

    sealed class Decision {
        /** No amount at all — ask, do not guess. */
        object Unknown : Decision()
        /** Explicit 0 — the contract's "resume" (routes through play-continue, rule #2). */
        object Resume : Decision()
        /** Signed seconds to seek by. Never 0. */
        data class Seek(val seconds: Int) : Decision()
    }

    fun decide(frequency: Double?): Decision {
        if (frequency == null || frequency.isNaN()) return Decision.Unknown
        val seconds = frequency.roundToInt()
        return if (seconds == 0) Decision.Resume else Decision.Seek(seconds)
    }
}

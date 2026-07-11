package com.moto.voice.nlu

/**
 * Central vocabulary of TTS lines spoken by the pipeline. Every line has TWO variants —
 * feminine (ค่ะ / นะคะ) and masculine (ครับ / นะครับ) — and the getter picks the right
 * one from the current [PersonaHolder].
 *
 * Kept as an `object` with `val get()` properties (not `const val`) so existing call sites
 * like `ErrorSpeech.THINKING` still compile unchanged and produce persona-correct output.
 */
object ErrorSpeech {

    // ─── Webhook progress (§1.2, §1.3) ────────────────────────────────────────
    val THINKING: String get() = pick("กำลังคิดค่ะ รอสักครู่นะคะ", "กำลังคิดครับ รอสักครู่นะครับ")
    val ONE_MORE_MOMENT: String get() = pick("อีกนิดนะคะ", "อีกนิดนะครับ")

    // ─── Webhook failure categories (§1.4, §6) ────────────────────────────────
    val OFFLINE_LIMITED: String get() = pick(
        "โหมดออฟไลน์ค่ะ ทำได้เฉพาะโทรกับหยุดเล่นนะคะ",
        "โหมดออฟไลน์ครับ ทำได้เฉพาะโทรกับหยุดเล่นนะครับ",
    )
    val TIMEOUT_WITH_FALLBACK: String get() = pick(
        "ระบบหลักช้า ทำแบบออฟไลน์ให้ค่ะ",
        "ระบบหลักช้า ทำแบบออฟไลน์ให้ครับ",
    )
    val TIMEOUT_NO_FALLBACK: String get() = pick(
        "ระบบช้ามากตอนนี้ ทำได้เฉพาะโทรกับหยุดเล่นค่ะ",
        "ระบบช้ามากตอนนี้ ทำได้เฉพาะโทรกับหยุดเล่นครับ",
    )
    val HTTP_401: String get() = pick(
        "ระบบปฏิเสธการเชื่อมต่อค่ะ ตรวจสอบโทเค็นในแอปนะคะ",
        "ระบบปฏิเสธการเชื่อมต่อครับ ตรวจสอบโทเค็นในแอปนะครับ",
    )
    val HTTP_OTHER: String get() = pick(
        "เซิร์ฟเวอร์มีปัญหาชั่วคราวค่ะ ลองใหม่อีกครั้งนะคะ",
        "เซิร์ฟเวอร์มีปัญหาชั่วคราวครับ ลองใหม่อีกครั้งนะครับ",
    )

    // ─── Feature-specific errors (§6.4, §6.5, §6.7) ───────────────────────────
    val YOUTUBE_NOT_FOUND: String get() = pick(
        "ค้นหาไม่ได้ชั่วคราวค่ะ ลองพูดชื่อเจาะจงกว่านี้แล้วลองอีกครั้งนะคะ",
        "ค้นหาไม่ได้ชั่วคราวครับ ลองพูดชื่อเจาะจงกว่านี้แล้วลองอีกครั้งนะครับ",
    )
    val FM_STREAM_FAILED: String get() = pick(
        "เปิดสถานีไม่สำเร็จค่ะ สถานีอาจมีปัญหาชั่วคราว",
        "เปิดสถานีไม่สำเร็จครับ สถานีอาจมีปัญหาชั่วคราว",
    )
    val NO_CELL_SIGNAL: String get() = pick(
        "ตอนนี้ไม่มีสัญญาณโทรศัพท์ค่ะ ลองใหม่อีกครั้งนะคะ",
        "ตอนนี้ไม่มีสัญญาณโทรศัพท์ครับ ลองใหม่อีกครั้งนะครับ",
    )

    // ─── STT input problems (§4) ──────────────────────────────────────────────
    val NOT_HEARD_RETRY: String get() = pick("ไม่ได้ยินค่ะ พูดอีกครั้งนะคะ", "ไม่ได้ยินครับ พูดอีกครั้งนะครับ")
    val NOT_HEARD_GIVING_UP: String get() = pick(
        "ยังไม่ได้ยินค่ะ ลองใหม่อีกครั้งนะคะ",
        "ยังไม่ได้ยินครับ ลองใหม่อีกครั้งนะครับ",
    )

    // ─── Barge-in (§3) ────────────────────────────────────────────────────────
    val CANCELLED: String get() = pick("ยกเลิกแล้วค่ะ", "ยกเลิกแล้วครับ")

    // ─── Preflight / self-check (§5.1) — spoken from VoiceCommandService ─────
    val PREFLIGHT_NOT_DEFAULT: String get() = pick(
        "แอปนี้ยังไม่ได้เป็น Default Assistant ค่ะ เปิดแอปเพื่อแก้ไขนะคะ",
        "แอปนี้ยังไม่ได้เป็น Default Assistant ครับ เปิดแอปเพื่อแก้ไขนะครับ",
    )
    val PREFLIGHT_MISSING_MIC: String get() = pick(
        "สิทธิ์ไมโครโฟนหายไปค่ะ เปิดแอปเพื่อแก้ไขนะคะ",
        "สิทธิ์ไมโครโฟนหายไปครับ เปิดแอปเพื่อแก้ไขนะครับ",
    )
    val PREFLIGHT_MISSING_CONTACTS: String get() = pick(
        "สิทธิ์รายชื่อหายไปค่ะ เปิดแอปเพื่อแก้ไขนะคะ",
        "สิทธิ์รายชื่อหายไปครับ เปิดแอปเพื่อแก้ไขนะครับ",
    )
    val PREFLIGHT_MISSING_CALL: String get() = pick(
        "สิทธิ์โทรออกหายไปค่ะ เปิดแอปเพื่อแก้ไขนะคะ",
        "สิทธิ์โทรออกหายไปครับ เปิดแอปเพื่อแก้ไขนะครับ",
    )

    // ─── Interaction utility lines (previously hardcoded) ────────────────────
    /** HelmetGreeter says this the moment the helmet connects. */
    val HELMET_READY: String get() = pick("พร้อมใช้งานค่ะ", "พร้อมใช้งานครับ")

    /** YouTube picker fallback when the rider doesn't pick after 2 rounds — spec §4.1. */
    val YT_PICKER_DEFAULT_FIRST: String get() = pick("เปิดอันแรกให้นะคะ", "เปิดอันแรกให้นะครับ")

    /** YouTube picker second-round retry lead-in. */
    val YT_PICKER_UNCLEAR_PREFIX: String get() = pick("ไม่ชัดค่ะ ลองอีกที ", "ไม่ชัดครับ ลองอีกที ")

    /** Settings "ทดลองฟัง" preview line — synthesized on demand, so intentionally short. */
    val PREVIEW_SAMPLE: String get() = pick(
        "สวัสดีค่ะ ทดสอบความเร็วเสียงพูด",
        "สวัสดีครับ ทดสอบความเร็วเสียงพูด",
    )

    /**
     * Spec v1.3.8 A5 — spoken when the pipeline detects the SAME action + payload
     * within [com.moto.voice.pipeline.DedupeGuard.WINDOW_MS] of the previous execution.
     * Keeps the answer short so the rider isn't punished for a stutter or a helmet
     * button double-press: just a 1-word acknowledgment that we've got it.
     */
    val ACTION_IN_PROGRESS: String get() = pick("กำลังทำอยู่ค่ะ", "กำลังทำอยู่ครับ")

    // ─── Spec v1.3.8 B3 — random pre-action openers ──────────────────────────
    // Prepended to webhook "speak" fields that start with "กำลัง" so the assistant
    // doesn't sound robotically identical every time. Weighted so the "no prefix"
    // path is most common (RandomOpener) and total audio stays ≤ 1s.

    /** "ได้เลยค่ะ " / "ได้เลยครับ " — pre-action acknowledgment, feminine/masculine variant. */
    val OPENER_DAI_LEUY: String get() = pick("ได้เลยค่ะ ", "ได้เลยครับ ")
    /** "จัดให้ค่ะ " / "จัดให้ครับ " — pre-action acknowledgment, casual/warm variant. */
    val OPENER_JAT_HAI: String get() = pick("จัดให้ค่ะ ", "จัดให้ครับ ")

    // ─── Spec v1.3.8 B4 — time-based helmet-connect greetings ────────────────
    // Replace the old flat HELMET_READY. Rider hears one of three based on hour;
    // all ≤ 1.5s and all pre-synthesized so the greet is instant off SCO connect.

    /** 05:00–10:59 wake-up energy. */
    val GREET_MORNING: String get() = pick("อรุณสวัสดิ์ค่ะ", "อรุณสวัสดิ์ครับ")
    /** 11:00–18:59 midday travel. */
    val GREET_MIDDAY: String get() = pick("พร้อมเดินทางแล้วค่ะ", "พร้อมเดินทางแล้วครับ")
    /** 19:00–04:59 evening safety wish. */
    val GREET_EVENING: String get() = pick("ขี่ปลอดภัยนะคะ", "ขี่ปลอดภัยนะครับ")

    // ─── Spec v1.3.8 B5 — media-context intercept responses ──────────────────

    /** After "อันต่อไป" but the videos list has no next entry. */
    val NEXT_VIDEO_EXHAUSTED: String get() = pick(
        "หมดตัวเลือกแล้วค่ะ ลองค้นใหม่นะคะ",
        "หมดตัวเลือกแล้วครับ ลองค้นใหม่นะครับ",
    )
    /** After "เมื่อกี้อะไร" but no media has been opened yet this session. */
    val WHAT_IS_PLAYING_NONE: String get() = pick(
        "ยังไม่ได้เปิดอะไรค่ะ",
        "ยังไม่ได้เปิดอะไรครับ",
    )

    /**
     * Spec v1.3.9 §2.3 — appended to confirm/disambig/slot-fill prompts for the
     * first [com.moto.voice.data.AppSettings.TEACHING_MODE_BUDGET] uses after install.
     * After the budget is spent the hint auto-suppresses so returning riders don't
     * hear it repeatedly. Persona-aware.
     */
    val TEACHING_HINT: String get() = pick(" ตอบหลังเสียงติ๊งนะคะ", " ตอบหลังเสียงติ๊งนะครับ")

    /**
     * v1.3.10 — spoken when slot-fill or another prompt-answer window couldn't
     * even reach Google's speech server (STT ERROR_SERVER_DISCONNECTED / NETWORK /
     * NETWORK_TIMEOUT / SERVER). Field report: the rider said "เปิด YouTube",
     * heard "เปิดอะไรดีคะ", then heard "ยกเลิกแล้ว" — leaving them confused
     * because from the outside it looked like they were told to cancel. This
     * line makes it clear it's a server hiccup, not a rejection.
     */
    val SERVER_UNAVAILABLE: String get() = pick(
        "ระบบไม่ตอบชั่วคราวค่ะ ลองใหม่นะคะ",
        "ระบบไม่ตอบชั่วคราวครับ ลองใหม่นะครับ",
    )

    /** All 21 lines, in a stable order — used by the pre-synthesize cache warmer. */
    fun allSystemLines(): List<String> = listOf(
        THINKING, ONE_MORE_MOMENT,
        OFFLINE_LIMITED, TIMEOUT_WITH_FALLBACK, TIMEOUT_NO_FALLBACK,
        HTTP_401, HTTP_OTHER,
        YOUTUBE_NOT_FOUND, FM_STREAM_FAILED, NO_CELL_SIGNAL,
        NOT_HEARD_RETRY, NOT_HEARD_GIVING_UP,
        CANCELLED,
        PREFLIGHT_NOT_DEFAULT, PREFLIGHT_MISSING_MIC,
        PREFLIGHT_MISSING_CONTACTS, PREFLIGHT_MISSING_CALL,
        HELMET_READY, YT_PICKER_DEFAULT_FIRST, YT_PICKER_UNCLEAR_PREFIX,
        PREVIEW_SAMPLE,
        ACTION_IN_PROGRESS,
        // v1.3.8 additions — RandomOpener prefixes, time-based greetings, media-context lines
        OPENER_DAI_LEUY, OPENER_JAT_HAI,
        GREET_MORNING, GREET_MIDDAY, GREET_EVENING,
        NEXT_VIDEO_EXHAUSTED, WHAT_IS_PLAYING_NONE,
        TEACHING_HINT,
        SERVER_UNAVAILABLE,
    )

    private fun pick(feminine: String, masculine: String): String =
        when (PersonaHolder.get()) {
            Persona.Feminine -> feminine
            Persona.Masculine -> masculine
        }
}

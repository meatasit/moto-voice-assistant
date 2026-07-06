package com.moto.voice.nlu

/**
 * Central vocabulary of TTS lines spoken by the pipeline. Grouped by category so we can
 * grep for "everything the assistant might say" in one place and audit for tone/register
 * consistency. All lines end in ค่ะ / นะคะ (feminine polite) per the requirement doc.
 *
 * Kept as compile-time constants because they're short, immutable, and referenced from
 * many places in the pipeline. Move to strings.xml later if we ever ship an English
 * build, but until then having them here avoids the resource-lookup overhead in a hot
 * code path and makes tests trivial.
 */
object ErrorSpeech {

    // ─── Webhook progress (§1.2, §1.3) ────────────────────────────────────────
    const val THINKING = "กำลังคิดค่ะ รอสักครู่นะคะ"
    const val ONE_MORE_MOMENT = "อีกนิดนะคะ"

    // ─── Webhook failure categories (§1.4, §6) ────────────────────────────────
    const val OFFLINE_LIMITED = "โหมดออฟไลน์ค่ะ ทำได้เฉพาะโทรกับหยุดเล่นนะคะ"
    const val TIMEOUT_WITH_FALLBACK = "ระบบหลักช้า ทำแบบออฟไลน์ให้ค่ะ"
    const val TIMEOUT_NO_FALLBACK = "ระบบช้ามากตอนนี้ ทำได้เฉพาะโทรกับหยุดเล่นค่ะ"
    const val HTTP_401 = "ระบบปฏิเสธการเชื่อมต่อค่ะ ตรวจสอบโทเค็นในแอปนะคะ"
    const val HTTP_OTHER = "เซิร์ฟเวอร์มีปัญหาชั่วคราวค่ะ ลองใหม่อีกครั้งนะคะ"

    // ─── Feature-specific errors (§6.4, §6.5, §6.7) ───────────────────────────
    const val YOUTUBE_NOT_FOUND = "ค้นหาไม่ได้ชั่วคราวค่ะ ลองพูดชื่อเจาะจงกว่านี้แล้วลองอีกครั้งนะคะ"
    const val FM_STREAM_FAILED = "เปิดสถานีไม่สำเร็จค่ะ สถานีอาจมีปัญหาชั่วคราว"
    const val NO_CELL_SIGNAL = "ตอนนี้ไม่มีสัญญาณโทรศัพท์ค่ะ ลองใหม่อีกครั้งนะคะ"

    // ─── STT input problems (§4) ──────────────────────────────────────────────
    const val NOT_HEARD_RETRY = "ไม่ได้ยินค่ะ พูดอีกครั้งนะคะ"
    const val NOT_HEARD_GIVING_UP = "ยังไม่ได้ยินค่ะ ลองใหม่อีกครั้งนะคะ"

    // ─── Barge-in (§3) ────────────────────────────────────────────────────────
    const val CANCELLED = "ยกเลิกแล้วค่ะ"

    // ─── Preflight / self-check (§5.1) — spoken from VoiceCommandService ─────
    const val PREFLIGHT_NOT_DEFAULT = "แอปนี้ยังไม่ได้เป็น Default Assistant ค่ะ เปิดแอปเพื่อแก้ไขนะคะ"
    const val PREFLIGHT_MISSING_MIC = "สิทธิ์ไมโครโฟนหายไปค่ะ เปิดแอปเพื่อแก้ไขนะคะ"
    const val PREFLIGHT_MISSING_CONTACTS = "สิทธิ์รายชื่อหายไปค่ะ เปิดแอปเพื่อแก้ไขนะคะ"
    const val PREFLIGHT_MISSING_CALL = "สิทธิ์โทรออกหายไปค่ะ เปิดแอปเพื่อแก้ไขนะคะ"
}

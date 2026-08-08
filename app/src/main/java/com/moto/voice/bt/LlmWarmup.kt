package com.moto.voice.bt

import com.moto.voice.network.WebhookClient

/**
 * v1.3.37 — warm the local LLM the moment the helmet connects, so the first real command
 * of the ride doesn't pay for a cold model load.
 *
 * Field log 1786178611552, entry 1786163057720: `webhookTimeMs = 26136` with an empty body
 * and `Timeout:timeout`. Ollama was pulling the model into VRAM while the rider sat waiting
 * — he heard nothing useful and the interaction died. The rider's diagnosis was right:
 * *"บางครั้ง LocalLLM ยังไม่ได้ Load ทำให้ Respond ช้า … หรือบางทีไม่ได้เปิด Com LLM ไว้"*.
 *
 * Pinging the webhook on connect fixes the cause rather than reporting it: the ping runs
 * the same n8n → Ollama path a real command does, and the workflow asks Ollama to keep the
 * model resident (`keep_alive: 24h`). By the time the rider presses BVRA, the model is hot.
 *
 * Rider's rule for the announcement (7 Aug 2026): *"อุ่นเครื่องตอนต่อหมวกก็พอ ถ้าต่อหมวกแล้ว
 * LLM ไม่ทำงานให้รายงาน"* — silent on success, speak only when something is actually wrong.
 * Every decision here is pure so it can be unit-tested without a radio or a network.
 */
object LlmWarmup {

    /** Text sent as the warm-up command. Same probe [com.moto.voice.pipeline.SystemStatusChecker] uses. */
    const val PING_TEXT = "ทดสอบระบบ"

    /**
     * Timeout for the warm-up call, in seconds. Much longer than the pipeline's own budget:
     * a cold model load was measured at 26s and the whole point is to absorb that wait HERE,
     * off the critical path, instead of making the rider sit through it mid-ride.
     */
    const val PING_TIMEOUT_SEC = 45

    /**
     * Don't re-ping on every reconnect. A helmet that drops and re-pairs at a traffic light
     * would otherwise fire a model load each time; the model stays resident far longer than
     * this anyway.
     */
    const val REWARM_AFTER_MS = 30 * 60 * 1000L

    /** What the ping told us about the far end. */
    enum class Outcome {
        /** Reached n8n and got a well-formed reply — the model is hot. Say nothing. */
        Warm,

        /** Reached n8n but it didn't answer in time — almost always the model still loading. */
        Loading,

        /** Couldn't reach the server at all: no data, or the LLM box is off. */
        Unreachable,

        /** Server rejected our token. */
        Rejected,

        /** Reached it, got something we couldn't use. */
        Broken,

        /** No webhook configured — nothing to warm, nothing to complain about. */
        NotConfigured,
    }

    /**
     * Classify a finished ping. Takes the enum rather than the [WebhookClient.Result] so the
     * test doesn't have to build a `WebhookResponse`.
     *
     * @param timedOutLocally true when our own `withTimeoutOrNull` fired (no Result at all).
     */
    fun outcomeFor(
        configured: Boolean,
        success: Boolean,
        kind: WebhookClient.Kind?,
        timedOutLocally: Boolean = false,
    ): Outcome = when {
        !configured -> Outcome.NotConfigured
        timedOutLocally -> Outcome.Loading
        success -> Outcome.Warm
        kind == WebhookClient.Kind.Timeout -> Outcome.Loading
        kind == WebhookClient.Kind.Network -> Outcome.Unreachable
        kind == WebhookClient.Kind.Http401 -> Outcome.Rejected
        else -> Outcome.Broken
    }

    /**
     * What to say, or null to stay quiet. Only [Outcome.Warm] and [Outcome.NotConfigured]
     * are silent — everything else is a problem the rider wants to know about BEFORE he
     * pulls away, not after a command falls on the floor.
     *
     * Deliberately short: this lands right after the connect greeting, in a helmet.
     */
    fun lineFor(outcome: Outcome): String? = when (outcome) {
        Outcome.Warm, Outcome.NotConfigured -> null
        Outcome.Loading -> "ระบบ AI กำลังโหลดอยู่ รออีกสักครู่นะคะ"
        Outcome.Unreachable -> "ต่อระบบ AI ไม่ได้ค่ะ เช็คเน็ตหรือเครื่องที่บ้านนะคะ"
        Outcome.Rejected -> "ระบบ AI ไม่รับการเชื่อมต่อค่ะ ลองดู token ในตั้งค่านะคะ"
        Outcome.Broken -> "ระบบ AI ตอบผิดปกติค่ะ"
    }

    /**
     * Whether to ping now. Only a [Outcome.Warm] result starts the cooldown — if the last
     * attempt found a problem we retry on the next connect, because the rider may well have
     * just gone and switched the LLM box on.
     */
    fun shouldWarm(nowMs: Long, lastWarmOkAtMs: Long?): Boolean =
        lastWarmOkAtMs == null || nowMs - lastWarmOkAtMs >= REWARM_AFTER_MS
}

# v1.3.32 — cold-start ready cue + Bug-2 diagnosis

Field log: `moto_voice_debug_1784863894811.json` (rider reported 2 bugs).

## Bug 1 — first-press ready cue inaudible → FIXED (awaiting rider validation)

**Evidence (rider's own voice, captured as STT in the log):**
- ts `1784805149188`: "ปลั๊กอันที่ 1 คือกดปุ่มครั้งแรกแล้วไม่มีเสียงสัญญาณให้ฟัง ก็เลยไม่รู้ว่าจะต้องพูด"
- ts `1784805183257`: "หมายถึงตอนกดปุ่มแยกโปรแกรม Moto Voice ครั้งแรก" → cold-start / first press only.

**Mechanism (code, not yet log-proven):** `VoiceCommandPipeline` plays `Earcon.ready()`
right after `connectSco()`. The API 31+ path (`BluetoothAudioRouter`) used a fixed
`SCO_SETTLE_MS = 300ms` after `setCommunicationDevice`. On a cold BT stack the SCO link
isn't carrying audio yet at 300ms (or the SCO comm-device isn't even enumerated on the
first press), so the ready tone leaks to the phone speaker / the switchover gap — the
helmeted rider hears nothing. Warm presses: 300ms is enough, cue audible.

⚠️ The earcon path was **uninstrumented** — the log could only show the rider *reporting*
the symptom, never where the tone went. So this release ships the fix **and** the proof.

**Fix (`BluetoothAudioRouter.connectModern`):**
1. Cold retry — if `setCommunicationDevice` fails on the first (cold) connect, retry once
   after `COLD_RETRY_MS = 250ms` before falling back to the phone mic.
2. Cold settle — first connect since process start uses `SCO_COLD_SETTLE_MS = 800ms`;
   subsequent connects keep the warm 300ms. `everConnectedThisProcess` is companion-static
   so it survives per-interaction router instances.

**Instrumentation (proves it in the next field log):**
- `DebugEntry.readyEarconRoute` — audio route the instant before `Earcon.ready()` fires
  (`sco` = helmet, `phone` = leaked to speaker).
- `DebugEntry.scoColdConnect` — was this interaction's SCO bring-up the cold path.
- summary(): `ready→sco|phone` and `sco:cold`.

**What the next field log should show:** first press → `scoColdConnect=true` **and**
`readyEarconRoute=sco`. If it shows `ready→phone` on the cold connect, the residual cause
is `setCommunicationDevice` still failing first-enumeration (tune `COLD_RETRY_MS` /
add a second retry). `SCO_COLD_SETTLE_MS=800` is a first guess — tune if the first-press
beep latency feels bad.

## Bug 2 — "เครื่อง AI" (home brain) off → YouTube fails, calls work → n8n-side, NOT app

**Evidence (morning window, ascending time):**
- `...269659`→`...459406`: clear "เปิดรายการเรื่องเล่าเช้านี้" ×5 → every one returned
  `Success` `action:"none"` "ไม่แน่ใจว่าต้องการอะไร", `webhookTimeMs ~3.5s`
  (**webhook DID respond — HTTP 200**).
- `...481791`: "โทรหารายการโปรดที่ 1" → `intercepted: CallFavorite` ✅ (never touches the
  webhook → this is why calls worked: "สั่งโทรออกได้ อันนี้ดี").
- ~10 min gap (rider phoned home to boot the machine) → `...074126` same phrase →
  `youtube_play` succeeds but `webhookTimeMs 12–16s` (cold LLM warming up).

**Root cause:** the n8n webhook stayed UP the whole time and returned its generic fallback
`action:none`. The thing that was OFF is the LLM/brain behind n8n. From the app's side this
is an indistinguishable `Result.Success(action=none)` — the honest-error paths in
`handleWebhookFailure` (timeout/network/HTTP) never fire because there is no HTTP failure.

**Fix is n8n-side (rider owns):** when the LLM/agent node fails, n8n should emit a distinct
signal (e.g. `{"action":"backend_down"}` or `"_llm_error":true`) instead of the generic
`none`. Then a small app change maps it to an honest line
("ตอนนี้เครื่องที่บ้านยังไม่พร้อม ลองใหม่นะคะ"). **No app change shipped for Bug 2** —
the app cannot tell this morning's case apart until n8n distinguishes it.

## Acceptance (rider — Claude has no device/build access)

Media/audio change → run ACCEPTANCE.md A–G on the S24 Ultra + Vimoto V11X, **two clean
rounds**. Critical check: **cold-start first press** — force-stop the app, press BVRA once,
confirm the ready beep is audible in the helmet, and check the exported log shows
`scoColdConnect=true` + `readyEarconRoute=sco` on that first entry.

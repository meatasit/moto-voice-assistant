# v1.4.0 — Cloud AI brain by default, Azure removed, product UI

Branch: `feat-cloud-llm-redesign-v1.4.0` · versionCode 49 · versionName 1.4.0

Bundles the round the rider queued ("เอาไปรวมไว้กับสิ่งที่จะแก้รอบหน้า") plus the two requests
from 15 Sep 2026: *"รื้อทำใหม่ … เพิ่ม Option ให้มี Toggle เลือกได้ระหว่าง Local LLM หรือ Api Key …
Default เป็น Api key ก่อน เพื่อให้มันนิ่งๆ"* and *"ทำหน้า UI ใหม่ด้วยให้ดูเป็น Product ที่ขายได้จริง"*.

## Why — field log `moto_voice_debug_1789440952407.json` (15 Sep 2026)

Five consecutive "เปิดรายการเรื่องเล่าเช้านี้"-class commands, chronologically:

| ts | webhookTimeMs | result |
|---|---|---|
| 1789435563959 | **30 681** | `action:none` — n8n's own Ollama timeout, rider told "ไม่แน่ใจ" |
| 1789435670403 | **15 350** | client `Timeout:timeout` → `timeout_fallback` |
| 1789435882598 | 4 086 | ok |
| 1789435928538 | 9 309 | ok |
| 1789436173498 | 1 632 | ok — model finally resident |

That is a cold `scb10x/typhoon2.5-qwen3-4b` load into a GPU that the OhMyDaysTools bot also
lives on (12 GB, see memory notes). The rider's diagnosis — *"GPU ไม่ว่าง หรือมัน start ช้า
ต้อง Load Model AI ก่อน"* — is exactly what the numbers show. Not a bug in the app; a
dependency the app cannot control. So the fix is to stop depending on it by default.

Also in that log, unchanged and **not** addressed here (no new evidence):

* 4× `nudge→launchBlocked(noSession)` with `mediaCtrlPkgMiss=asuk.com.android.app` — a new
  package holding a session while YouTube never registered one within the poll window.
  Unknown app; waiting for a log with the screen unlocked to compare.
* `nudge→refireSwitch(clearTask)` **did** fire on the locked กรรมกรข่าว switch and landed —
  so the v1.3.42 App Link did not remove the need for the re-fire. `CLEAR_TASK` stays.

## What changed

### 1. n8n `Javis Voice Intent` v3.8 (published)

* `Parse Request` reads `body.llm` → `provider` = `"local"` or `"api"` (default **api**, so
  build-108 already benefits without an update).
* New `Use Local LLM` IF → `Call Ollama` (unchanged) **or** `Build OpenAI Body` →
  `Call OpenAI` (`gpt-5.4-mini`, `response_format: json_object`, `reasoning_effort: low`,
  credential "OpenAI account" — the same one the shop chatbot uses; the key never leaves n8n).
* `Extract Intent` accepts both reply shapes and stamps `_llm` + `_model` into the response,
  so every app field log now says which brain answered.
* Live test through the real webhook, UTF-8 bodies: กรรมกรข่าว → today's live in 1.6 s,
  เพลงบรรเลงสากล → `youtube_play` 2.7 s, โทรหาแม่ → `call`/แม่ 2.9 s. All `_model:
  gpt-5.4-mini-2026-03-17`.

### 2. App — brain selector

* `AppSettings.llmProvider` (`"api"` | `"local"`, default api), sent as `llm` in the webhook
  body by every `WebhookClient` call site; logged as `DebugEntry.llmProvider`; in the backup
  schema as optional `llm_provider`.
* Settings → "สมอง AI": segmented Cloud AI / Local LLM with a one-line hint each. The old
  "โหมด LLM" switch is still there as "ใช้สมอง AI" (off = rule-based only).
* Helmet warm-up still pings on connect for both providers (rider's rule: report when the
  brain is not working). A timeout on the cloud brain now says "ตอบช้าผิดปกติ" instead of
  "กำลังโหลด", which only a local model can be doing.

### 3. Azure Neural TTS removed

Rider: *"เลิกใช้ Azure ไปเลยก็ได้"* (subscription disabled, every synth HTTP 401 since log
1786688875809). Deleted `AzureTtsEngine`, `AzureSsml`, `AzureTtsState`, `CacheWarmer`,
`TtsCache` and their tests; `TtsRouter` is Android-only; Settings card and SystemStatus row
gone; the dead key is purged from the encrypted store on first launch. This removes the
mechanism behind two chased bugs — *two voices* and *two sentences overlapping* — rather than
their workarounds. `EngineChoiceReason` keeps the historic constants so old exports still grep.

### 4. Earcon — "ตึ่งตึ๊ง" is back

`Earcon.answerListen()` is again two 100 ms `TONE_PROP_ACK` beeps (as in v1.3.9–v1.3.35).
`ready()` stays the single 180 ms `TONE_PROP_BEEP`. Rider noticed the loss and asked.

### 5. UI — product pass

One dark "night ride" theme (Material 3, amber accent), toolbars with back navigation on every
sub-screen (there were none), hero card on Home with a coloured readiness dot and three chips
(brain / helmet / online), card-based Settings in five sections, new launcher icon. Every
existing view id is preserved; behaviour of alerts, history and replay is unchanged.

## Acceptance — awaiting rider validation

Scoped per the rider's earlier agreement (not the full A–I run):

1. **Brain**: with Settings on Cloud AI, three commands in a row after the PC has been idle —
   every `webhookTimeMs` should be under ~4 s and `webhookResponse` should carry
   `"_llm":"api"`. Then flip to Local LLM, one command: `"_llm":"local"`.
2. **Voice**: one voice only, all ride. No line ever plays on top of another. Every entry
   `engineChoiceReason: "android"`.
3. **Cue**: after "ไม่ได้ยินค่ะ พูดอีกครั้งนะคะ" and after a call-confirm question you hear
   **two** short beeps; after a BVRA press you hear **one**.
4. **UI**: home shows the dot green with the three chips; each sub-screen has a back arrow.

Rider validates — Claude cannot run acceptance (no device build env in the sandbox).

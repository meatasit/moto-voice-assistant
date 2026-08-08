# v1.3.37 — warm the LLM on connect, and finish what v1.3.36 started

Branch: `feat-llm-warmup-v1.3.37` · versionCode 43 · versionName 1.3.37

Field log `moto_voice_debug_1786178611552` — the first log of v1.3.36 in the field. Two of
its four fixes worked, one was too impatient to notice it had worked, and one I broke.

## 1. LLM warm-up on helmet connect (new)

Entry 1786163057720: `webhookTimeMs = 26136`, empty body, `Timeout:timeout`. Ollama was
pulling the model into VRAM while the rider waited 26 seconds for nothing. Rider's read was
exactly right — *"บางครั้ง LocalLLM ยังไม่ได้ Load ทำให้ Respond ช้า … หรือบางทีไม่ได้เปิด Com
LLM ไว้"*.

`HelmetGreeter` now fires a warm-up command (`ทดสอบระบบ`, the same probe `SystemStatusChecker`
uses) the moment the helmet connects. It runs the real n8n → Ollama path, and the workflow
already asks Ollama to keep the model resident (`keep_alive: 24h`), so by the time BVRA is
pressed the model is hot. The ping starts **before** the greeting is spoken, so the load
happens while "อรุณสวัสดิ์ค่ะ" is playing — the wait is absorbed by audio the rider wanted.

Announcement follows the rider's rule (*"ถ้าต่อหมวกแล้ว LLM ไม่ทำงานให้รายงาน"*): silent when
it works, one short line when it doesn't.

| outcome | spoken |
| --- | --- |
| reached, answered | — (silent) |
| timeout / still loading | ระบบ AI กำลังโหลดอยู่ รออีกสักครู่นะคะ |
| can't reach the server | ต่อระบบ AI ไม่ได้ค่ะ เช็คเน็ตหรือเครื่องที่บ้านนะคะ |
| 401 | ระบบ AI ไม่รับการเชื่อมต่อค่ะ ลองดู token ในตั้งค่านะคะ |
| anything else | ระบบ AI ตอบผิดปกติค่ะ |
| no webhook configured | — (nothing to warm) |

45s ping budget (the observed cold load is 26s — a shorter budget would report "loading" on
every connect). 30-minute cooldown so a helmet that re-pairs at a traffic light doesn't fire
a model load each time; **only a success starts the cooldown**, so a failed ping is retried on
the next connect — the rider may have just walked over and switched the box on.

## 2. CLEAR_TASK worked; we declared it failed 9 seconds too early

Entry 1786164662718 ran `nudge→refireSwitch(clearTask)` and ended `stillPrior`. But the next
interaction, 57s later, found YouTube playing `-CXDKsZY80I` — **the exact video that
"failed"**. Tearing YouTube's task down and starting it again is a full cold start, and
v1.3.36 shipped the escalation while still judging it on the warm clock.

The poll window now extends to a cold start's worth of time from the moment the CLEAR_TASK
re-fire goes out.

## 3. The Azure failure detail I added in v1.3.36 — I deleted it in the same release

Every `azure_failed_fallback` entry in this log has **no `azureError` at all**. v1.3.36 made
`markDebug` assign the field unconditionally to keep it consistent with `engineChoiceReason`
— and the Android-fallback success that follows a failure writes `error = null`, wiping the
reason the detail existed to capture. I traded away the data to tidy the pairing.

Now cleared once per `speak()` instead: a later speak can't inherit an older error, and the
failure that belongs to *this* speak survives into the log. So the two-voices bug is still
uninvestigated — this release is the one that actually collects the evidence.

## 4. Earcons

**Route.** v1.3.36 raised the SCO-route poll budget for cold connects only, on the evidence
that warm presses always showed `readyEarconRoute=sco`. This log disproves it: three of ten
entries are `phone` with `scoColdConnect=false`. One budget for every press now, 2s. Waiting
is free when the route settles early — the poll returns the instant it flips.

**Contrast.** Rider after riding v1.3.36: *"เสียงยังแยกไม่ออกระหว่างรอกับหยุดรอฟัง"*. Pitch
direction alone wasn't enough, so duration now carries it:

| | before | now |
| --- | --- | --- |
| speak now | 90 + 120ms rising pair | **70 + 90ms** — two quick pips, rising |
| stopped | 140ms single | **300ms** single, same pitch |

Two quick pips versus one long tone: different in count, direction and length. Still not a
descending motif — that is the shape v1.3.13 was rejected for.

**Re-listen window.** *"บางที AI บอกให้พูดอีกครั้ง แต่พอ AI พูดจบก็หยุดฟังเลย"*. The retry after
"ไม่ได้ยินเลย พูดอีกที" reused the 3s default, counted from the end of the prompt. Entry
1786169082147 shows `sttRetryCount=1` with `sttTimeMs=4706` for both listens combined. The
re-listen now gets 6s.

## Still open — not fixed here

**The OS is refusing the full-screen intent.** Entries 1786169115949 / 1786169158782 /
1786169245688: `launch→fullScreenIntent` in the ops but `fsiTrampolineRan=false` and
`fsiTrampolineLaunchOk=false` — the notification posted, the OS never fired it, the
trampoline never ran, YouTube never opened. The permission is still granted (a revoked one
logs `no_fsi_permission`). Same shape as the v1.3.29 stale-notification-id bug but 30–90s
apart, not the rapid repeat that fix addressed, so the cause is something else and I don't
have it yet. v1.3.36's line change does at least make the rider's advice correct here —
locked with no FSI honored is the one case where "ปลดล็อคก่อน" is true.

## Tests

`LlmWarmupTest` (13) — classification of every failure kind, silent-on-healthy, every
problem outcome speaks, lines are mutually distinct, cooldown behaviour, ping budget clears
the observed cold-load time.

## Acceptance — awaiting rider validation

1. **Helmet connect with the LLM box OFF** → expect "ต่อระบบ AI ไม่ได้ค่ะ…" after the greeting.
2. **Helmet connect with it ON but cold** → silent (or "กำลังโหลด" if it exceeds 45s), and the
   first real command should answer fast instead of timing out.
3. **Healthy reconnect within 30 min** → nothing spoken, no second ping.
4. **C-switch** — clip A locked, ask for B/C/D. `refireSwitch(clearTask)` should now be
   followed by `nudge→confirmed` rather than `launchBlocked(stillPrior)`.
5. **By ear** — two quick rising pips vs one long tone.
6. **Say nothing after BVRA** → "ไม่ได้ยินเลย พูดอีกที" → you now have 6s to answer.
7. Send the log: `azureError` should be populated again on any `azure_failed_fallback`, and
   that string is what the two-voices fix will be built on.

# v1.4.8 — the paused-switch bug, and the follow-up window measured at last

Branch: `fix-paused-switch-followup-v1.4.8` · versionCode 57 · versionName 1.4.8 · **awaiting rider validation**

Field log 1789697284287, `appVersion: "1.4.7 (56)"` on all nine entries — **the right build, at
last**, so everything below is real. Rider:

> เปิดครั้งแรกปกติ, เปลี่ยนรายการปกติ … ผมไปกดปุ่ม Play/Pause เพื่อให้หยุดชั่วคราว แล้วสั่งเปลี่ยนรายการ
> ระบบแจ้งว่าไม่สามารถเปิด… หลังจากนั้นเปิด YouTube ไม่ได้เลย กด Play/Pause ก็ไม่มีเสียง
> พอจอดรถ ปลดล็อคหน้าจอก็เจอว่าเปิด YouTube อยู่

He asked whether it was a bug or him. **It is a bug, and v1.4.6 made it worse.**

## Scorecard first

| | change | this log |
|---|---|---|
| ✅ | **v1.4.6 §1** warm switch fires CLEAR_TASK first | 2 warm switches from a PLAYING target, both `sessionSeen(playing);confirmed` on the first delivery, **no `refireSwitch`** — validated |
| ✅ | **v1.4.7 §1** no `refireNoSession` behind a secure keyguard | none fired |
| ✅ | **v1.4.6 §3** STT stamp carries elapsed | `followup_stt 11@8ms` — see below |
| ✅ | v1.4.5 cold launch | entry 1789694164736, wifi, `sessionSeen(none);confirmed` |
| ❌ | **v1.4.6 §1 + v1.3.36 refire** at a PAUSED target | 4 for 4 `launchBlocked(stillPrior)`, session paused → **stopped** — the new bug |
| ❓ | `mediaBrowserConnect` | **never ran** — probe was gated on `noSession`; all four blocks were `stillPrior` |
| ❓ | "รายการถัดไป", "เล่นต่อไป", `spotify_play` | not exercised |

## 1 — CLEAR_TASK at a paused YouTube behind a secure keyguard

Entries 1789695568166, 1789695630821, 1789695788566, 1789695850981, in order:

```
priorTitle: GTA6…   actualTitle: GTA6…   playbackState: stopped   keyguardSecure: true
ops: …;nudge→sessionSeen(paused);nudge→refireSwitch(clearTask);…;nudge→launchBlocked(stillPrior)
ops: …;nudge→sessionSeen(stopped);nudge→refireSwitch(clearTask);…;nudge→launchBlocked(stillPrior)
ops: …;nudge→sessionSeen(stopped);…
ops: …;nudge→sessionSeen(stopped);…
```

Read the first op of each: **paused, then stopped, stopped, stopped.** He pressed the helmet's
Play/Pause, YouTube's session went to `paused` — alive, resumable from the same button. We
then fired CLEAR_TASK at it (twice per attempt, since v1.4.6 added one to the first delivery).
Behind a secure keyguard the restarted task can never resume (v1.4.7's finding), so the switch
could not land — and the teardown turned the session from `paused` into `stopped`. That is
"กด Play/Pause ก็ไม่มีเสียง": we had killed the thing his button resumes. Then every attempt
spoke `SWITCH_NOT_LANDED`, "ลองสั่งเปลี่ยนอีกครั้ง", which he did, four times.

The two warm switches in the same log that *worked* both started from a **PLAYING** target.
That single bit of state is the whole difference, and it was captured nowhere at fire time.

**Fix.** `clearTaskCanLand(targetPlaying, behindSecureKeyguard)` — false only for
paused/stopped AND secure-locked, the one cell proven fatal. It gates the first delivery
(`firstFireNeedsRestart` now takes the prior state) and the stillPrior re-fire (on the live
state). In that cell we never restart, so the paused session survives and his Play/Pause
still works; and since a plain intent to a paused task is a known no-op, we stop waiting at
`refireAt` (~2.5 s) instead of the window end and say the true thing:

> **`SWITCH_NEEDS_UNLOCK`** — "คลิปที่หยุดไว้เปลี่ยนตอนจอล็อคไม่ได้ค่ะ กดเล่นต่อได้เลย หรือปลดล็อคจอแล้วสั่งใหม่นะคะ"

Both of the things that line offers are things that actually work. `stillPrior` from a
PLAYING target keeps `SWITCH_NOT_LANDED` unchanged. `mediaPriorState` is stamped at fire time
so the next log shows this bit without inference.

## 2 — `followup_stt 11@8ms`: the follow-up window has never opened the mic

v1.4.6 §3 asked one question — instant drop, or held the mic for 4 s? — and the answer is
**8 milliseconds.** The recognizer never listened. 12 for 12 across four logs now.

The cause is in the pipeline, not the rider. A new interaction's main listen finds
`recognizer == null` (cleanup() retired the previous one minutes ago) and binds a fresh
service cleanly. The follow-up finds the main listen's recognizer **still alive and bound**,
and `listenOnceRaw` did `destroy()` → `createSpeechRecognizer()` → `startListening()` in one
synchronous block: the new bind raced the old unbind and the service dropped it on the spot.
`listenOnceDetailed`'s 350 ms "settle" was meant to be that gap — but it ran *before* the
destroy, where it gapped nothing. The v1.3.8 A2 "degrading recognizer" branch already does
destroy → wait → create in the right order; this makes every listen do it.

Fix is four lines. **Hypothesis, stated as one**: the ordering is provably different between
the two paths and matches the 8 ms exactly, but the next log's `followup_stt` stamp (or its
absence, with `followupUsed: true` at last) is the proof.

## 3 — the MediaBrowser probe now runs on `stillPrior` too

It was gated on `noSession`; this log had four `stillPrior` blocks and no `noSession`, so
`mediaBrowserConnect` is absent from every entry. Same question either way — can we reach
YouTube's session without its Activity? — so it now runs on both.

## Tests

`WarmSwitchRestartTest` (rewritten: the four `clearTaskCanLand` cells, the paused-locked cell
that must never restart, `isActivelyPlaying`), `LaunchBlockedContractTest` (+2: the paused
line, and that a playing stillPrior is unchanged).

**Claude could not run them** — no Android SDK in the sandbox and Gradle cannot reach Google
Maven; CI runs `testDebugUnitTest`.

## What the next log should show

* `appVersion: "1.4.8 (57)"`.
* Pause from the helmet, then ask for another clip: `mediaPriorState: paused`, **no**
  `refireSwitch`, `nudge→switchNeedsUnlock(paused)` at ~2.5 s, the new line — and then
  **Play/Pause on the helmet still resumes the clip.** That last part is the fix.
* Chat, say nothing: `followupUsed: true` on the next thing said, or a `followup_stt` stamp
  with a number nowhere near 8 ms.
* `mediaBrowserConnect` on any blocked entry — still the field that decides v1.4.9.

Rider validates — Claude cannot run acceptance. ACCEPTANCE.md A–I on the S24 Ultra + Vimoto
V11X, **two clean rounds in a row** before merge, debug-log JSON attached.

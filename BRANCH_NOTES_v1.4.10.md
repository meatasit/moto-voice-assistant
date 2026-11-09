# v1.4.10 — "คลิปที่หยุดไว้" was spoken to a rider who had paused nothing

Branch: `claude/epic-darwin-fjfedd` · versionCode 59 · versionName 1.4.10 · **awaiting rider validation**

Field log 1794208929620, `appVersion: "1.4.9 (58)"` on every entry. Rider:

> วันนี้สั่งงานได้ปกติ มีแปลกๆตรง สั่งเปลี่ยนรายการเป็น "กรรมกรข่าวคุยนอกจอ" ระบบแจ้งว่าทำไม่ได้ตอนจอล็อค
> แต่พอทิ้งไว้ซักพัก สั่งใหม่ ก็เปิดรายการได้ ผมคิดว่าระบบน่าจะแจ้งสาเหตุผิด เพราะวันนี้สั่งงานตอนจอ Lock ตลอดเวลา

He is right.

## Scorecard first

| | this log |
|---|---|
| ✅ cold open behind secure keyguard (entry 1794187133692) | `sessionSeen(none);play#1..3;confirmed` |
| ✅ warm switch from PLAYING (entry 1794187711884) | `sessionSeen(playing);confirmed` — v1.4.6 §1 first-delivery CLEAR_TASK, landed |
| ✅ v1.4.8 §2 follow-up window (entry 1794188471661) | `followup_stt 7@3346ms` — the mic was open for 3.3 s (it died at 8 ms before); he just didn't speak |
| ❌ **warm switch from PLAYING** (entry 1794188420595) | `sessionSeen(stopped)` → `launchBlocked(stillPriorPaused)` → SWITCH_NEEDS_UNLOCK — **the wrong line** |
| ✅ same clip again 77 s later (entry 1794188497352) | `mediaPriorState: error` → `sessionSeen(stopped)` → `confirmed`, screen still locked |
| ❓ `readyEarconRoute: phone` on the first (cold SCO) press of the day | still open, unchanged since v1.3.37 |

## The finding

Entry 1794188420595, in order:

```
mediaPriorState: playing     mediaPriorTitle: Morning Coffee | …     keyguardSecure: true
ops: openYoutube:l-rxyIG_Iwc;link→webTargeted;launch→fullScreenIntent;
     nudge→sessionSeen(stopped);nudge→launchBlocked(stillPriorPaused)
playbackState: stopped       mediaActualTitle: Morning Coffee | …
```

1. The target was **playing** when we fired. v1.4.6 §1 therefore sent the first delivery with
   CLEAR_TASK, and v1.4.8's `clearTaskCanLand(playing=true, secure=true)` allowed it.
2. CLEAR_TASK tears YouTube's task down. The first poll tick, 800 ms later, found the session
   **stopped** on the old title — that is our restart in flight.
3. The same v1.4.8 gate then re-read the **live** state: stopped + secure keyguard →
   "cannot land" → the paused branch. `firstFireRestarted` was true, so it skipped the early
   `refireAt` exit, sat out the warm 9 s window, and declared `stillPriorPaused` →
   "คลิปที่หยุดไว้เปลี่ยนตอนจอล็อคไม่ได้ค่ะ กดเล่นต่อได้เลย หรือปลดล็อคจอ…".
4. Nothing had been paused. Both halves of that advice were wrong for this shape, and the
   proof is entry 1794188497352: the same clip asked for again with the screen still locked
   went `stopped` → `confirmed`. ("ลองสั่งเปลี่ยนอีกครั้ง", the SWITCH_NOT_LANDED line, is
   exactly what he did and exactly what worked.)

v1.4.8's gate was right about its own case — field log 1789697284287 had `mediaPriorState:
paused` **at fire time**, i.e. the rider had paused it. It conflated that with a stop we had
just caused ourselves. The two are told apart by a value we already stamp: `firstFireRestarted`
is true only when the target was playing when we fired.

## Fix

`MediaOrchestrator.pausedPriorAction(firstFireRestarted, nowMs, refireAt, windowExhausted)`,
pure, consumed in the STILL_PRIOR branch:

* `firstFireRestarted = true` → **SettleRestart**: log `nudge→restartSettling(<state>)` once,
  extend the window once to a cold start's budget (`pollStartAt + POLL_WINDOW_COLD_MS`, the
  v1.3.37 reasoning — a CLEAR_TASK restart is a cold start), keep polling. If it still has not
  landed when that runs out, declare plain `stillPrior` → SWITCH_NOT_LANDED. The paused-clip
  line is unreachable from this shape.
* `firstFireRestarted = false` (the rider's pause, v1.4.8) → unchanged: KeepPolling until
  `refireAt`, then **GiveUpNeedsUnlock** → SWITCH_NEEDS_UNLOCK.

Tests: `SelfInflictedStopTest` pins both shapes to their log lines and the line each ends on.
`WarmSwitchRestartTest` and `LaunchBlockedContractTest` unchanged and still green.

## What the next log should show

On a warm switch from PLAYING that goes `stopped` after the first delivery:
`nudge→restartSettling(stopped)` followed by `nudge→confirmed` inside ~15 s — or, if it truly
misses, `launchBlocked(stillPrior)` and the "ลองสั่งเปลี่ยนอีกครั้ง" line. `stillPriorPaused`
should appear only on entries whose `mediaPriorState` is `paused`/`stopped` at fire time.

## Claude could NOT run

Acceptance A–I — no device. `assembleDebug` + `testDebugUnitTest` ran locally (SDK 34).

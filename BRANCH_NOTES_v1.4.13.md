# v1.4.13 — a restart stuck behind the keyguard is waiting for one more launch

Branch: `claude/busy-dijkstra-h4yi7e` · versionCode 62 · versionName 1.4.13 · **awaiting rider validation**

Field log 1795045364488, `appVersion 1.4.12 (61)`, helmet ride 18 Nov 17:51–18:01, helmet SCO,
phone locked (`keyguardSecure: true`, `screenInteractive: false`), cellular. Rider: commands
have to be repeated.

## Scorecard first

| | this log |
|---|---|
| ✅ listening over playing media on the helmet | 10 presses, 10 heard on the first listen |
| ✅ v1.4.11 cold locked open (entry 1794999114758) | `browser→playFromSearch` → `browser→fallbackDeeplink` → `play#2,#3` → `confirmed` |
| ✅ warm switch from PLAYING, landed on its own | 1794999406302 (`restartSettling(stopped)` → `confirmed`), 1794999579885 (`sessionSeen(playing)` → `confirmed`) |
| ❌ **warm switch from PLAYING, stuck** | 1794999172458 and 1794999294967: `restartSettling(stopped)` → `launchBlocked(stillPrior)` |
| ❌ `wrongVideo` (entry 1794999334044) | a consequence of the stuck restart before it — see below |

Four warm switches from a playing target, two landed, two stuck. Field log 1794208929620 had
the same split (3 of 4).

## The finding

Both stuck entries are followed by the rider's next command, and both pairs read the same way:

```
1794999172458  prior: playing "Chill & Enjoy Music…"   want 53TJ1Zo-M1w "Start Your Day Right…"
  ops: …launch→fullScreenIntent;nudge→sessionSeen(stopped);nudge→restartSettling(stopped);
       nudge→launchBlocked(stillPrior)          playbackState: stopped, actual title = prior
1794999213657  (41 s later)  prior: stopped "Chill & Enjoy Music…"   want 53TJ1Zo-M1w again
  ops: …launch→fullScreenIntent;nudge→sessionSeen(stopped);nudge→confirmed

1794999294967  prior: playing "Start Your Day Right…"  want C_2rJ7_hFGY "Momos & Maggie…"
  ops: …nudge→sessionSeen(stopped);nudge→restartSettling(stopped);nudge→launchBlocked(stillPrior)
1794999334044  (40 s later)  prior: stopped "Start Your Day Right…"  want XGt1YZi9k7U "VIRAL PHONK…"
  ops: …launch→fullScreenIntent;nudge→sessionSeen(stopped);nudge→launchBlocked(wrongVideo)
  mediaActualTitle: "Momos & Maggie…"   playbackState: playing
```

1. The target was playing, so the first delivery carried CLEAR_TASK (v1.4.6). The old clip
   stopped. The session then sat `stopped` on the old title for the whole 15 s settle window
   (v1.4.10) and the rider was told to say it again — with his music now gone.
2. 40 s later the session was **still** stopped on the old title (`mediaPriorState: stopped`
   at the next fire). Waiting longer would not have helped.
3. Because the prior state was now stopped behind a secure keyguard, the next first delivery
   was plain (v1.4.8 gate). That plain launch is what started playback, at once
   (`sessionSeen(stopped)` → verdict on the next ticks, no `play#`).
4. The second pair says **what** it started: the rider had asked for a different clip and
   YouTube played the one the stuck restart had loaded. The plain launch did not navigate. It
   woke the task.

So "say it again worked" (v1.4.10's reading of 1794188420595 → 1794188497352, which is this
shape a third time) was half right: a second launch works, but it works by waking the first
one, whatever it asks for. Three stuck restarts in two logs, three woken by the next plain
launch, none by waiting.

**What the log cannot prove:** why YouTube holds the restarted task until a second launch
arrives, and whether a launch sent 6 s after the restart wakes it as reliably as the rider's
did at 40–77 s. The op log has no clock, so it also cannot say how long the two restarts that
landed on their own took. This build measures both.

## Fix

* **One plain wake launch** (`MediaOrchestrator`, SettleRestart branch) — when our own
  CLEAR_TASK restart is still paused/stopped on the old title `WAKE_SETTLING_RESTART_MS` (6 s)
  into the poll, fire the **same** link once more with no CLEAR_TASK. Nothing is torn down;
  a restart that was about to land is only brought forward. Op log:
  `nudge→wakeRestart(plain)@<ms>` followed by the launch's own `link→…;launch→…` ops.
  The title check judges the result exactly as before, and if the window still runs out the
  line is still `stillPrior` → SWITCH_NOT_LANDED.
* `shouldWakeSettlingRestart(alreadyWoken, nowMs, wakeAt, haveLinkTarget)` is pure;
  `SettlingRestartWakeTest` pins it to the entries above. Once per launch, separate from the
  `refired` CLEAR_TASK escalation, and unreachable for a clip the rider paused himself
  (v1.4.8: that shape still answers GiveUpNeedsUnlock).
* **`mediaResolvedMs`** on the debug entry — ms from the launch to the nudge's verdict
  (confirmed, blocked or gave up). Shown as `resolved:<ms>` in the on-phone log line.

Iron rules: #1 the wake goes through `fireYoutubeIntent` with the explicit YouTube package,
no media key; #2 unchanged; #3 the wake and its timing are in the op log.

## Not changed, on purpose

* **`wrongVideo` (entry 1794999334044)** — it happened because a stuck restart was still
  pending when the next command arrived. If the wake lands, that state no longer exists when
  the next command fires. If the next log still shows `wrongVideo` after a
  `wakeRestart(plain)`, that is a separate fix with its own evidence.
* The "music" request that returned a food video (entry 1794999294967, STT heard
  "เพลงแนว Hungry Show") is a search problem and is handled in the n8n workflow, outside this
  repo.

## What the next log should show

On a warm switch from PLAYING behind the lock screen:

* landed on its own: `…restartSettling(stopped);nudge→confirmed` or
  `sessionSeen(playing);nudge→confirmed`, with `mediaResolvedMs` saying how long it took;
* was stuck: `…restartSettling(stopped);nudge→wakeRestart(plain)@~6800ms;link→webTargeted;
  launch→fullScreenIntent;nudge→confirmed`, the requested title, `mediaResolvedMs` under 15 s,
  and the rider did **not** have to repeat himself;
* the wake did not help: the same ops ending `launchBlocked(stillPrior)`. Send the log — the
  timing or the mechanism is wrong and `mediaResolvedMs` plus the `@<ms>` say which.

## Claude could NOT run

Acceptance A–I — no device. Whether the wake lands is instrumented behaviour and belongs to
the road test (scenarios with a locked warm switch). Locally: `testDebugUnitTest` (80 suites,
757 tests, 0 failures) and `assembleDebug`.

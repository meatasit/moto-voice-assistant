# v1.4.5 — five findings from field log 1789561893967

Branch: `main` · versionCode 54 · versionName 1.4.5 · **awaiting rider validation**

The rider asked whether the code already fixed what his latest log showed. It did not. Every
change below names the entry that proves it — sprint rule **ห้ามแก้ก่อนพิสูจน์**. No new
features; one honest-error path, one dead action wired up, and three observability fixes that
exist because this review was harder than it should have been.

The log itself: 14 entries, 13:53–14:31. Seven `youtube_play`-shaped commands, **six blocked**.

## 1 — `launch_blocked(noSession)` six times: the poll had no escalation

Entries 1789559482592, 1789559528335, 1789559608775, 1789559731063, 1789559964188,
1789561588003. Identical shape:

```
finishReason: launch_blocked          screenLocked: true    keyguardSecure: true
fsiTrampolineRan: true                screenInteractive: false
fsiTrampolineLaunchOk: true           mediaBrowserAvail: yt=true,ytm=false
ops: openYoutube:VID;link→webTargeted;launch→fullScreenIntent;nudge→launchBlocked(noSession)
```

The full-screen intent was honored, the trampoline's `startActivity` succeeded — and YouTube
never registered a MediaSession. Not one of the six logged a `nudge→sessionSeen`.

`MediaOrchestrator`'s `ctrl == null` branch had exactly two moves: poll again, or give up at
the window end. The CLEAR_TASK escalation v1.3.36 added lives in the `STILL_PRIOR` branch,
which needs a controller to reach, so it could never help this shape.

The fix was already visible in the log. Entry 1789561588003 blocked; **40 s later**
1789561627775 — same lock state, same transport, same cold start — landed:

```
ops: openYoutube:eoXtKw_bW_s;link→webTargeted;launch→fullScreenIntent;
     nudge→sessionSeen(none);nudge→play#1;nudge→confirmed        playbackState: playing
```

Firing the launch a second time is what makes it stick, and the only thing doing that was the
rider repeating himself. Now: if no session has appeared after `REFIRE_NO_SESSION_MS` (6 s —
past the documented 800 ms–3 s cold start, so a merely-slow launch is not torn down), re-fire
once with `CLEAR_TASK` and give it a cold window from that moment. Gated on `!sawSession`, so
the "opened then stopped" case keeps its own honest line instead of a restart into the same
keyguard. One escalation per launch total, shared with the stillPrior branch — two CLEAR_TASK
restarts racing is the re-fire war v1.3.36 already paid for. `NoSessionRefireTest`.

Note the successful entry's `sessionSeen(none)`: while locked YouTube comes up **not playing**
and our `play()` is what starts it. The nudge was always doing its half correctly.

## 2 — `spotify_play` was never wired to anything

Entry 1789559688055: "เปิดเพลงจาก spotify ได้ไหม" → the workflow answered `spotify_play`, the
rider heard "เปิดสปอติฟายต่อจากเดิมให้ได้ค่ะ", and:

```
finishReason: ok   mediaCtrlUsed: false   mediaTargetPkg: —   mediaOperations: —
```

A media action with no target and no op log at all, against Rule #3. The `when (action)` in
`VoiceCommandPipeline` has handled `youtube_play` / `fm` / `stop` / `chat` / `none` / `seek`
since v1.3.20 and never had a `spotify_play` case — it fell through to the `else` that only
speaks. `MediaOrchestrator.SPOTIFY_PKG` even carries the KDoc "Referenced by webhook
`action=spotify_play`"; nothing referenced it.

Now routed to `playContinue(appHint = "spotify")`, which is Rule #1-clean by construction: the
rider named the app, so the orchestrator gets an explicit target package and never a bare
media key. `lastOpenedApp` is updated so a following "เล่นต่อ" comes back to Spotify.

This also promoted the silent path v1.4.4 filed below the cut: a non-YouTube target with no
session could only be launched by package, which comes up **paused**, and the old code
returned `Success` so the caller said "เล่นแล้ว" over silence. New
`Result.AppLaunchedNotPlaying` + `SPOTIFY_OPENED_NOT_PLAYING`; a launch that does not fire at
all is now `LaunchFailed` and stamped, not silent.

## 3 — v1.4.4 is still unproven in the field

Not a code change — a statement. Against v1.4.4's own "what the next log should show":

| expected signal | this log |
|---|---|
| `nudge→cancelled(newInteraction)` | absent — rider never spoke inside a launch window (gaps 40–52 s). **Untested** |
| no `repauseForeign[com.moto.voice]` | true, but no `repauseForeign` at all fired (the Spotify sessions were paused). **Weak** |
| `openYoutube→launchFailed` | absent — every launch fired. **Untested** |
| `launchBlocked(sessionLost)` | absent — all six were `noSession`. **Untested** |
| `SEEK_AMOUNT_UNKNOWN` | no `seek` in the log at all. **Untested** |

So the acceptance rounds for this branch cover v1.4.4's five signals as well as v1.4.5's.
They are listed at the bottom.

## 4 — the export could not say which build produced it

The question that should have opened this review — *is the rider even running the build with
the fixes in it?* — was unanswerable. `DebugLog.exportToFile` wrote a bare array of entries,
`DebugEntry` had no version field, and every field present in the log had existed since
v1.4.2, so the file could equally have been v1.4.2, v1.4.3 or v1.4.4.

`DebugEntry.appVersion` ("1.4.5 (54)") is now the first key of every entry, read off
`PackageManager` at `Application.onCreate` (no `BuildConfig`, so no gradle `buildFeatures`
change). **Per entry, not per file** — entries get excerpted one at a time into branch notes
and chat, and a header version is gone the moment someone pastes the entry that matters. Also
in the export filename and on the in-app Debug Log rows.

## 5 — `STT 11` on five interactions that succeeded

Every entry in the log carrying `error: "STT 11"` — 1789559455950, 1789559709013,
1789559580187, 1789561561232, 1789561874600 — has `finishReason: ok`, a correct `sttFinal`,
`sttConfidence` ≥ 0.91 and `sttRetryCount: 0`. All five are `action=chat`.

`chat` is exactly what sets `followupEligible`. `runFollowUpWindow` reuses the interaction's
own `DebugEntry` (it has to — a follow-up continues the same interaction), so when the rider
had nothing more to say and the 4 s recognizer dropped, `STT 11` landed on an interaction that
had already finished successfully. Five entries reading as failures that were not — in a log
being read to find failures.

The breadcrumb is kept and labelled: `followup_stt 11`, deliberately not a substring of `STT`
so `grep "STT 11"` over an export cannot match it. `DebugEntry.isErrorlike()` excludes it from
the "Errors only" chip — and that predicate, previously written out once inline in
`DebugLogActivity` and again, separately, in `ErrorFilterTest`, is now one function both use.

### 5b — "เปิด YouTube ให้ฟังหน่อย" went to the cloud to be asked a local question

Entries 1789559709013 and 1789561561232. `SlotFiller`'s opener regexes are end-anchored, so
the politeness tail made "เปิด youtube ให้ฟังหน่อย" not a bare opener. It round-tripped ~4 s to
the LLM and came back `action=chat` / "อยากฟังอะไรดีคะ" — the same question `promptFor` asks
instantly, arriving slower and with no slot to fill afterwards. Twice in one session.

`stripPoliteTail` removes a trailing politeness token before the match. The guard that keeps
the slot filler from stealing real commands is untouched (still whole-string), and the test
pins the three sentences from this same log that DO carry a payload — "เปิด youtube
ช่องในอาร์มให้ฟังหน่อย", "เปิดเพลงจาก youtube ให้ฟังหน่อย", "เปิดเพลงจาก spotify ได้ไหม" — as still
reaching the webhook. The token list comes from the rider's logs and should not grow
speculatively.

## Tests

`NoSessionRefireTest`, `SlotFillerPoliteTailTest`, `AppVersionStampTest`, and three added
cases in `ErrorFilterTest`. All pure JVM. The instrumented halves — does the CLEAR_TASK
re-fire actually land on a locked S24, does Spotify resume — are the Acceptance Suite's.

**Claude could not run them.** There is no Android SDK in the sandbox and Gradle cannot reach
Google Maven from it (`Plugin [id: 'com.android.application', version: '8.4.2'] was not
found`), so `testDebugUnitTest` did not execute locally; CI runs it on this PR. The
`SlotFiller` regex — the one change that could silently steal a working command — was
hand-verified against all 13 rider sentences in this log plus every existing `SlotFillerTest`
expectation, 28/28.

## What the next log should show

**v1.4.5:**
* `appVersion: "1.4.5 (54)"` on every entry — if it is absent or older, nothing below counts.
* `nudge→refireNoSession(clearTask)` on a locked cold open, followed by
  `sessionSeen;play#N;confirmed` rather than `launchBlocked(noSession)`.
* `spotify_play` entries carrying `mediaTargetPkg: com.spotify.music` and a real
  `mediaOperations` string — never an `ok` entry with neither.
* `followup_stt 11` instead of `STT 11` on a chat the rider didn't continue, and those
  entries gone from the "Errors only" chip.
* "เปิด youtube ให้ฟังหน่อย" answered by "เปิดอะไรดีคะ" with `slotFilled: true` and **no**
  `webhookRequest` for the opener itself.

**Still owed from v1.4.4** (see §3): `nudge→cancelled(newInteraction)`,
`openYoutube→launchFailed`, `launchBlocked(sessionLost)` only where
`mediaActualTitle != mediaPriorTitle`, `SEEK_AMOUNT_UNKNOWN`, and no
`repauseForeign[com.moto.voice]`.

Rider validates — Claude cannot run acceptance. ACCEPTANCE.md A–I on the S24 Ultra + Vimoto
V11X, **two clean rounds in a row** before merge, debug-log JSON attached.

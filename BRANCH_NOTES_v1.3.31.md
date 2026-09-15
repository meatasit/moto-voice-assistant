# Branch note — v1.3.31 pre-pause race fix (per-tick `repauseForeign`)

**Build:** versionCode 37 / versionName 1.3.31 (Build 85, `build-85`, app-debug.apk)
**PR:** #7 (merged to main, CI green — tests + assembleDebug)
**Branch:** fix-fsi-refire-v1.3.29 (HEAD at the v1.3.31 commit `b5936d1`)

## What changed

Per-tick foreign re-pause inside `scheduleTargetedNudge`'s ~9s poll window. Each nudge tick
pauses any non-YouTube `PLAYING`/`BUFFERING` session via a targeted `controller.pause()`
(Iron Rule #1 — explicit targetPkg, never a bare media key), logged once per package as
`repauseForeign[pkg]`. Shared `foreignActivePlayers()` helper.

**Why:** the v1.3.25 `prepauseForeignPlayers()` guard was a single snapshot taken at the
launch instant. A Spotify that auto-resumes *after* the `openYoutube` snapshot — the
BT-reconnect autoplay race — slipped straight through and re-stole focus, producing
`nudge→launchBlocked(noSession)` + `mediaCtrlPkgMiss=com.spotify.music`. Re-checking every
tick closes that structural gap.

**Scope:** addresses the 3 `noSession` locked-open failures only. The 4 `stillPrior`
failures (YouTube already has focus, deep-link navigation BAL-dropped on a locked YT→YT
switch) are a **separate open front** — not touched by this change.

## Field validation — log `moto_voice_debug_1784819980337.json` (2026-07-23)

One file, both states: two clusters ~9.7h apart, all `screenLocked=true`, SCO connected.

### Pre-fix cluster (ts 1784770445782) — target symptom reproduced
- `finishReason=launch_blocked`, `nudge→launchBlocked(noSession)`,
  `mediaCtrlPkgMiss=com.spotify.music`
- ~30 min after the v1.3.31-motivating log `1784768501667`; same failing build, same
  Spotify-hijack noSession vector. Honest error path fired (`launchBlocked=true`, not silent).

### Post-fix cluster (ts 1784805203551 – 1784806358236) — 3/3 locked plays OK
| ts | video | key ops | result |
|---|---|---|---|
| 1784805203551 | 1JKFcYk4nrg | **`repauseForeign[com.spotify.music]`** (`mediaForeignPaused: com.spotify.music=buffering`) → confirmed | ✅ playing |
| 1784805354068 | X6NfrHKo36M | play#1 → play#2 → confirmed | ✅ playing |
| 1784806358236 | Ctg1YOjZios | `nudge→refireSwitch` → play#1 → confirmed | ✅ playing |

**The load-bearing entry is 1784805203551:** the FIRST time the pre-pause guard fired *in
anger*. Spotify was actively `buffering` during the nudge window, per-tick re-pause caught
it, YouTube took over clean instead of the old `noSession` block. The launch-instant-snapshot
race is proven closed. FSI trampoline + id-rotation (v1.3.29) still holding
(`fsiTrampolineRan/LaunchOk=true`, `refireSwitch`→confirmed). Every op carries
`mediaTargetPkg=com.google.android.youtube` + full `mediaOperations` trail (Rules #1/#3).

Noise (not bugs): 3× `no_speech`/`STT 11` (helmet-mic silence), 2× `prev_exit_other(16)`
(Android killing the backgrounded process). No media path involved.

## Acceptance status — **1 of 2 clean rounds**

This log validates the Spotify-buffering `noSession` case **once**. CLAUDE.md requires **two
clean rounds back-to-back** before merge-confidence. Need one more Spotify-active /
BT-reconnect (morning) round ending `nudge→confirmed`.

**Rider validates — Claude cannot run acceptance (no device build env in sandbox).**

## Still open
- `stillPrior` locked YT→YT switch (navigation-BAL on the deep link; `refireSwitch`-once
  insufficient) — separate front, no data in this log.
- Deferred audio-signal / TTS pacing UX (kept out of this build to keep the log attributable
  to one change).
